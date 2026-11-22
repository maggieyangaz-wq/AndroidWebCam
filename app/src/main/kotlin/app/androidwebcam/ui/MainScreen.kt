package app.androidwebcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidwebcam.Quality
import app.androidwebcam.R
import app.androidwebcam.StreamSettings
import app.androidwebcam.camera.BackCamera
import app.androidwebcam.camera.CameraKind
import app.androidwebcam.net.LanNetworkMonitor
import app.androidwebcam.service.ErrorKind
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: StreamSettings,
    cameras: List<BackCamera>,
    onSettingsChange: (StreamSettings) -> Unit,
    batteryOk: Boolean,
    onePlusFamily: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnterBlackout: () -> Unit,
) {
    val state by StreamController.state.collectAsStateWithLifecycle()
    val active = state.phase == Phase.RUNNING || state.phase == Phase.STARTING
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state)
            PreviewBox(state)
            if (active) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.btn_stop), fontSize = 17.sp) }
                if (state.phase == Phase.RUNNING && settings.keepAwake) {
                    OutlinedButton(onClick = onEnterBlackout, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_blackout))
                    }
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.btn_start), fontSize = 17.sp)
                }
            }
            ErrorCard(state)
            if (state.phase == Phase.RUNNING) {
                ConnectionCard(state)
                ViewersCard(state)
                ControlsCard(state)
            }
            KeepAliveCard(settings, onSettingsChange, batteryOk, onePlusFamily, onRequestBatteryExemption)
            SettingsCard(settings, cameras, enabled = !active, onSettingsChange)
            InfoCard(stringResource(R.string.section_privacy)) {
                Text(stringResource(R.string.privacy_points), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Header(state: StreamState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        val (label, color) = when (state.phase) {
            Phase.IDLE -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.outline
            Phase.STARTING -> stringResource(R.string.status_starting) to MaterialTheme.colorScheme.tertiary
            Phase.RUNNING -> stringResource(R.string.status_running) to Color(0xFFE53935)
            Phase.ERROR -> stringResource(R.string.status_error) to MaterialTheme.colorScheme.error
        }
        Text(
            text = (if (state.phase == Phase.RUNNING) "● " else "") + label,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (state.phase == Phase.RUNNING && state.streamWidth > 0) {
        Text(
            stringResource(R.string.stream_format, state.streamWidth, state.streamHeight, state.streamFps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun PreviewBox(state: StreamState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (state.phase == Phase.RUNNING) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        setSecure(true)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                StreamController.attachPreview(holder.surface, width, height)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) = StreamController.detachPreview()
                        })
                    }
                },
            )
        } else {
            Text(
                stringResource(R.string.preview_off),
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun ErrorCard(state: StreamState) {
    val message = when {
        state.phase == Phase.ERROR -> when (state.error) {
            ErrorKind.CAMERA_PERMISSION -> stringResource(R.string.error_camera_permission)
            ErrorKind.PORT_IN_USE -> stringResource(R.string.error_port_in_use, state.port)
            ErrorKind.NO_CAMERA -> stringResource(R.string.error_no_camera)
            ErrorKind.CAMERA -> stringResource(R.string.error_camera, state.errorDetail ?: "?")
            ErrorKind.ENCODER -> stringResource(R.string.error_encoder, state.errorDetail ?: "?")
            else -> stringResource(R.string.error_generic, state.errorDetail ?: "?")
        }
        state.phase == Phase.RUNNING && state.cameraBusy -> stringResource(R.string.error_camera_busy)
        else -> return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun kindLabel(kind: LanNetworkMonitor.Kind): String = stringResource(
    when (kind) {
        LanNetworkMonitor.Kind.WIFI -> R.string.kind_wifi
        LanNetworkMonitor.Kind.HOTSPOT -> R.string.kind_hotspot
        LanNetworkMonitor.Kind.USB -> R.string.kind_usb
        LanNetworkMonitor.Kind.ETHERNET -> R.string.kind_ethernet
        LanNetworkMonitor.Kind.OTHER -> R.string.kind_other
    },
)

@Composable
private fun ConnectionCard(state: StreamState) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    InfoCard(stringResource(R.string.section_connect)) {
        Text(stringResource(R.string.connect_steps), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        if (state.endpoints.isEmpty()) {
            Text(stringResource(R.string.connect_no_network), color = MaterialTheme.colorScheme.error)
        }
        state.endpoints.forEachIndexed { index, endpoint ->
            val url = "https://${endpoint.address.hostAddress}:${state.port}"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = index == selected, onClick = { selected = index }, role = Role.RadioButton),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(kindLabel(endpoint.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    SelectionContainer {
                        Text(url, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Android WebCam", url))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_copy)) }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.label_access_code), style = MaterialTheme.typography.labelLarge)
        Text(
            state.accessCode ?: "—",
            fontFamily = FontFamily.Monospace,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.access_code_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        val endpoint = state.endpoints.getOrNull(selected) ?: state.endpoints.firstOrNull()
        val token = state.pairingToken
        if (endpoint != null && token != null) {
            HorizontalDivider()
            Text(stringResource(R.string.label_qr), style = MaterialTheme.typography.labelLarge)
            val link = "https://${endpoint.address.hostAddress}:${state.port}/#pair=$token"
            val qr = remember(link) { QrCode.render(link) }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.label_qr),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(220.dp).background(Color.White),
                )
            }
            if (state.endpoints.size > 1) {
                Text(
                    stringResource(R.string.label_qr_for) + " " + kindLabel(endpoint.kind),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.label_fingerprint), style = MaterialTheme.typography.labelLarge)
        val fp = state.fingerprint ?: "—"
        SelectionContainer {
            Text(fp.chunked(48).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Text(
            stringResource(if (state.hardwareKey) R.string.key_hardware else R.string.key_software),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.hardwareKey) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ViewersCard(state: StreamState) {
    InfoCard(stringResource(R.string.label_viewers, state.viewers.size, state.maxViewers)) {
        if (state.viewers.isEmpty()) {
            Text(stringResource(R.string.no_viewers), style = MaterialTheme.typography.bodyMedium)
        }
        for (viewer in state.viewers) {
            Text(stringResource(R.string.viewer_line, viewer.address, viewer.client, viewer.mode), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        state.lastSnapshot?.let { last ->
            // Single pictures (e.g. taken by an AI tool) are listed too: nothing is taken unnoticed.
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(last.atEpochMs))
            Text(
                stringResource(R.string.snapshot_line, state.snapshotCount, time, last.address, last.client),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = { StreamController.disconnectAllAndRotateCode() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_disconnect_all))
        }
        if (state.alerts.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.label_alerts), style = MaterialTheme.typography.labelLarge)
            for (alert in state.alerts) Text("• $alert", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Settings and hints that keep streaming alive on systems that end background apps (OnePlus etc.). */
@Composable
private fun KeepAliveCard(
    settings: StreamSettings,
    onChange: (StreamSettings) -> Unit,
    batteryOk: Boolean,
    onePlusFamily: Boolean,
    onRequestBatteryExemption: () -> Unit,
) {
    InfoCard(stringResource(R.string.section_keep_alive)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.keep_awake_label), modifier = Modifier.weight(1f))
            Switch(checked = settings.keepAwake, onCheckedChange = { onChange(settings.copy(keepAwake = it)) })
        }
        Text(stringResource(R.string.keep_awake_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        if (!batteryOk) {
            HorizontalDivider()
            Text(stringResource(R.string.battery_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRequestBatteryExemption) { Text(stringResource(R.string.battery_button)) }
        }
        if (onePlusFamily) {
            HorizontalDivider()
            Text(stringResource(R.string.oneplus_tips), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ControlsCard(state: StreamState) {
    if (!state.torchAvailable && state.zoomMax <= state.zoomMin + 0.01f) return
    InfoCard(stringResource(R.string.section_controls)) {
        if (state.torchAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.label_torch), modifier = Modifier.weight(1f))
                Switch(checked = state.torchOn, onCheckedChange = { StreamController.setTorch(it) })
            }
        }
        if (state.zoomMax > state.zoomMin + 0.01f) {
            Text(stringResource(R.string.label_zoom, state.zoom))
            Slider(
                value = state.zoom,
                onValueChange = { StreamController.setZoom(it) },
                valueRange = state.zoomMin..state.zoomMax,
            )
        }
    }
}

@Composable
private fun cameraLabel(camera: BackCamera): String {
    val base = when (camera.kind) {
        CameraKind.MAIN -> stringResource(R.string.camera_main)
        CameraKind.ULTRA_WIDE -> stringResource(R.string.camera_ultrawide)
        CameraKind.TELE -> stringResource(R.string.camera_tele)
        CameraKind.OTHER -> stringResource(R.string.camera_other, camera.id)
    }
    return camera.horizontalFov?.let { stringResource(R.string.camera_fov, base, it) } ?: base
}

@Composable
private fun orientationLabel(value: Int): String = stringResource(
    when (value) {
        0 -> R.string.orientation_0
        90 -> R.string.orientation_90
        180 -> R.string.orientation_180
        270 -> R.string.orientation_270
        else -> R.string.orientation_auto
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(settings: StreamSettings, cameras: List<BackCamera>, enabled: Boolean, onChange: (StreamSettings) -> Unit) {
    InfoCard(stringResource(R.string.section_settings)) {
        if (!enabled) Text(stringResource(R.string.settings_locked_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        if (cameras.size > 1) {
            Text(stringResource(R.string.label_camera), style = MaterialTheme.typography.labelLarge)
            val selectedId = settings.cameraId ?: cameras.first().id
            for (camera in cameras) {
                RadioRow(cameraLabel(camera), selected = camera.id == selectedId, enabled = enabled) {
                    onChange(settings.copy(cameraId = camera.id))
                }
            }
        }

        Text(stringResource(R.string.label_quality), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (q in Quality.entries) {
                FilterChip(selected = settings.quality == q, enabled = enabled, onClick = { onChange(settings.copy(quality = q)) }, label = { Text(q.label) })
            }
        }

        Text(stringResource(R.string.label_fps), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (fps in StreamSettings.FPS_CHOICES) {
                FilterChip(selected = settings.fps == fps, enabled = enabled, onClick = { onChange(settings.copy(fps = fps)) }, label = { Text("$fps fps") })
            }
        }

        Text(stringResource(R.string.label_orientation), style = MaterialTheme.typography.labelLarge)
        for (value in StreamSettings.ORIENTATION_CHOICES) {
            RadioRow(orientationLabel(value), selected = settings.orientation == value, enabled = enabled) {
                onChange(settings.copy(orientation = value))
            }
        }

        var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
        val port = portText.toIntOrNull()
        val portValid = port != null && StreamSettings.isValidPort(port)
        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter { it.isDigit() }.take(5)
                val p = portText.toIntOrNull()
                if (p != null && StreamSettings.isValidPort(p)) onChange(settings.copy(port = p))
            },
            enabled = enabled,
            label = { Text(stringResource(R.string.label_port)) },
            isError = !portValid,
            supportingText = { if (!portValid) Text(stringResource(R.string.port_invalid)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
    }
}
