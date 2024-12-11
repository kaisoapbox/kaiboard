package kaizo.co.WhisperVoiceKeyboard

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
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
import kaizo.co.WhisperVoiceKeyboard.media.decodeWaveFile
import kaizo.co.WhisperVoiceKeyboard.recorder.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private suspend fun transcribeAudio(file: File) {
        if (!canTranscribe) {
            return
        }
        canTranscribe = false

        try {
            printMessage("Reading wave samples... ")
            val data = decodeWaveFile(file)
            printMessage("${data.size / (16000 / 1000)} ms\n")
            val nThreads =
                sharedPref?.getInt(application.getString(R.string.num_threads), maxThreads)
            printMessage("Transcribing data...\n")
            val start = System.currentTimeMillis()
            var text = whisperContext?.transcribeData(data, false, nThreads ?: maxThreads)?.trim()
            printMessage(text.toString())
            // remove special tokens like [MUSIC] or [BLANK_AUDIO]
            // bizarrely, it sometimes also does *music* or (music) so handle these cases too
            // there's another case with flanking ♪ but it seems slightly different
            text = text?.replace(Regex("""\[[-_a-zA-Z0-9 ]*]"""), "")
            text = text?.replace(Regex("""\*[-_a-zA-Z0-9 ]*\*"""), "")
            text = text?.replace(Regex("""\([-_a-zA-Z0-9 ]*\)"""), "")
            if (sharedPref?.getBoolean(
                    application.getString(R.string.casual_mode), false
                ) == true
            ) {
                printMessage("keeping it casual...\n")
                text = text?.trim()?.lowercase()
                // remove trailing punctuation but not e.g. ! or ?
                if (text?.length!! > 0 && text[text.lastIndex] in charArrayOf('.', ',', ';')) {
                    // < makes the range exclusive instead of inclusive, dropping the last char
                    text = text.slice(0..<text.lastIndex)
                }
            }
            // commit text only if non-empty
            // avoids committing just a single space for empty messages
            if (text?.length!! > 0) {
                currentInputConnection.commitText("$text ", 1)
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
                recorder.stopRecording()
                isRecording = false
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    fun toggleRecord() = scope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeAudio(it) }
            } else {
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                        }
                    }
                }
                isRecording = true
                recordedFile = file
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

    fun renderGlobe(): Boolean {
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