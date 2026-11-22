package app.androidwebcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.androidwebcam.R
import kotlinx.coroutines.delay

/**
 * Full-screen black standby while streaming (the activity also drops the backlight to its
 * lowest level). No preview is drawn. A dim hint shows for a few seconds after a single tap;
 * a double tap returns to the normal screen.
 */
@Composable
fun BlackoutScreen(onExit: () -> Unit) {
    var hintRequests by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(hintRequests) {
        hintVisible = true
        delay(5_000)
        hintVisible = false
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onExit() },
                    onTap = { hintRequests++ },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hintVisible) {
            Text(
                stringResource(R.string.blackout_hint),
                color = Color(0xFF5A5A5A),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
