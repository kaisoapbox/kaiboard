package kaizo.co.WhisperVoiceKeyboard

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.whispercpp.whisper.WhisperContext
import kaizo.co.WhisperVoiceKeyboard.media.decodeShortArray
import kaizo.co.WhisperVoiceKeyboard.media.decodeWaveFile
import kaizo.co.WhisperVoiceKeyboard.recorder.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "VoiceKeyboardInputMethodService"

class VoiceKeyboardInputMethodService : InputMethodService(), LifecycleOwner,
    SavedStateRegistryOwner {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // handle audio manager
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(LOG_TAG, "loss of focus")
                toggleRecord()
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ).setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(focusChangeListener)
            .build()

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onCreateInputView(): View {
        val view = VoiceKeyboardView(this)
        window!!.window!!.decorView.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        return view
    }

    // Lifecycle Methods
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private fun handleLifecycleEvent(event: Lifecycle.Event) =
        lifecycleRegistry.handleLifecycleEvent(event)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryCtrl.performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        modelsPath = File(application.filesDir, "models")
        samplesPath = File(application.filesDir, "samples")
        sharedPref = application.getSharedPreferences(
            getString(R.string.preference_file_key), Context.MODE_PRIVATE
        )

        scope.launch {
            printSystemInfo()
            loadData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking {
            whisperContext?.release()
            whisperContext = null
        }
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // SaveStateRegistry Methods
    private val savedStateRegistryCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryCtrl.savedStateRegistry

    // copy whispercppdemo MainScreenViewModel implementation
    private var whisperContext: WhisperContext? = null
    private var recordedFile: File? = null
    private var recorder: Recorder = Recorder()
    var canTranscribe by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set
    private var modelsPath: File? = null
    private var samplesPath: File? = null
    private var sharedPref: SharedPreferences? = null
    private val maxThreads = Runtime.getRuntime().availableProcessors()
    private var currentJob: Job? = null
    private var trailing: String? = null

    private fun checkBoolPref(resource: Int): Boolean? {
        return sharedPref?.getBoolean(
            application.getString(resource), false
        )
    }

    private fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", WhisperContext.getSystemInfo()))
    }

    private suspend fun loadData() {
        printMessage("Loading data...\n")
        try {
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private fun printMessage(msg: String) {
        Log.d(LOG_TAG, msg)
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath?.mkdirs()
        samplesPath?.mkdirs()
        //application.copyData("models", modelsPath, ::printMessage)
        //samplesPath?.let { application.copyData("samples", it, ::printMessage) }
        printMessage("All data copied to working directory.\n")
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("Loading model...\n")
        val models = application.assets.list("models/")
        if (models != null) {
            whisperContext =
                WhisperContext.createContextFromAsset(application.assets, "models/" + models[0])
            printMessage("Loaded model ${models[0]}.\n")
        } else {
            throw Exception("no models found")
        }

        //val firstModel = modelsPath.listFiles()!!.first()
        //whisperContext = WhisperContext.createContextFromFile(firstModel.absolutePath)
    }

    private suspend fun transcribeFile(file: File) {
        if (!canTranscribe) {
            return
        }
        canTranscribe = false
        try {
            currentJob?.cancel()
            printMessage("Reading wave samples... ")
            val data = decodeWaveFile(file)
            transcribeAudio(data)
            currentInputConnection.finishComposingText()
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun transcribeAudio(data: FloatArray) {
        try {
            printMessage("${data.size / (16000 / 1000)} ms\n")
            val nThreads =
                sharedPref?.getInt(application.getString(R.string.num_threads), maxThreads)
            printMessage("Transcribing data...\n")
            val start = System.currentTimeMillis()
            var text = whisperContext?.transcribeData(data, false, nThreads ?: maxThreads)?.trim()
            printMessage(text.toString())
            // remove special tokens like [MUSIC] or [BLANK_AUDIO]
            // bizarrely, it sometimes also does *music* or (music) so handle these case-s too
            // there's another case with flanking ♪ but it seems slightly different
            text = text?.replace(Regex("""\[[-_a-zA-Z0-9 ]*]"""), "")
            text = text?.replace(Regex("""\*[-_a-zA-Z0-9 ]*\*"""), "")
            text = text?.replace(Regex("""\([-_a-zA-Z0-9 ]*\)"""), "")
            if (checkBoolPref(R.string.casual_mode) == true) {
                printMessage("keeping it casual...\n")
                text = text?.trim()?.lowercase()
                // remove trailing punctuation but not e.g. ! or ?
                if (text?.length!! > 0 && text[text.lastIndex] in charArrayOf('.', ',', ';')) {
                    // < makes the range exclusive instead of inclusive, dropping the last char
                    text = text.slice(0..<text.lastIndex)
                }
            }
            // add text only if non-empty
            // avoids adding just a single space for empty messages
            if (text?.length!! > 0) {
                currentInputConnection.setComposingText(text + (trailing ?: ""), 1)
            }
            val elapsed = System.currentTimeMillis() - start
            printMessage("Done ($elapsed ms): \n$text\n")
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }

        canTranscribe = true
    }

    fun cancelRecord() = scope.launch {
        try {
            if (isRecording) {
                if (checkBoolPref(R.string.pause_media) == true) {
                    focusRequest.let { audioManager.abandonAudioFocusRequest(it) }
                }
                currentJob?.cancel()
                currentInputConnection.finishComposingText()
                recorder.stopRecording()
                isRecording = false
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    private val onError = { e: Exception ->
        scope.launch {
            withContext(Dispatchers.Main) {
                printMessage("${e.localizedMessage}\n")
                currentJob?.cancel()
                isRecording = false
            }
        }
    }

    private val transcriptionCallback = { shortData: ShortArray ->
        if (currentJob == null) {
            currentJob = scope.launch {
                printMessage(shortData.size.toString())
                // convert ShortArray to FloatArray for transcription
                val floatData = decodeShortArray(shortData, 1)
                transcribeAudio(floatData)
                currentJob = null
            }
        }
    }

    fun toggleRecord() = scope.launch {
        try {
            if (isRecording) {
                if (checkBoolPref(R.string.pause_media) == true) {
                    focusRequest.let { audioManager.abandonAudioFocusRequest(it) }
                }
                currentJob?.cancel()
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeFile(it) }
            } else {
                if (checkBoolPref(R.string.pause_media) == true) {
                    // Request focus
                    val result = audioManager.requestAudioFocus(focusRequest)
                    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        // Failed to gain audio focus
                        Log.w(LOG_TAG, "Failed to gain audio focus")
                        return@launch
                    }
                }
                trailing = null
                val file = getTempFileForRecording()
                recorder.startRecording(file, onError, transcriptionCallback)
                isRecording = true
                recordedFile = file
                // if not preceded by empty space, commit empty space
                val charBefore = currentInputConnection.getTextBeforeCursor(1, 0)
                if (charBefore?.isNotEmpty()?.and(charBefore != " ") == true) {
                    currentInputConnection.commitText(" ", 1)
                }
                // if not followed by empty space, add trailing space
                val charAfter = currentInputConnection.getTextAfterCursor(1, 0)
                if (charAfter != " ") {
                    trailing = " "
                }

            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    // for del and newline buttons
    fun sendKeyPress(key: Int) {
        currentInputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        currentInputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    fun shouldRenderSwitcher(): Boolean {
        return shouldOfferSwitchingToNextInputMethod()
    }

    fun switchKeyboard() {
        switchToNextInputMethod(false)
    }
}

private suspend fun Context.copyData(
    assetDirName: String, destDir: File, printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Log.v(LOG_TAG, "Processing $assetPath...")
        val destination = File(destDir, name)
        Log.v(LOG_TAG, "Copying $assetPath to $destination...")
        printMessage("Copying $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.v(LOG_TAG, "Copied $assetPath to $destination")
    }
}