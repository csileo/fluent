package app.fluent

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * window.FluentNative: speech recognition, speech synthesis and Bluetooth call-mode routing for the web page.
 * Results go back to the page as window.__fluentNative.onEvent({...}).
 * JavascriptInterface methods run on a binder thread, so everything is posted to the main thread.
 */
class NativeBridge(private val activity: MainActivity, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(AudioManager::class.java)

    private var recognizer: SpeechRecognizer? = null
    private var currentRec = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTts = 0

    private var active = false
    private var callMode = false
    private var routedToBluetooth = false
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(activity.applicationContext) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                emit(JSONObject().put("type", "voices"))
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = ttsFinished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = ttsFinished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = ttsFinished(utteranceId)
        })
        // The car connecting or disconnecting mid-conversation moves the sound with it.
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (active && callMode) applyRoute()
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (active && callMode) applyRoute()
            }
        }, main)
    }

    // ---------- speech recognition ----------

    @JavascriptInterface
    fun startListening(id: Int, lang: String, maxAlternatives: Int) = main.post {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emitSr(id, "error", "not-allowed"); emitSr(id, "end"); return@post
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            emitSr(id, "error", "service-not-allowed"); emitSr(id, "end"); return@post
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        currentRec = id
        r.setRecognitionListener(listenerFor(id))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            // Learners pause to look for words: ask the recognizer not to cut them off too soon.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        r.startListening(intent)
    }

    @JavascriptInterface
    fun stopListening(id: Int) = main.post { if (id == currentRec) recognizer?.stopListening() }

    @JavascriptInterface
    fun cancelListening(id: Int) = main.post {
        if (id != currentRec) return@post
        recognizer?.cancel()
        currentRec = 0
        emitSr(id, "end")
    }

    private fun listenerFor(id: Int) = object : RecognitionListener {
        private fun current() = id == currentRec

        override fun onPartialResults(bundle: Bundle) {
            if (!current()) return
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            if (!texts.isNullOrEmpty()) emitSr(id, "partial", texts = texts.take(1))
        }

        override fun onResults(bundle: Bundle) {
            if (!current()) return
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            if (!texts.isNullOrEmpty()) emitSr(id, "final", texts = texts)
            currentRec = 0
            emitSr(id, "end")
        }

        override fun onError(error: Int) {
            if (!current()) return
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy(); recognizer = null
            }
            emitSr(id, "error", webError(error))
            currentRec = 0
            emitSr(id, "end")
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // Same error names as the Web Speech API, which the page already handles.
    private fun webError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "network"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-not-supported"
        else -> "aborted"  // busy, client, audio: transient, the page simply listens again
    }

    // ---------- speech synthesis ----------

    @JavascriptInterface
    fun getVoices(): String {
        val list = JSONArray()
        val voices = try { tts?.voices } catch (e: Exception) { null } ?: return list.toString()
        for (v in voices.sortedBy { it.name }) {
            if (v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) continue
            list.put(JSONObject().put("name", v.name).put("lang", v.locale.toLanguageTag()))
        }
        return list.toString()
    }

    @JavascriptInterface
    fun speak(id: Int, text: String, lang: String, voiceName: String, rate: Float) = main.post {
        val engine = tts
        if (engine == null || !ttsReady) { emitTts(id); return@post }
        val voice = engine.voices?.firstOrNull { it.name == voiceName }
        if (voice != null) engine.voice = voice else engine.language = Locale.forLanguageTag(lang)
        engine.setSpeechRate(rate)
        // In call mode the voice goes through the call channel, i.e. the car speakers.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (routedToBluetooth) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        pendingTts = id
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "u$id")
    }

    @JavascriptInterface
    fun stopSpeaking() = main.post {
        val id = pendingTts
        pendingTts = 0
        tts?.stop()
        if (id != 0) emitTts(id)
    }

    private fun ttsFinished(utteranceId: String) {
        val id = utteranceId.removePrefix("u").toIntOrNull() ?: return
        main.post {
            if (pendingTts == id) pendingTts = 0
            emitTts(id)
        }
    }

    // ---------- conversation state and audio routing ----------

    @JavascriptInterface
    fun setConversationActive(on: Boolean, useCallMode: Boolean) = main.post {
        if (on == active && useCallMode == callMode) return@post
        active = on
        callMode = useCallMode
        activity.keepScreenOn(on)
        val service = Intent(activity, ConversationService::class.java)
        if (on) {
            requestFocus()
            applyRoute()
            service.putExtra(ConversationService.EXTRA_TEXT, if (routedToBluetooth) "Conversation (Bluetooth)" else "Conversation")
            try { activity.startForegroundService(service) } catch (e: Exception) {}
        } else {
            clearRoute()
            abandonFocus()
            activity.stopService(service)
        }
    }

    private fun applyRoute() {
        val device = if (callMode) bluetoothDevice() else null
        if (device == null) {
            clearRoute()
            emitRoute("phone", "")
            return
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = if (Build.VERSION.SDK_INT >= 31) {
            audio.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            true
        }
        routedToBluetooth = ok
        if (!ok) audio.mode = AudioManager.MODE_NORMAL
        emitRoute(if (ok) "bluetooth" else "phone", device.productName?.toString() ?: "")
    }

    private fun clearRoute() {
        if (!routedToBluetooth) return
        routedToBluetooth = false
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
        }
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun bluetoothDevice(): AudioDeviceInfo? {
        val candidates = if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices
        else audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return candidates.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    // Pauses music while Fluent talks and listens.
    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        clearRoute()
        abandonFocus()
        recognizer?.destroy()
        tts?.shutdown()
        activity.stopService(Intent(activity, ConversationService::class.java))
    }

    // ---------- events to the page ----------

    private fun emitSr(id: Int, event: String, error: String? = null, texts: List<String>? = null) {
        val e = JSONObject().put("type", "sr").put("id", id).put("event", event)
        if (error != null) e.put("error", error)
        if (texts != null) e.put("texts", JSONArray(texts))
        emit(e)
    }

    private fun emitTts(id: Int) = emit(JSONObject().put("type", "tts").put("id", id))

    private fun emitRoute(route: String, name: String) =
        emit(JSONObject().put("type", "route").put("route", route).put("name", name))

    private fun emit(event: JSONObject) = main.post {
        web.evaluateJavascript("window.__fluentNative && window.__fluentNative.onEvent($event)", null)
    }
}
