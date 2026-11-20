package com.usquebox.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.usquebox.R
import com.usquebox.data.ConfigException
import com.usquebox.data.ConfigFields
import com.usquebox.data.ConfigIssue
import com.usquebox.data.NoiseFields
import com.usquebox.data.UsqueConfig
import com.usquebox.ui.configIssueMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentConfig: String?,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateToApps: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val initial = remember(currentConfig) { currentConfig?.takeIf { it.isNotBlank() }?.let { runCatching { UsqueConfig.parse(it) } } }
    var document by remember(currentConfig) { mutableStateOf(initial?.getOrNull()) }
    var fields by remember(currentConfig) { mutableStateOf(document?.fields ?: ConfigFields()) }
    var source by remember(currentConfig) { mutableStateOf(currentConfig.orEmpty()) }
    var loadedSource by remember(currentConfig) { mutableStateOf(currentConfig.orEmpty()) }
    var importIssue by remember(currentConfig) { mutableStateOf((initial?.exceptionOrNull() as? ConfigException)?.issue) }
    var saveIssue by remember(currentConfig) { mutableStateOf<ConfigIssue?>(null) }
    var showJson by remember { mutableStateOf(document == null) }
    var showEndpoints by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("") }
    var jwt by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var registerFailed by remember { mutableStateOf(false) }

    fun load(text: String) {
        source = text
        try {
            val parsed = UsqueConfig.parse(text)
            document = parsed
            fields = parsed.fields
            loadedSource = text
            importIssue = null
            saveIssue = null
        } catch (e: ConfigException) {
            document = null
            importIssue = e.issue
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(enabled = !registering, onClick = {
                    source = ""
                    loadedSource = ""
                    document = null
                    fields = ConfigFields()
                    importIssue = null
                    saveIssue = null
                    registerFailed = false
                    showJson = true
                }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.clear_draft))
                }
            }
        )
    }) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsHeading(R.string.account)
            SettingsCard {
                SettingField(R.string.device_name, deviceName, { deviceName = it })
                OutlinedTextField(
                    value = jwt,
                    onValueChange = { jwt = it },
                    label = { Text(stringResource(R.string.device_jwt)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !registering,
                    onClick = {
                        registering = true
                        registerFailed = false
                        val token = jwt.trim()
                        val name = deviceName.trim()
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { Mobile.registerAccount(token, name) }
                                if (result.startsWith("error:")) {
                                    registerFailed = true
                                } else {
                                    load(result)
                                    jwt = ""
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                registerFailed = true
                            } finally {
                                registering = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (registering) R.string.registering else R.string.register))
                }
                if (registerFailed) ErrorText(R.string.error_registration)
            }

            SettingsCard {
                OutlinedButton(onClick = { showJson = !showJson }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (showJson) R.string.hide_config_json else R.string.import_config))
                }
                if (showJson) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it; importIssue = null },
                        label = { Text(stringResource(R.string.config_json)) },
                        placeholder = { Text(stringResource(R.string.paste_json)) },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    HelpText(R.string.config_contains_credentials)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !registering, onClick = {
                            val text = clipboard.getText()?.text
                            if (!text.isNullOrBlank()) load(text)
                        }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.paste))
                        }
                        OutlinedButton(enabled = source.isNotBlank() && !registering, onClick = { load(source) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.parse_config))
                        }
                    }
                }
                if (source != loadedSource && importIssue == null) HelpText(R.string.parse_before_save)
                importIssue?.let { ErrorText(it.configIssueMessage()) }
            }

            if (document != null) {
                SettingsHeading(R.string.tun_interface)
                SettingsCard {
                    HelpText(R.string.tun_family_help)
                    SettingSwitch(R.string.tun_enable_ipv4, fields.ipv4) { fields = fields.copy(ipv4 = it) }
                    SettingSwitch(R.string.tun_enable_ipv6, fields.ipv6) { fields = fields.copy(ipv6 = it) }
                    SettingField(R.string.tun_mtu, fields.mtu, { fields = fields.copy(mtu = it) }, R.string.tun_mtu_help, number = true)
                    SettingField(R.string.tun_dns, fields.dns, { fields = fields.copy(dns = it) }, R.string.tun_dns_help, singleLine = false)
                }

                SettingsHeading(R.string.connection)
                SettingsCard {
                    SettingField(R.string.connection_port, fields.port, { fields = fields.copy(port = it) }, number = true)
                    SettingField(R.string.connection_sni, fields.sni, { fields = fields.copy(sni = it) }, R.string.connection_sni_help)
                    SettingSwitch(R.string.connection_ipv6, fields.useIPv6) { fields = fields.copy(useIPv6 = it) }
                    SettingSwitch(R.string.connection_http2, fields.useHttp2) { fields = fields.copy(useHttp2 = it) }
                    OutlinedButton(onClick = { showEndpoints = !showEndpoints }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (showEndpoints) R.string.hide_endpoints else R.string.show_endpoints))
                    }
                    if (showEndpoints) {
                        SettingField(R.string.endpoint_h3_ipv4, fields.endpointV4, { fields = fields.copy(endpointV4 = it) })
                        SettingField(R.string.endpoint_h3_ipv6, fields.endpointV6, { fields = fields.copy(endpointV6 = it) })
                        SettingField(R.string.endpoint_h2_ipv4, fields.endpointH2V4, { fields = fields.copy(endpointH2V4 = it) })
                        SettingField(R.string.endpoint_h2_ipv6, fields.endpointH2V6, { fields = fields.copy(endpointH2V6 = it) })
                    }
                }

                SettingsHeading(R.string.protocol)
                SettingsCard {
                    SettingMenu(R.string.congestion, fields.congestion, congestionChoices) { fields = fields.copy(congestion = it) }
                    when (fields.congestion) {
                        "bbr" -> SettingMenu(R.string.bbr_profile, fields.bbrProfile, bbrChoices) { fields = fields.copy(bbrProfile = it) }
                        "brutal" -> SettingField(R.string.brutal_rate, fields.brutalBps, { fields = fields.copy(brutalBps = it) }, number = true)
                    }
                }

                SettingsHeading(R.string.noise_settings)
                NoiseCard(R.string.connection_noise, fields.noise) { fields = fields.copy(noise = it) }
                NoiseCard(R.string.connection_pre_noise, fields.preNoise) { fields = fields.copy(preNoise = it) }

                SettingsHeading(R.string.advanced)
                SettingsCard {
                    SettingField(R.string.keepalive, fields.keepalive, { fields = fields.copy(keepalive = it) }, R.string.duration_help)
                    SettingField(R.string.reconnect_delay, fields.reconnect, { fields = fields.copy(reconnect = it) }, R.string.duration_help)
                    SettingField(R.string.initial_packet_size, fields.initialPacketSize, { fields = fields.copy(initialPacketSize = it) }, R.string.initial_packet_size_help, number = true)
                    SettingField(R.string.pmtu_target, fields.pmtuTarget, { fields = fields.copy(pmtuTarget = it) }, R.string.pmtu_target_help)
                    SettingSwitch(R.string.insecure, fields.insecure) { fields = fields.copy(insecure = it) }
                    HelpText(R.string.insecure_warning)
                }
            }

            SettingsHeading(R.string.per_app_proxy)
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToApps)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_rules), style = MaterialTheme.typography.bodyLarge)
                        HelpText(R.string.app_rules_help)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }

            Button(
                enabled = !registering && document != null && source == loadedSource,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    try {
                        document?.save(fields)?.let(onSave)
                    } catch (e: ConfigException) {
                        saveIssue = e.issue
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
            if (document != null) saveIssue?.let { ErrorText(it.configIssueMessage()) }
        }
    }
}

private val congestionChoices = listOf("reno" to R.string.cc_reno, "bbr" to R.string.cc_bbr, "brutal" to R.string.cc_brutal)
private val bbrChoices = listOf("conservative" to R.string.bbr_conservative, "standard" to R.string.bbr_standard, "aggressive" to R.string.bbr_aggressive)

@Composable
private fun SettingsHeading(@StringRes title: Int) {
    Text(stringResource(title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun HelpText(@StringRes message: Int) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(@StringRes message: Int) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun SettingField(
    @StringRes label: Int,
    value: String,
    onChange: (String) -> Unit,
    @StringRes help: Int? = null,
    singleLine: Boolean = true,
    number: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = if (help != null) { { Text(stringResource(help)) } } else null,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingSwitch(@StringRes label: Int, on: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(on, onCheckedChange = changed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingMenu(@StringRes label: Int, value: String, choices: List<Pair<String, Int>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = choices.firstOrNull { it.first == value }?.second
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (display != null) stringResource(display) else value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, res) ->
                DropdownMenuItem(text = { Text(stringResource(res)) }, onClick = { onChange(key); expanded = false })
            }
        }
    }
}

@Composable
private fun NoiseCard(@StringRes title: Int, fields: NoiseFields, onChange: (NoiseFields) -> Unit) {
    SettingsCard {
        SettingSwitch(title, fields.enabled) { onChange(fields.copy(enabled = it)) }
        if (fields.enabled) {
            SettingField(R.string.noise_count, fields.count, { onChange(fields.copy(count = it)) }, number = true)
            SettingField(R.string.noise_min_size, fields.minSize, { onChange(fields.copy(minSize = it)) }, number = true)
            SettingField(R.string.noise_max_size, fields.maxSize, { onChange(fields.copy(maxSize = it)) }, number = true)
            SettingField(R.string.noise_delay_min, fields.delayMin, { onChange(fields.copy(delayMin = it)) }, R.string.duration_help)
            SettingField(R.string.noise_delay_max, fields.delayMax, { onChange(fields.copy(delayMax = it)) }, R.string.duration_help)
        }
    }
}
