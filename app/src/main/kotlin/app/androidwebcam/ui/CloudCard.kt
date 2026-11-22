package app.androidwebcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androidwebcam.R
import app.androidwebcam.StreamSettings
import app.androidwebcam.core.cloud.RelayClient
import app.androidwebcam.security.CloudAgentStore
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamState

/**
 * Cloud AI access (off by default): the relay address, the connection state, the phone's key pin
 * (needed to set up the relay) and the cloud agents, which can only be created on the phone.
 */
@Composable
internal fun CloudCard(
    settings: StreamSettings,
    editable: Boolean,
    state: StreamState,
    agents: List<CloudAgentStore.Info>,
    pin: String?,
    onSettingsChange: (StreamSettings) -> Unit,
    onCreateAgent: (String) -> String,
    onRevokeAgent: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var confirmEnable by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf<String?>(null) }

    InfoCard(stringResource(R.string.section_cloud)) {
        Text(stringResource(R.string.cloud_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cloud_enable), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.cloudEnabled,
                enabled = editable,
                onCheckedChange = { on -> if (on) confirmEnable = true else onSettingsChange(settings.copy(cloudEnabled = false)) },
            )
        }
        if (settings.cloudEnabled) {

            var relayText by remember(settings.cloudRelay) { mutableStateOf(settings.cloudRelay) }
            val relayValid = StreamSettings.isValidRelay(relayText)
            OutlinedTextField(
                value = relayText,
                onValueChange = { text ->
                    relayText = text.trim()
                    if (StreamSettings.isValidRelay(relayText)) onSettingsChange(settings.copy(cloudRelay = relayText.trimEnd('/')))
                },
                enabled = editable,
                label = { Text(stringResource(R.string.cloud_relay_label)) },
                isError = relayText.isNotEmpty() && !relayValid,
                supportingText = {
                    Text(stringResource(if (relayText.isNotEmpty() && !relayValid) R.string.cloud_relay_invalid else R.string.cloud_relay_hint))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloud_wifi_only), modifier = Modifier.weight(1f))
                Switch(checked = settings.cloudWifiOnly, enabled = editable, onCheckedChange = { onSettingsChange(settings.copy(cloudWifiOnly = it)) })
            }
            Text(cloudStatus(state, relayValid), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Text(stringResource(R.string.cloud_pin_label), style = MaterialTheme.typography.labelLarge)
            if (pin != null) {
                SelectionContainer { Text(pin, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton(onClick = { onCopy(pin) }) { Text(stringResource(R.string.action_copy_text)) }
            } else {
                Text(stringResource(R.string.cloud_pin_unknown), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text(stringResource(R.string.cloud_agents_label), style = MaterialTheme.typography.labelLarge)
            if (agents.isEmpty()) Text(stringResource(R.string.cloud_agents_none), style = MaterialTheme.typography.bodySmall)
            val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            for (agent in agents) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val used = if (agent.lastUsedAtMs > 0) format.format(java.util.Date(agent.lastUsedAtMs)) else stringResource(R.string.ai_key_never_used)
                    Text(
                        stringResource(R.string.cloud_agent_line, agent.name, format.format(java.util.Date(agent.createdAtMs)), used),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRevokeAgent(agent.id) }) { Text(stringResource(R.string.action_revoke)) }
                }
            }
            OutlinedButton(onClick = { naming = true }, enabled = StreamSettings.isValidRelay(settings.cloudRelay), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloud_new_agent))
            }
        }
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.cloud_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnable = false
                    onSettingsChange(settings.copy(cloudEnabled = true))
                }) { Text(stringResource(R.string.cloud_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.cloud_new_agent)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.cloud_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    instructions = onCreateAgent(name)
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    instructions?.let { text ->
        AlertDialog(
            onDismissRequest = { instructions = null },
            title = { Text(stringResource(R.string.cloud_created_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cloud_created_note), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { onCopy(text) }) { Text(stringResource(R.string.action_copy_text)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onShare(text) }) { Text(stringResource(R.string.action_share)) }
                    TextButton(onClick = { instructions = null }) { Text(stringResource(R.string.action_done)) }
                }
            },
        )
    }
}

@Composable
private fun cloudStatus(state: StreamState, relayValid: Boolean): String = when {
    state.phase != Phase.RUNNING -> stringResource(R.string.cloud_status_idle)
    !relayValid -> stringResource(R.string.cloud_status_invalid)
    else -> when (state.cloudStatus) {
        RelayClient.Status.CONNECTED -> stringResource(R.string.cloud_status_connected)
        RelayClient.Status.WAITING_FOR_NETWORK -> stringResource(R.string.cloud_status_waiting)
        RelayClient.Status.REJECTED -> stringResource(R.string.cloud_status_rejected)
        RelayClient.Status.RETRYING -> stringResource(R.string.cloud_status_retrying, state.cloudDetail ?: "")
        RelayClient.Status.CONNECTING, RelayClient.Status.STOPPED, null -> stringResource(R.string.cloud_status_connecting)
    }
}
