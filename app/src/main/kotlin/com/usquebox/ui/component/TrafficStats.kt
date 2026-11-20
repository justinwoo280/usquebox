package com.usquebox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usquebox.R
import com.usquebox.ui.formatTrafficBytes

@Composable
fun TrafficStats(
    bytesSent: Long,
    bytesRecv: Long,
    uptime: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                icon = Icons.Default.ArrowUpward,
                label = stringResource(R.string.traffic_sent),
                value = context.formatTrafficBytes(bytesSent)
            )
            StatItem(
                icon = Icons.Default.ArrowDownward,
                label = stringResource(R.string.traffic_recv),
                value = context.formatTrafficBytes(bytesRecv)
            )
            StatItem(
                icon = Icons.Default.Timer,
                label = stringResource(R.string.uptime),
                value = uptime.ifEmpty { stringResource(R.string.uptime_unavailable) }
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
