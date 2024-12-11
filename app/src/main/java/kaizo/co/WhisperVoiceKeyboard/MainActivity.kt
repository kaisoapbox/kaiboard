package kaizo.co.WhisperVoiceKeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kaizo.co.WhisperVoiceKeyboard.ui.main.MainScreen
import kaizo.co.WhisperVoiceKeyboard.ui.main.MainScreenViewModel
import kaizo.co.WhisperVoiceKeyboard.ui.theme.KaiboardTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KaiboardTheme {
                MainScreen(viewModel)
            }
        }
    }
}