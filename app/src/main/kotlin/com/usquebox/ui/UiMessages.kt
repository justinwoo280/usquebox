package com.usquebox.ui

import android.content.Context
import androidx.annotation.StringRes
import com.usquebox.R
import com.usquebox.data.ConfigIssue

@StringRes
internal fun ConfigIssue.configIssueMessage(): Int = when (this) {
    ConfigIssue.JSON -> R.string.error_config_json
    ConfigIssue.ACCOUNT -> R.string.error_config_account
    ConfigIssue.INBOUND -> R.string.error_config_inbound
    ConfigIssue.PORT -> R.string.error_config_port
    ConfigIssue.MTU -> R.string.error_config_mtu
    ConfigIssue.DNS -> R.string.error_config_dns
    ConfigIssue.INITIAL_PACKET_SIZE -> R.string.error_config_initial_size
    ConfigIssue.PMTU_TARGET -> R.string.error_config_pmtu_target
    ConfigIssue.ENDPOINT -> R.string.error_config_endpoint
    ConfigIssue.IP_FAMILY -> R.string.error_config_ip_family
    ConfigIssue.CONGESTION -> R.string.error_config_congestion
    ConfigIssue.BRUTAL_RATE -> R.string.error_config_brutal_rate
    ConfigIssue.NOISE -> R.string.error_config_noise
    ConfigIssue.DURATION -> R.string.error_config_duration
}

internal fun Context.formatTrafficBytes(bytes: Long): String = when {
    bytes < 1024 -> getString(R.string.bytes_format, bytes)
    bytes < 1024 * 1024 -> getString(R.string.kib_format, bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> getString(R.string.mib_format, bytes / (1024.0 * 1024))
    else -> getString(R.string.gib_format, bytes / (1024.0 * 1024 * 1024))
}
