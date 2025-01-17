

package com.localzet.shadowsocks.bg

import android.content.Context
import android.util.Base64
import com.localzet.shadowsocks.Core.app
import com.localzet.shadowsocks.acl.Acl
import com.localzet.shadowsocks.acl.AclSyncer
import com.localzet.shadowsocks.core.R
import com.localzet.shadowsocks.database.Profile
import com.localzet.shadowsocks.plugin.PluginConfiguration
import com.localzet.shadowsocks.plugin.PluginManager
import com.localzet.shadowsocks.preference.DataStore
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * This class sets up environment for ss-local.
 */
class ProxyInstance(val profile: Profile, private val route: String = profile.route) {
    init {
        require(profile.host.isNotEmpty() && (profile.method == "none" || profile.password.isNotEmpty())) {
            app.getString(R.string.proxy_empty)
        }
        // check the crypto
        require(profile.method !in arrayOf("aes-192-gcm", "chacha20", "salsa20")) {
            "cipher ${profile.method} is deprecated."
        }
        // check the key format for aead-2022-cipher
        if (profile.method in setOf(
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
            )) {
            for (pwd in profile.password.split(":")) {
                require(Base64.decode(pwd, Base64.DEFAULT).size in arrayOf(16, 32)) {
                    "The Base64 Key is invalid."
                }
            }
        }
    }

    private var configFile: File? = null
    var trafficMonitor: TrafficMonitor? = null
    val plugin by lazy { PluginManager.init(PluginConfiguration(profile.plugin ?: "")) }

    /**
     * Sensitive shadowsocks configuration file requires extra protection. It may be stored in encrypted storage or
     * device storage, depending on which is currently available.
     */
    fun start(service: BaseService.Interface, stat: File, configFile: File, mode: String, dnsRelay: Boolean = true) {
        // setup traffic monitor path
        trafficMonitor = TrafficMonitor(stat)

        // init JSON config
        this.configFile = configFile
        val config = profile.toJson()
        plugin?.let { (path, opts, isV2) ->
            if (service.isVpnService) {
                if (isV2) opts["__android_vpn"] = "" else config.put("plugin_args", JSONArray(arrayOf("-V")))
            }
            config.put("plugin", path).put("plugin_opts", opts.toString())
        }
        config.put("dns", "system")
        config.put("locals", JSONArray().apply {
            // local SOCKS5 proxy
            put(JSONObject().apply {
                put("local_address", DataStore.listenAddress)
                put("local_port", DataStore.portProxy)
                put("local_udp_address", DataStore.listenAddress)
                put("local_udp_port", DataStore.portProxy)
                put("mode", mode)
            })

            // local DNS proxy
            if (dnsRelay) try {
                URI("dns://${profile.remoteDns}")
            } catch (e: URISyntaxException) {
                throw BaseService.ExpectedExceptionWrapper(e)
            }.let { dns ->
                put(JSONObject().apply {
                    put("local_address", DataStore.listenAddress)
                    put("local_port", DataStore.portLocalDns)
                    put("local_dns_address", "local_dns_path")
                    put("remote_dns_address", dns.host ?: "0.0.0.0")
                    put("remote_dns_port", if (dns.port < 0) 53 else dns.port)
                    put("protocol", "dns")
                })
            }
        })
        configFile.writeText(config.toString())

        // build the command line
        val cmd = arrayListOf(
                File((service as Context).applicationInfo.nativeLibraryDir, Executable.SS_LOCAL).absolutePath,
                "--stat-path", stat.absolutePath,
                "-c", configFile.absolutePath,
        )

        if (service.isVpnService) cmd += "--vpn"

        if (route != Acl.ALL) {
            cmd += "--acl"
            cmd += Acl.getFile(route).absolutePath
        }

        service.data.processes!!.start(cmd)
    }

    fun scheduleUpdate() {
        if (route !in arrayOf(Acl.ALL, Acl.CUSTOM_RULES)) AclSyncer.schedule(route)
    }

    fun shutdown(scope: CoroutineScope) {
        trafficMonitor?.apply {
            thread.shutdown(scope)
            persistStats(profile.id)    // Make sure update total traffic when stopping the runner
        }
        trafficMonitor = null
        configFile?.delete()    // remove old config possibly in device storage
        configFile = null
    }
}
