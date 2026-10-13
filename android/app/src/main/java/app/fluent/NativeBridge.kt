package app.fluent

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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

    // ---------- storage ----------
    // Settings, notebook and conversation, kept by the app so they survive WebView resets and updates.
    private val prefs = activity.getSharedPreferences("fluent", android.content.Context.MODE_PRIVATE)

    @JavascriptInterface
    fun getItem(key: String): String? = prefs.getString(key, null)

    @JavascriptInterface
    fun setItem(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ---------- speech recognition ----------
    // Microphone modes:
    //  "system": the recognizer opens the microphone itself (in a car, it may ask the car for voice-command mode).
    //  "stream": Fluent records the call-mode microphone (the car's, over Bluetooth) and feeds the recognizer.
    //  "stream_ondevice": same, with the on-device recognizer.
    private var micMode = "stream"

    @JavascriptInterface
    fun setMicMode(mode: String) = main.post {
        if (mode == micMode) return@post
        micMode = mode
        recognizer?.destroy(); recognizer = null
        diag("Mode micro : $mode")
    }

    private fun createRecognizer(): SpeechRecognizer {
        readyLogged = false
        if (micMode == "stream_ondevice" && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            diag("Reconnaissance : sur l'appareil")
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        }
        diag("Reconnaissance : service Google")
        return SpeechRecognizer.createSpeechRecognizer(activity)
    }

    @JavascriptInterface
    fun startListening(id: Int, lang: String, maxAlternatives: Int) = main.post {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            diag("Permission micro refusée")
            emitSr(id, "error", "not-allowed"); emitSr(id, "end"); return@post
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            diag("Aucun service de reconnaissance vocale")
            emitSr(id, "error", "service-not-allowed"); emitSr(id, "end"); return@post
        }
        val r = recognizer ?: createRecognizer().also { recognizer = it }
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
        var source: ParcelFileDescriptor? = null
        if (micMode != "system" && Build.VERSION.SDK_INT >= 33) {
            source = startStream()
            if (source != null) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, STREAM_RATE)
            } else {
                diag("Flux micro impossible, le service ouvre le micro lui-même")
            }
        }
        // The recognizer may still be connecting: the pipe stays open until this utterance ends (stopStream).
        r.startListening(intent)
    }

    @JavascriptInterface
    fun stopListening(id: Int) = main.post {
        if (id != currentRec) return@post
        stopStream()
        recognizer?.stopListening()
    }

    @JavascriptInterface
    fun cancelListening(id: Int) = main.post {
        if (id != currentRec) return@post
        stopStream()
        recognizer?.cancel()
        currentRec = 0
        emitSr(id, "end")
    }

    private var lastRmsEmit = 0L
    private var readyLogged = false
    @Volatile private var streamBytes = 0L
    private var lastMicLogged = ""

    private fun listenerFor(id: Int) = object : RecognitionListener {
        private fun current() = id == currentRec

        override fun onPartialResults(bundle: Bundle) {
            if (!current()) return
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            if (!texts.isNullOrEmpty()) emitSr(id, "partial", texts = texts.take(1))
        }

        override fun onResults(bundle: Bundle) {
            if (!current()) return
            stopStream()
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            if (!texts.isNullOrEmpty()) emitSr(id, "final", texts = texts)
            currentRec = 0
            emitSr(id, "end")
        }

        override fun onError(error: Int) {
            if (!current()) return
            stopStream()
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                val sent = if (streamBytes > 0) "%.1f s de son transmis".format(streamBytes / (2.0 * STREAM_RATE)) else "aucun son transmis"
                diag("Erreur reconnaissance : ${errorName(error)} (code $error, mode $micMode, $sent)")
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.destroy(); recognizer = null
            }
            // The on-device recognizer may need its language pack: ask Android to download it.
            if (Build.VERSION.SDK_INT >= 33 && micMode == "stream_ondevice" &&
                (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            ) {
                try {
                    recognizer?.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
                    diag("Téléchargement du pack de langue demandé")
                } catch (e: Exception) {}
            }
            emitSr(id, "error", webError(error))
            currentRec = 0
            emitSr(id, "end")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // In stream mode the level comes from Fluent's own recording instead.
            if (streaming) return
            val now = System.currentTimeMillis()
            if (now - lastRmsEmit < 150) return
            lastRmsEmit = now
            emitLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (!readyLogged) { readyLogged = true; diag("Moteur prêt à écouter") }
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- recording the call-mode microphone ----------
    @Volatile private var streaming = false
    private var streamThread: Thread? = null
    private var streamOut: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var streamIn: ParcelFileDescriptor? = null

    private fun startStream(): ParcelFileDescriptor? {
        stopStream()
        val minBuf = AudioRecord.getMinBufferSize(STREAM_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, STREAM_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, STREAM_RATE)
            )
        } catch (e: Exception) {
            diag("Enregistrement impossible : ${e.message}"); return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); diag("Enregistrement non initialisé"); return null
        }
        val carMic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (carMic != null) recorder.preferredDevice = carMic
        val pipe = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try { recorder.startRecording() } catch (e: Exception) {
            diag("Démarrage enregistrement impossible : ${e.message}")
            recorder.release(); out.close(); pipe[0].close(); return null
        }
        streamBytes = 0
        streaming = true
        streamOut = out
        streamIn = pipe[0]
        streamThread = Thread {
            val samples = ShortArray(STREAM_RATE / 10)  // 100 ms
            val bytes = ByteArray(samples.size * 2)
            var lastLevel = 0L
            try {
                while (streaming) {
                    val n = recorder.read(samples, 0, samples.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = samples[i].toInt()
                        bytes[2 * i] = (v and 0xff).toByte()
                        bytes[2 * i + 1] = (v shr 8 and 0xff).toByte()
                        sum += v.toDouble() * v
                    }
                    out.write(bytes, 0, n * 2)
                    if (streamBytes == 0L) {
                        // Logged once per microphone, when the first audio actually reaches the recognizer.
                        val d = recorder.routedDevice
                        val mic = if (d == null) "inconnu" else "${deviceType(d.type)} ${d.productName}"
                        if (mic != lastMicLogged) { lastMicLogged = mic; diag("Son transmis au moteur, micro : $mic") }
                    }
                    streamBytes += n * 2
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 150) {
                        lastLevel = now
                        val rms = Math.sqrt(sum / n) / 32768.0
                        // About -60 dBFS (silence) to -10 dBFS (loud speech) mapped to 0..1.
                        val db = 20 * Math.log10(rms.coerceAtLeast(1e-6))
                        emitLevel(((db + 60) / 50).toFloat().coerceIn(0f, 1f))
                    }
                }
            } catch (e: Exception) {
                // The recognizer stopped reading: this utterance is over.
            } finally {
                try { out.close() } catch (e: Exception) {}
                try { recorder.stop() } catch (e: Exception) {}
                recorder.release()
            }
        }.also { it.start() }
        return pipe[0]
    }

    private fun stopStream() {
        streaming = false
        streamThread = null
        // Closing the pipe also unblocks a write the recognizer is no longer reading.
        try { streamOut?.close() } catch (e: Exception) {}
        try { streamIn?.close() } catch (e: Exception) {}
        streamOut = null
        streamIn = null
    }

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "réseau (délai)"
        SpeechRecognizer.ERROR_NETWORK -> "réseau"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "serveur"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "aucune parole"
        SpeechRecognizer.ERROR_NO_MATCH -> "rien reconnu"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "occupé"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "trop de requêtes"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "serveur déconnecté"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "langue non prise en charge"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "langue indisponible (pack à télécharger)"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "vérification impossible"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "suivi du téléchargement impossible"
        else -> "code $code"
    }

    private fun deviceType(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (appel)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro du téléphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "casque filaire"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        else -> "type $type"
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
            if (callMode) diag("Mode appel : aucun appareil Bluetooth d'appel trouvé")
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
        diag("Mode appel : ${device.productName} — " + if (ok) "activé" else "refusé")
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
        stopStream()
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

    private fun diag(line: String) = emit(JSONObject().put("type", "diag").put("line", line))

    private fun emitLevel(level: Float) = emit(JSONObject().put("type", "level").put("level", level.toDouble()))

    private fun emitTts(id: Int) = emit(JSONObject().put("type", "tts").put("id", id))

    private fun emitRoute(route: String, name: String) =
        emit(JSONObject().put("type", "route").put("route", route).put("name", name))

    private fun emit(event: JSONObject) = main.post {
        web.evaluateJavascript("window.__fluentNative && window.__fluentNative.onEvent($event)", null)
    }

    companion object {
        private const val STREAM_RATE = 16000
    }
}
