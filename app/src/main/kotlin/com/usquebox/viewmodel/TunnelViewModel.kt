package com.usquebox.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usquebox.data.ConfigStore
import com.usquebox.data.ProxyMode
import com.usquebox.service.TunnelState
import com.usquebox.service.UsqueVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TunnelViewModel(app: Application) : AndroidViewModel(app) {
    private val configStore = ConfigStore(app)

    val tunnelState: StateFlow<TunnelState> = UsqueVpnService.tunnelState
        .stateIn(viewModelScope, SharingStarted.Eagerly, TunnelState.Stopped)

    val hasConfig: Boolean get() = configStore.hasConfig()

    fun getConfigJson(): String? = configStore.configJson

    fun saveConfig(json: String) {
        configStore.configJson = json
    }

    fun getProxyMode(): ProxyMode = configStore.proxyMode
    fun setProxyMode(mode: ProxyMode) { configStore.proxyMode = mode }
    fun getSelectedApps(): Set<String> = configStore.selectedApps
    fun setSelectedApps(apps: Set<String>) { configStore.selectedApps = apps }

    fun connect(vpnPermissionLauncher: ActivityResultLauncher<Intent>) {
        val vpnIntent = VpnService.prepare(getApplication())
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startService()
        }
    }

    fun onVpnPermissionGranted() {
        startService()
    }

    fun disconnect() {
        val intent = Intent(getApplication(), UsqueVpnService::class.java).apply {
            action = UsqueVpnService.ACTION_DISCONNECT
        }
        getApplication<Application>().startService(intent)
    }

    private fun startService() {
        val intent = Intent(getApplication(), UsqueVpnService::class.java).apply {
            action = UsqueVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }
}
