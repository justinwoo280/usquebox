package com.usquebox.ui.screen

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usquebox.R
import com.usquebox.service.TunnelState
import com.usquebox.ui.component.ConnectButton
import com.usquebox.ui.component.TrafficStats
import com.usquebox.ui.theme.ConnectedGreen
import com.usquebox.ui.theme.ErrorRed
import com.usquebox.ui.theme.ReconnectingAmber
import mobile.Mobile
import org.json.JSONObject
import kotlin.time.Duration

@Composable
fun HomeScreen(
    state: TunnelState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state is TunnelState.Connected || state is TunnelState.Connecting || state is TunnelState.Reconnecting

    val statusColor by animateColorAsState(
        targetValue = when (state) {
            is TunnelState.Connected -> ConnectedGreen
            is TunnelState.Connecting, is TunnelState.Reconnecting -> ReconnectingAmber
            is TunnelState.Error -> ErrorRed
            is TunnelState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val statusText = when (state) {
        is TunnelState.Stopped -> stringResource(R.string.state_stopped)
        is TunnelState.Connecting -> stringResource(R.string.state_connecting)
        is TunnelState.Connected -> stringResource(R.string.state_connected)
        is TunnelState.Reconnecting -> stringResource(R.string.state_reconnecting)
        is TunnelState.Error -> stringResource(state.message)
    }

    val (sent, recv) = when (state) {
        is TunnelState.Connected -> state.bytesSent to state.bytesRecv
        is TunnelState.Reconnecting -> state.bytesSent to state.bytesRecv
        else -> 0L to 0L
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(48.dp))

        ConnectButton(
            state = state,
            onToggle = { if (isActive) onDisconnect() else onConnect() }
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = statusColor
        )

        Spacer(Modifier.height(32.dp))

        if (isActive) {
            TrafficStats(
                bytesSent = sent,
                bytesRecv = recv,
                uptime = runCatching { JSONObject(Mobile.getStatus()).optString("uptime") }
                    .getOrNull()?.let { Duration.parseOrNull(it) }?.inWholeSeconds
                    ?.let { DateUtils.formatElapsedTime(it) }.orEmpty()
            )
        }

        if (state is TunnelState.Error && !state.detail.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
    }
}
