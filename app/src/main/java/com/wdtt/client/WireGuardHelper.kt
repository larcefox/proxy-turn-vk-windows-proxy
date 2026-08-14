package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.Inet4Address

class WireGuardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val backend = (appContext as WdttApplication).getBackend(context)

    private companion object {
        const val EMPTY_WHITELIST_MESSAGE = "В режиме БС выберите хотя бы одно приложение"
        // Full IPv4 route with RFC 1918 networks and multicast removed. Keeping LAN destinations
        // off VpnService lets local proxy clients reach the phone while proxy egress stays tunneled.
        val PUBLIC_IPV4_ROUTES = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
            "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6",
            "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9",
            "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9",
            "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15",
            "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8",
            "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4"
        )
        val wgMutex = Mutex()
        var sharedTunnel: WgTunnel? = null
        var disabledByEmptyWhitelist = false
    }

    class WgTunnel : Tunnel {
        override fun getName() = "wdtt"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    enum class WatchdogState {
        UP,
        DOWN,
        DISABLED_BY_EMPTY_WHITELIST
    }

    suspend fun startTunnel(configString: String) = wgMutex.withLock {
        startTunnelLocked(configString)
    }

    private suspend fun startTunnelLocked(configString: String) = withContext(Dispatchers.IO) {
        try {
            if (VpnService.prepare(appContext) != null) {
                throw IllegalStateException("VPN-разрешение не выдано")
            }

            ensureGoBackendServiceStarted()

            val parsedConfig = Config.parse(ByteArrayInputStream(configString.toByteArray(Charsets.UTF_8)))

            val builder = Interface.Builder()
                .parseAddresses(parsedConfig.`interface`.addresses.joinToString(", ") { it.toString() })
            
            if (parsedConfig.`interface`.dnsServers.isNotEmpty()) {
                builder.parseDnsServers(parsedConfig.`interface`.dnsServers.joinToString(", ") { it.hostAddress ?: "" })
            }
            if (parsedConfig.`interface`.listenPort.isPresent) {
                builder.parseListenPort(parsedConfig.`interface`.listenPort.get().toString())
            }
            if (parsedConfig.`interface`.mtu.isPresent) {
                val serverMtu = parsedConfig.`interface`.mtu.get()
                
                builder.parseMtu(serverMtu.coerceAtLeast(1280).toString())
            } else {
                builder.parseMtu("1280")
            }
            builder.parsePrivateKey(parsedConfig.`interface`.keyPair.privateKey.toBase64())

            
            val settingsStore = SettingsStore(appContext)
            settingsStore.migrateLegacyWhitelistMode()
            val savedExcluded = settingsStore.excludedApps.first()
            val isWhitelist = settingsStore.isWhitelist.first()
            val localNetworkAccess = settingsStore.localNetworkAccess.first()
            val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()
            val transportPackages = setOf(appContext.packageName, "com.vkontakte.android", "com.vk.calls")

            if (isWhitelist) {
                val installedIncluded = userSelected
                    .filter { it !in transportPackages && it.isInstalledPackage() }
                    .toSet()
                if (installedIncluded.isEmpty()) {
                    throw IllegalStateException(EMPTY_WHITELIST_MESSAGE)
                }
                builder.includeApplications(installedIncluded)
            } else {
                val excluded = transportPackages.toMutableSet()
                excluded.addAll(userSelected)
                val installedExcluded = excluded.filter { it.isInstalledPackage() }.toSet()
                if (installedExcluded.isNotEmpty()) {
                    builder.excludeApplications(installedExcluded)
                }
            }

            val newInterface = builder.build()

            val peerBuilder = Peer.Builder()
            val firstPeer = parsedConfig.peers.firstOrNull()
                ?: throw IllegalStateException("WireGuard config has no peer")
            firstPeer.let { peer ->
                peerBuilder.parsePublicKey(peer.publicKey.toBase64())
                if (peer.preSharedKey.isPresent) peerBuilder.parsePreSharedKey(peer.preSharedKey.get().toBase64())
                if (peer.endpoint.isPresent) peerBuilder.parseEndpoint(peer.endpoint.get().toString())
                if (peer.persistentKeepalive.isPresent) peerBuilder.parsePersistentKeepalive(peer.persistentKeepalive.get().toString())
            }
            
            val allowedIps = if (localNetworkAccess) {
                val dnsRoutes = parsedConfig.`interface`.dnsServers
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .map { "$it/32" }
                (PUBLIC_IPV4_ROUTES + dnsRoutes).distinct().joinToString(", ")
            } else {
                "0.0.0.0/0"
            }
            peerBuilder.parseAllowedIPs(allowedIps)
            
            val finalConfig = Config.Builder()
                .setInterface(newInterface)
                .addPeer(peerBuilder.build())
                .build()

            disabledByEmptyWhitelist = false
            stopSharedTunnel("previous tunnel before restart")
            val nextTunnel = WgTunnel()
            setTunnelUpWithRetry(nextTunnel, finalConfig)
            sharedTunnel = nextTunnel
            Log.d("WG", "WireGuard tunnel started successfully")
        } catch (e: Exception) {
            if (e.isEmptyWhitelistFailure()) {
                throw e
            }
            val detailed = "WireGuard start failed: ${e.readableMessage()}; ${configString.describeWireGuardConfig()}"
            Log.e("WG", detailed)
            e.printStackTrace()
            throw IllegalStateException(detailed, e)
        }
    }

    suspend fun reloadTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val configFlow = TunnelManager.config.first() ?: return@withContext
                startTunnelLocked(configFlow)
                Log.d("WG", "WireGuard tunnel reloaded for new exceptions")
            } catch (e: Exception) {
                if (e.isEmptyWhitelistFailure()) {
                    stopSharedTunnel("empty whitelist")
                    disabledByEmptyWhitelist = true
                    Log.d("WG", "WireGuard tunnel disabled for empty whitelist")
                } else {
                    Log.e("WG", "Failed to reload WireGuard: ${e.readableMessage()}")
                }
            }
        }
    }

    suspend fun isTunnelUp(): Boolean = wgMutex.withLock {
        isSharedTunnelUpLocked()
    }

    suspend fun watchdogState(): WatchdogState = wgMutex.withLock {
        when {
            disabledByEmptyWhitelist -> WatchdogState.DISABLED_BY_EMPTY_WHITELIST
            isSharedTunnelUpLocked() -> WatchdogState.UP
            else -> WatchdogState.DOWN
        }
    }

    suspend fun stopTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                sharedTunnel?.let {
                    backend.setState(it, Tunnel.State.DOWN, null)
                    sharedTunnel = null
                    Log.d("WG", "WireGuard tunnel stopped")
                }
                disabledByEmptyWhitelist = false
            } catch (e: Exception) {
                Log.e("WG", "Failed to stop WireGuard: ${e.readableMessage()}")
            }
        }
    }

    private suspend fun ensureGoBackendServiceStarted() {
        withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(appContext, GoBackend.VpnService::class.java)
                appContext.startService(intent)
            }.onFailure {
                Log.w("WG", "GoBackend service warmup failed: ${it.readableMessage()}")
            }
        }
        delay(300)
    }

    private suspend fun stopSharedTunnel(reason: String) {
        sharedTunnel?.let { existingTunnel ->
            try {
                backend.setState(existingTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w("WG", "Failed to stop tunnel for $reason: ${e.readableMessage()}")
            }
            sharedTunnel = null
            delay(150)
        }
    }

    private suspend fun setTunnelUpWithRetry(nextTunnel: WgTunnel, finalConfig: Config) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                backend.setState(nextTunnel, Tunnel.State.UP, finalConfig)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w("WG", "WireGuard UP attempt ${attempt + 1}/3 failed: ${e.readableMessage()}")
                runCatching { backend.setState(nextTunnel, Tunnel.State.DOWN, null) }
                ensureGoBackendServiceStarted()
                delay(250L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("WireGuard UP failed")
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun Throwable.isEmptyWhitelistFailure(): Boolean {
        return message?.contains(EMPTY_WHITELIST_MESSAGE) == true
    }

    private fun isSharedTunnelUpLocked(): Boolean {
        val current = sharedTunnel ?: return false
        return try {
            backend.getState(current) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }

    private fun String.isInstalledPackage(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(this, 0)
            true
        }.getOrDefault(false)
    }

    private fun String.describeWireGuardConfig(): String {
        val lines = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hasInterface = lines.any { it.equals("[Interface]", ignoreCase = true) }
        val hasPeer = lines.any { it.equals("[Peer]", ignoreCase = true) }
        val hasPrivateKey = lines.any { it.startsWith("PrivateKey", ignoreCase = true) }
        val hasPublicKey = lines.any { it.startsWith("PublicKey", ignoreCase = true) }
        val hasAddress = lines.any { it.startsWith("Address", ignoreCase = true) }
        val endpoint = lines.firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            ?.take(80)
            ?: "none"
        return "config lines=${lines.size}, interface=$hasInterface, peer=$hasPeer, privateKey=$hasPrivateKey, publicKey=$hasPublicKey, address=$hasAddress, endpoint=$endpoint"
    }
}
