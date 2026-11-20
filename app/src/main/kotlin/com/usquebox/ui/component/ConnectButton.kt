package com.usquebox.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usquebox.R
import com.usquebox.service.TunnelState
import com.usquebox.ui.theme.ConnectedGreen
import com.usquebox.ui.theme.ErrorRed
import com.usquebox.ui.theme.ReconnectingAmber

@Composable
fun ConnectButton(
    state: TunnelState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state is TunnelState.Connected || state is TunnelState.Connecting || state is TunnelState.Reconnecting

    val containerColor by animateColorAsState(
        targetValue = when (state) {
            is TunnelState.Connected -> ConnectedGreen
            is TunnelState.Connecting, is TunnelState.Reconnecting -> ReconnectingAmber
            is TunnelState.Error -> ErrorRed
            is TunnelState.Stopped -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (state is TunnelState.Connecting || state is TunnelState.Reconnecting) 1.05f else 1f,
        animationSpec = tween(300),
        label = "buttonScale"
    )

    FilledIconButton(
        onClick = onToggle,
        modifier = modifier
            .size(120.dp)
            .scale(scale),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = stringResource(if (isActive) R.string.disconnect else R.string.connect),
            modifier = Modifier.size(56.dp)
        )
    }
}
