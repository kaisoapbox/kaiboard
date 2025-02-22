package kaizo.co.WhisperVoiceKeyboard

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kaizo.co.WhisperVoiceKeyboard.ui.theme.KaiboardTheme
import kotlinx.coroutines.flow.collectLatest


@SuppressLint("ViewConstructor")
class VoiceKeyboardView(private val service: VoiceKeyboardInputMethodService) :
    AbstractComposeView(service) {
    private val padding = 2.dp
    private val minSize = 12.dp
    private val contentPad = 12.dp
    private val shape = RoundedCornerShape(size = 8.dp)

    @Composable
    override fun Content() {
        KaiboardTheme {
            Row {
                RecordButton(
                    enabled = service.canTranscribe,
                    isRecording = service.isRecording,
                    onClick = service::toggleRecord,
                    modifier = Modifier
                        .weight(1f, true)
                        .padding(padding),
                    shape = shape,
                )
                if (service.isRecording) {
                    CancelButton(
                        onClick = service::cancelRecord,
                        modifier = Modifier
                            .padding(padding)
                            .defaultMinSize(minWidth = minSize),
                        shape = shape,
                        contentPadding = PaddingValues(horizontal = contentPad),
                    )
                }
                DeleteButton(
                    onClick = { service.sendKeyPress(KeyEvent.KEYCODE_DEL) },
                    modifier = Modifier
                        .padding(padding)
                        .defaultMinSize(minWidth = minSize),
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = contentPad),
                )
                Button(
                    onClick = {
                        service.sendKeyPress(KeyEvent.KEYCODE_ENTER)
                    },
                    modifier = Modifier
                        .padding(padding)
                        .defaultMinSize(minWidth = minSize),
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = contentPad),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardReturn,
                        stringResource(R.string.return_button)
                    )
                }
                if (service.shouldRenderSwitcher()) {
                    Button(
                        onClick = { service.switchKeyboard() },
                        modifier = Modifier
                            .padding(padding)
                            .defaultMinSize(minWidth = minSize),
                        contentPadding = PaddingValues(horizontal = contentPad),
                        shape = shape
                    ) {
                        Icon(
                            painterResource(R.drawable.keyboard_previous_language),
                            stringResource(R.string.switch_keyboard_button)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CancelButton(
    onClick: () -> Unit, modifier: Modifier, shape: Shape, contentPadding: PaddingValues,
) {
    Button(onClick = onClick, modifier = modifier, shape = shape, contentPadding = contentPadding) {
        Icon(Icons.Outlined.Clear, stringResource(R.string.cancel_recording))
    }
}

@Composable
private fun DeleteButton(
    onClick: () -> Unit, modifier: Modifier, shape: Shape, contentPadding: PaddingValues
) {
    var mHandler: Handler? = null
    val mAction: Runnable = object : Runnable {
        override fun run() {
            onClick()
            mHandler!!.postDelayed(this, 50)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (mHandler != null) return@collectLatest
                    mHandler = Handler(Looper.getMainLooper())
                    mHandler!!.post(mAction)
                }

                is PressInteraction.Release -> {
                    if (mHandler == null) return@collectLatest
                    mHandler!!.removeCallbacks(mAction)
                    mHandler = null
                }
            }
        }
    }
    Button(
        onClick = {},
        interactionSource = interactionSource,
        modifier = modifier,
        shape = shape,
        contentPadding = contentPadding
    ) {
        Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.delete_button))
    }
}

@Composable
private fun RecordButton(
    enabled: Boolean, isRecording: Boolean, onClick: () -> Unit, modifier: Modifier, shape: Shape
) {
    // handle showing Record on the first render and not Transcribing...
    var firstRender by remember { mutableStateOf(true) }
    // handle stopwatch timer
    var seconds by remember { mutableIntStateOf(0) }
    var handler: Handler? by remember { mutableStateOf(null) }
    var start by remember { mutableLongStateOf(0) }

    val runnable: Runnable = object : Runnable {
        override fun run() {
            seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
            handler?.postDelayed(this, 1_000)
        }
    }

    Button(onClick = {
        if (!isRecording) {
            start = System.currentTimeMillis()
            seconds = 0
            handler = Handler(Looper.getMainLooper())
            handler!!.postDelayed(runnable, 1_000)
        } else {
            handler!!.removeCallbacks(runnable)
            handler = null
        }
        onClick()
    }, enabled = enabled, modifier = modifier, shape = shape) {
        if (!enabled && firstRender) {
            Icon(
                Icons.Outlined.KeyboardVoice, stringResource(R.string.start_recording)
            )
        } else if (!enabled) {
            firstRender = false
            Text(stringResource(R.string.transcribing), fontSize = 16.sp)
        } else if (isRecording) {
            firstRender = false
            Icon(Icons.Outlined.Check, stringResource(R.string.stop_recording))
            Text(
                " %d".format(seconds / 60) + ":%02d".format(
                    seconds % 60
                ), fontSize = 16.sp
            )
        } else {
            Icon(
                Icons.Outlined.KeyboardVoice, stringResource(R.string.start_recording)
            )
        }
    }
}
