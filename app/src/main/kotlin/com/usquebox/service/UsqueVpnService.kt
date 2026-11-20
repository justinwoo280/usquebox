package com.usquebox.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.usquebox.MainActivity
import com.usquebox.R
import com.usquebox.UsqueBoxApp
import com.usquebox.data.ConfigStore
import com.usquebox.data.ConfigException
import com.usquebox.data.ProxyMode
import com.usquebox.data.UsqueConfig
import com.usquebox.ui.configIssueMessage
import com.usquebox.ui.formatTrafficBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import mobile.Mobile
import mobile.TunnelListener
import java.net.InetAddress

class UsqueVpnService : VpnService() {

    private lateinit var configStore: ConfigStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Written/read from multiple threads (main, Binder for onRevoke, IO). Volatile
    // for visibility; the actual stop is idempotent-guarded in stopIfRunning().
    @Volatile private var tunnelStopped = false
    private var tunPfd: ParcelFileDescriptor? = null
    private var udpPfd: ParcelFileDescriptor? = null
    private var engineStarted = false
    private var stopJob: Job? = null
    private var trafficJob: Job? = null
    private var lastSent: Long = 0
    private var lastRecv: Long = 0
    private var lastTrafficTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        // Register this instance as the current one. If a previous instance is
        // still tearing down, its onDestroy will only clear `instance` if it is
        // still itself (see onDestroy), so we won't be clobbered.
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel()
            ACTION_DISCONNECT -> stopIfRunning(blocking = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopIfRunning(blocking = true)
        serviceScope.cancel()
        // Only clear the shared `instance` if we are still the current one. A
        // fast disconnect→reconnect can create a new service whose onCreate set
        // `instance = newService` while this (old) instance is being destroyed;
        // clearing unconditionally would null out the live new service.
        clearInstanceIfSelf()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopIfRunning(blocking = true)
        serviceScope.cancel()
        clearInstanceIfSelf()
    }

    /**
     * Idempotent, thread-safe stop entry. Guards against concurrent callers
     * (user disconnect on main thread racing system onRevoke on the Binder
     * thread, or onDestroy). Only the first caller runs the teardown.
     */
    private fun stopIfRunning(blocking: Boolean) {
        val job = synchronized(this) {
            if (tunnelStopped) return@synchronized stopJob
            tunnelStopped = true
            stopTrafficUpdates()
            // Independent of serviceScope: onDestroy may cancel that scope
            // before native shutdown has finished closing its descriptors.
            val snapshot = snapshotAndClearFds()
            CoroutineScope(Dispatchers.IO).launch {
                teardownGoEngine(snapshot)
                stopSelf()
            }.also { stopJob = it }
        }
        if (blocking && job != null) {
            runBlocking {
                withTimeoutOrNull(STOP_TIMEOUT) { job.join() }
                    ?: Log.w(TAG, "Tunnel stop still running after ${STOP_TIMEOUT}ms")
            }
        }
    }

    @Synchronized
    private fun clearInstanceIfSelf() {
        if (instance === this) {
            instance = null
        }
    }

    @Synchronized
    private fun startTunnel() {
        if (tunnelStopped || engineStarted) return
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))
        _tunnelState.value = TunnelState.Connecting
        val configJson = configStore.configJson
        if (configJson.isNullOrBlank()) {
            failStartup(R.string.no_config)
            return
        }

        val parsed = try {
            UsqueConfig.parse(configJson).prepare()
        } catch (e: ConfigException) {
            failStartup(e.issue.configIssueMessage())
            return
        }

        val builder = Builder().setMtu(parsed.mtu).setSession(getString(R.string.app_name))
        // Android blocks a family for VPN-covered apps if no address, route or
        // DNS server enables it. Don't call allowFamily(), which would bypass it.
        try {
            parsed.ipv4Address?.let {
                builder.addAddress(it, 32).addRoute("0.0.0.0", 0)
            }
            parsed.ipv6Address?.let {
                builder.addAddress(it, 128).addRoute("::", 0)
            }
            parsed.dnsServers.forEach { builder.addDnsServer(it) }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Android rejected the VPN network settings", e)
            failStartup(R.string.error_vpn_interface)
            return
        }

        // Per-app proxy: configure app routing on the VPN builder
        val selfPkg = packageName
        val proxyMode = configStore.proxyMode
        val selectedApps = configStore.selectedApps
        when (proxyMode) {
            ProxyMode.BYPASS -> {
                // Blacklist: proxy everything EXCEPT selected apps
                val disallowed = mutableSetOf(selfPkg)
                for (pkg in selectedApps) {
                    disallowed.add(pkg)
                }
                for (pkg in disallowed) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "Per-app BYPASS: ${disallowed.size} apps excluded")
            }
            ProxyMode.PROXY_ONLY -> {
                // Whitelist: ONLY proxy selected apps
                // Do NOT call addDisallowedApplication here — Android 14+ Builder
                // treats allowed/disallowed as mutually exclusive modes.
                // The app itself is not in the allow-list. Its HTTP/2 control
                // socket then stays outside the VPN, just like the protected UDP socket.
                var allowed = 0
                for (pkg in selectedApps) {
                    if (pkg != selfPkg) {
                        try {
                            builder.addAllowedApplication(pkg)
                            allowed++
                        } catch (_: Exception) {}
                    }
                }
                if (allowed == 0) {
                    failStartup(R.string.error_vpn_no_apps)
                    return
                }
                Log.d(TAG, "Per-app PROXY_ONLY: $allowed apps included")
            }
            ProxyMode.GLOBAL -> {
                builder.addDisallowedApplication(selfPkg)
                Log.d(TAG, "Global mode: all traffic proxied")
            }
        }

        // Use the family of the outer endpoint, not the inner TUN toggles.
        if (!parsed.useHttp2) {
            try {
                val pfd = openUdpSocket(parsed.useIPv6)
                udpPfd = pfd
                if (!protect(pfd.fd)) {
                    failStartup(R.string.error_vpn_protect)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to prepare outer UDP socket", e)
                failStartup(R.string.error_vpn_socket)
                return
            }
        }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to establish VPN interface", e)
            null
        }
        if (pfd == null) {
            failStartup(R.string.error_vpn_interface)
            return
        }
        tunPfd = pfd

        val listener = object : TunnelListener {
            override fun onStateChange(state: String?) {
                if (tunnelStopped || instance !== this@UsqueVpnService) return
                Log.d(TAG, "State change: $state")
                _tunnelState.value = when (state) {
                    "connecting" -> TunnelState.Connecting
                    "connected" -> TunnelState.Connected()
                    "reconnecting" -> TunnelState.Reconnecting()
                    "stopped" -> TunnelState.Stopped
                    "error" -> TunnelState.Error(R.string.notification_error)
                    else -> _tunnelState.value
                }
                updateNotification()
                if (state == "stopped" || state == "error") {
                    stopTrafficUpdates()
                    stopSelf()
                }
            }

            override fun onTraffic(sent: Long, recv: Long) {
                if (tunnelStopped || instance !== this@UsqueVpnService) return
                _tunnelState.update { current ->
                    when (current) {
                        is TunnelState.Connected -> current.copy(bytesSent = sent, bytesRecv = recv)
                        is TunnelState.Reconnecting -> current.copy(bytesSent = sent, bytesRecv = recv)
                        else -> current
                    }
                }
            }
        }

        Mobile.registerListener(listener)
        val tunFd = pfd.detachFd() // remove Android's fdsan tag before Go takes the FD
        tunPfd = null
        val err = Mobile.startTunnel(tunFd.toLong(), udpPfd?.fd?.toLong() ?: -1L, parsed.json)
        if (err.isNotEmpty()) {
            Mobile.unregisterListener()
            // StartTunnel did not take ownership on failure.
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            failStartup(R.string.error_vpn_start, err)
        } else {
            engineStarted = true
            startTrafficUpdates()
        }
    }

    private fun openUdpSocket(ipv6: Boolean): ParcelFileDescriptor {
        val domain = if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET
        val fd = Os.socket(domain, OsConstants.SOCK_DGRAM or OsConstants.SOCK_CLOEXEC, OsConstants.IPPROTO_UDP)
        try {
            if (ipv6) Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
            Os.bind(fd, InetAddress.getByName(if (ipv6) "::" else "0.0.0.0"), 0)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun failStartup(@StringRes message: Int, detail: String? = null) {
        runCatching { tunPfd?.close() }
        tunPfd = null
        runCatching { udpPfd?.close() }
        udpPfd = null
        tunnelStopped = true
        _tunnelState.value = TunnelState.Error(message, detail)
        stopSelf()
    }

    /** Go owns the detached TUN; Kotlin keeps the protected original UDP descriptor. */
    @Synchronized
    private fun snapshotAndClearFds(): FdSnapshot {
        val snapshot = FdSnapshot(engineStarted, udpPfd)
        engineStarted = false
        udpPfd = null
        runCatching { tunPfd?.close() } // only non-null before handing off to Go
        tunPfd = null
        return snapshot
    }

    private fun teardownGoEngine(snap: FdSnapshot) {
        if (snap.engineStarted) {
            runCatching { Mobile.unregisterListener() }
            runCatching { Mobile.stopTunnel() }
        }
        runCatching { snap.udpPfd?.close() }
        if (instance === this && _tunnelState.value !is TunnelState.Error) _tunnelState.value = TunnelState.Stopped
    }

    private data class FdSnapshot(val engineStarted: Boolean, val udpPfd: ParcelFileDescriptor?)

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, UsqueBoxApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val contentText = when (val state = _tunnelState.value) {
            is TunnelState.Connected -> getString(R.string.notification_connected_rates,
                getString(R.string.rate_format, formatTrafficBytes(state.rateSent)),
                getString(R.string.rate_format, formatTrafficBytes(state.rateRecv)))
            is TunnelState.Reconnecting -> getString(R.string.notification_reconnecting)
            is TunnelState.Connecting -> getString(R.string.notification_connecting)
            is TunnelState.Stopped -> getString(R.string.notification_disconnected)
            is TunnelState.Error -> getString(state.message)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun startTrafficUpdates() {
        trafficJob?.cancel()
        lastSent = 0
        lastRecv = 0
        lastTrafficTime = System.currentTimeMillis()
        trafficJob = serviceScope.launch {
            while (true) {
                delay(1000)
                updateTrafficRate()
            }
        }
    }

    private fun stopTrafficUpdates() {
        trafficJob?.cancel()
        trafficJob = null
    }

    private fun updateTrafficRate() {
        val current = _tunnelState.value
        if (current !is TunnelState.Connected) return
        val now = System.currentTimeMillis()
        val dt = (now - lastTrafficTime) / 1000.0
        lastTrafficTime = now

        val dSent = (current.bytesSent - lastSent).coerceAtLeast(0)
        val dRecv = (current.bytesRecv - lastRecv).coerceAtLeast(0)
        lastSent = current.bytesSent
        lastRecv = current.bytesRecv

        val rateSent = if (dt > 0) (dSent / dt).toLong() else 0
        val rateRecv = if (dt > 0) (dRecv / dt).toLong() else 0

        _tunnelState.update { latest ->
            if (latest is TunnelState.Connected) latest.copy(rateSent = rateSent, rateRecv = rateRecv) else latest
        }
        if (!tunnelStopped && instance === this) updateNotification()
    }

    companion object {
        private const val TAG = "UsqueVpnService"
        private const val NOTIFICATION_ID = 1
        // Upper bound for the blocking stop in lifecycle callbacks. The Go-side
        // stop completes in milliseconds; this only guards against an unexpected
        // stall so onDestroy/onRevoke can't ANR or get SIGKILL'd.
        private const val STOP_TIMEOUT = 5_000L
        const val ACTION_CONNECT = "com.usquebox.CONNECT"
        const val ACTION_DISCONNECT = "com.usquebox.DISCONNECT"

        private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

        @Volatile var instance: UsqueVpnService? = null
            private set
    }
}

sealed class TunnelState {
    data object Stopped : TunnelState()
    data object Connecting : TunnelState()
    data class Connected(
        val bytesSent: Long = 0,
        val bytesRecv: Long = 0,
        val rateSent: Long = 0,
        val rateRecv: Long = 0
    ) : TunnelState()
    data class Reconnecting(
        val bytesSent: Long = 0,
        val bytesRecv: Long = 0
    ) : TunnelState()
    data class Error(@StringRes val message: Int, val detail: String? = null) : TunnelState()
}
