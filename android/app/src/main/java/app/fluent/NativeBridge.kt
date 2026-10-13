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
        if (mode == "system") closeMic() else if (active && Build.VERSION.SDK_INT >= 33) openMic()
        diag("Mode micro : $mode")
    }

    private var engineLogged = ""

    private fun createRecognizer(): SpeechRecognizer {
        val onDevice = micMode == "stream_ondevice" && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        val name = if (onDevice) "sur l'appareil" else "service Google"
        if (engineLogged != name) { engineLogged = name; diag("Reconnaissance : $name") }
        return if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        else SpeechRecognizer.createSpeechRecognizer(activity)
    }

    // One listening attempt ("écoute") per startListening call, numbered for the diagnostics.
    private var listenNo = 0
    private var listenStart = 0L
    private var sawReady = false
    private var sawText = false
    private var stalls = 0
    private val watchdog = Runnable { checkStalled() }

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
        val streamed = micMode != "system" && Build.VERSION.SDK_INT >= 33
        // In streamed mode the previous recognizer was destroyed when its attempt ended (see endAttempt).
        val fresh = recognizer == null
        val r = recognizer ?: createRecognizer().also { recognizer = it }
        listenNo++
        listenStart = System.currentTimeMillis()
        sawReady = false
        sawText = false
        currentRec = id
        currentLang = lang
        lastPartial = ""
        if (fresh) diag("Écoute #$listenNo : moteur neuf")
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
        if (streamed) {
            val source = startStream()
            if (source != null) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, STREAM_RATE)
            } else {
                diag("Flux micro impossible, le service ouvre le micro lui-même")
            }
        }
        // The recognizer may still be connecting: the pipe stays open until this attempt ends (stopStream).
        r.startListening(intent)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    // A recognizer that never gets ready, or never reads the audio Fluent sends, is replaced.
    private fun checkStalled() {
        val id = currentRec
        if (id == 0) return
        val reading = streamBytes > PIPE_BYTES
        val stalled = !sawReady || (streaming && !reading && !sawText)
        if (!stalled) { stalls = 0; return }
        stalls++
        diag("Écoute #$listenNo : moteur bloqué (" + (if (!sawReady) "jamais prêt" else "ne lit pas le son") + "), relance $stalls")
        recognizer?.cancel()
        recognizer?.destroy(); recognizer = null
        if (stalls >= 3) { stalls = 0; emit(JSONObject().put("type", "stalled")) }
        emitSr(id, "error", "aborted")
        endAttempt(id)
    }

    // Ends the current attempt: stops recording, logs what happened, and lets the page listen again.
    private fun endAttempt(id: Int) {
        main.removeCallbacks(watchdog)
        val wasStreaming = streaming || streamBytes > 0
        stopStream()
        if (wasStreaming && (sawText || streamBytes > 0)) {
            val secs = "%.1f".format(streamBytes / (2.0 * STREAM_RATE))
            val read = if (streamBytes > PIPE_BYTES) "lu par le moteur" else "pas lu par le moteur"
            diag("Écoute #$listenNo terminée : $secs s de son, $read" + levelSummary() + if (sawText) "" else ", aucun texte")
        }
        if (sawText) stalls = 0
        // In streamed mode a recognizer is not reused (a stopped one can stay stuck without reading).
        // Destroying it now, not when the next attempt starts, gives Google's service time to close the
        // session while Claude answers; otherwise the new one fails at once with "server disconnected".
        if (micMode != "system") { recognizer?.destroy(); recognizer = null }
        currentRec = 0
        emitSr(id, "end")
    }

    @JavascriptInterface
    fun stopListening(id: Int) = main.post {
        if (id != currentRec) return@post
        stopStream()
        recognizer?.stopListening()
        // With a streamed source the recognizer may never answer: after a moment, send the last text it gave.
        main.postDelayed({
            if (id != currentRec) return@postDelayed
            if (lastPartial.isNotBlank()) emitSr(id, "final", texts = listOf(lastPartial))
            diag("Écoute #$listenNo : moteur muet après l'arrêt, envoi du dernier texte reconnu")
            recognizer?.cancel()
            endAttempt(id)
        }, 1500)
    }

    @JavascriptInterface
    fun cancelListening(id: Int) = main.post {
        if (id != currentRec) return@post
        recognizer?.cancel()
        endAttempt(id)
    }

    private var lastRmsEmit = 0L
    private var currentLang = ""
    private var lastPartial = ""
    private val packRequested = mutableSetOf<String>()
    @Volatile private var streamBytes = 0L

    private fun listenerFor(id: Int) = object : RecognitionListener {
        private fun current() = id == currentRec

        override fun onPartialResults(bundle: Bundle) {
            if (!current()) return
            val text = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() } ?: return
            sawText = true
            // A streamed session restarts its text after each pause. When the new text clearly starts over,
            // keep the previous piece as finished instead of losing it.
            if (startsOver(lastPartial, text)) commitPiece(lastPartial)
            lastPartial = text
            emitSr(id, "partial", texts = listOf(text))
        }

        // Pieces finished at each pause (Android 13+, streamed or segmented sessions).
        override fun onSegmentResults(bundle: Bundle) {
            if (!current()) return
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            lastPartial = ""
            if (texts.isNullOrEmpty()) return
            sawText = true
            diag("Morceau reconnu : ${texts[0]}")
            emitSr(id, "final", texts = texts)
        }

        override fun onEndOfSegmentedSession() {
            if (!current()) return
            endAttempt(id)
        }

        override fun onResults(bundle: Bundle) {
            if (!current()) return
            val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            lastPartial = ""
            if (!texts.isNullOrEmpty()) { sawText = true; diag("Phrase reconnue : ${texts[0]}"); emitSr(id, "final", texts = texts) }
            endAttempt(id)
        }

        private fun commitPiece(text: String) {
            diag("Morceau reconnu : $text")
            emitSr(id, "final", texts = listOf(text))
        }

        override fun onError(error: Int) {
            if (!current()) return
            // The service still closing a previous session: not a network problem, just listen again.
            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !sawReady) {
                diag("Écoute #$listenNo : service pas encore disponible (code 11), relance silencieuse")
                recognizer?.destroy(); recognizer = null
                emitSr(id, "error", "aborted")
                endAttempt(id)
                return
            }
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                val sent = if (streamBytes > 0) "%.1f s de son transmis".format(streamBytes / (2.0 * STREAM_RATE)) else "aucun son transmis"
                diag("Erreur reconnaissance : ${errorName(error)} (code $error, mode $micMode, $sent)")
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.destroy(); recognizer = null
            }
            // The on-device recognizer needs the pack of the practised language: ask once, then fall back to mode 1.
            if (Build.VERSION.SDK_INT >= 33 && micMode == "stream_ondevice" &&
                (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            ) {
                if (packRequested.add(currentLang)) {
                    try {
                        recognizer?.triggerModelDownload(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
                        )
                        diag("Pack de reconnaissance « $currentLang » demandé à Android")
                    } catch (e: Exception) {
                        diag("Téléchargement du pack « $currentLang » impossible : ${e.message}")
                    }
                }
                diag("Retour au micro de l'appel avec la reconnaissance Google (mode 1)")
                micMode = "stream"
                recognizer?.destroy(); recognizer = null
                emit(JSONObject().put("type", "micmode").put("mode", micMode))
            }
            emitSr(id, "error", webError(error))
            endAttempt(id)
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
            if (!current()) return
            if (!sawReady) {
                sawReady = true
                diag("Écoute #$listenNo : moteur prêt (${System.currentTimeMillis() - listenStart} ms)")
            }
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- the call-mode microphone ----------
    // Opened once per conversation and kept running. Reopening it for every sentence, right after Claude's
    // voice had played on the call channel, gave a silent microphone in the car. Each listening attempt only
    // attaches a pipe to the running microphone; while Fluent speaks, the audio is dropped.
    @Volatile private var streaming = false
    @Volatile private var streamOut: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var streamIn: ParcelFileDescriptor? = null
    @Volatile private var micGeneration = 0
    @Volatile private var micRunning = false
    @Volatile private var speakingNow = false
    // Per-attempt statistics for the diagnostics.
    @Volatile private var levelSum = 0.0
    @Volatile private var levelCount = 0
    @Volatile private var levelMax = -100.0
    @Volatile private var zeroChunks = 0

    private fun carMicDevice(): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun openMic(): Boolean {
        if (micRunning) return true
        val minBuf = AudioRecord.getMinBufferSize(STREAM_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION: no call-style echo cancellation, which can smother the voice after playback.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, STREAM_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, STREAM_RATE)
            )
        } catch (e: Exception) {
            diag("Micro impossible à ouvrir : ${e.message}"); return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); diag("Micro non initialisé"); return false
        }
        carMicDevice()?.let { recorder.preferredDevice = it }
        try { recorder.startRecording() } catch (e: Exception) {
            diag("Démarrage du micro impossible : ${e.message}"); recorder.release(); return false
        }
        val generation = ++micGeneration
        micRunning = true
        Thread { readMic(recorder, generation) }.start()
        return true
    }

    private fun closeMic() {
        micRunning = false
        micGeneration++
    }

    private fun reopenMic() {
        closeMic()
        openMic()
    }

    private fun readMic(recorder: AudioRecord, generation: Int) {
        val samples = ShortArray(STREAM_RATE / 10)  // 100 ms
        val bytes = ByteArray(samples.size * 2)
        var lastLevel = 0L
        var micLogged = false
        try {
            while (micRunning && generation == micGeneration) {
                val n = recorder.read(samples, 0, samples.size)
                if (n <= 0) continue
                if (!micLogged) {
                    micLogged = true
                    val d = recorder.routedDevice
                    diag("Micro ouvert : " + (if (d == null) "appareil inconnu" else "${deviceType(d.type)} ${d.productName}"))
                }
                var sum = 0.0
                var peak = 0
                for (i in 0 until n) {
                    val v = samples[i].toInt()
                    bytes[2 * i] = (v and 0xff).toByte()
                    bytes[2 * i + 1] = (v shr 8 and 0xff).toByte()
                    sum += v.toDouble() * v
                    if (Math.abs(v) > peak) peak = Math.abs(v)
                }
                val rms = Math.sqrt(sum / n) / 32768.0
                val db = 20 * Math.log10(rms.coerceAtLeast(1e-6))
                val now = System.currentTimeMillis()
                if (now - lastLevel > 150) {
                    lastLevel = now
                    // About -60 dBFS (silence) to -10 dBFS (loud speech) mapped to 0..1.
                    emitLevel(((db + 60) / 50).toFloat().coerceIn(0f, 1f))
                }
                if (speakingNow) continue
                val out = streamOut ?: continue
                levelSum += db; levelCount++
                if (db > levelMax) levelMax = db
                // Real microphones always pick up some noise: exact zeros for 3 s mean a muted capture.
                if (peak == 0) zeroChunks++ else zeroChunks = 0
                if (zeroChunks == 30) main.post { zeroChunks = 0; diag("Micro muet (son nul depuis 3 s) : réouverture du micro"); reopenMic() }
                try {
                    out.write(bytes, 0, n * 2)
                    streamBytes += n * 2
                } catch (e: Exception) {
                    // The attempt ended and its pipe was closed: keep the microphone running for the next one.
                    if (streamOut === out) streamOut = null
                }
            }
        } catch (e: Exception) {
            diag("Micro interrompu : ${e.message}")
        } finally {
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
            if (generation == micGeneration) micRunning = false
        }
    }

    private fun startStream(): ParcelFileDescriptor? {
        stopStream()
        if (!openMic()) return null
        val pipe = ParcelFileDescriptor.createPipe()
        streamBytes = 0
        levelSum = 0.0; levelCount = 0; levelMax = -100.0; zeroChunks = 0
        streamIn = pipe[0]
        streamOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        streaming = true
        return pipe[0]
    }

    // Detaches the current attempt from the microphone (which keeps running).
    private fun stopStream() {
        streaming = false
        val out = streamOut
        streamOut = null
        // Closing the pipe also unblocks a write the recognizer is no longer reading.
        try { out?.close() } catch (e: Exception) {}
        try { streamIn?.close() } catch (e: Exception) {}
        streamIn = null
    }

    private fun levelSummary(): String =
        if (levelCount == 0) "" else ", niveau moyen %.0f dB, max %.0f dB".format(levelSum / levelCount, levelMax)

    // True when a partial result starts a new piece rather than revising the current one.
    private fun startsOver(previous: String, next: String): Boolean {
        val a = previous.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val b = next.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (a.size < 2 || b.isEmpty()) return false
        return b.size < a.size && b[0] != a[0]
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
        speakingNow = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "u$id")
    }

    @JavascriptInterface
    fun stopSpeaking() = main.post {
        val id = pendingTts
        pendingTts = 0
        speakingNow = false
        tts?.stop()
        if (id != 0) emitTts(id)
    }

    private fun ttsFinished(utteranceId: String) {
        val id = utteranceId.removePrefix("u").toIntOrNull() ?: return
        main.post {
            if (pendingTts == id) { pendingTts = 0; speakingNow = false }
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
            if (micMode != "system" && Build.VERSION.SDK_INT >= 33) openMic()
            service.putExtra(ConversationService.EXTRA_TEXT, if (routedToBluetooth) "Conversation (Bluetooth)" else "Conversation")
            try { activity.startForegroundService(service) } catch (e: Exception) {}
        } else {
            stopStream()
            closeMic()
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
        // The route changed (car connected or not): reopen the microphone on the new route.
        if (micRunning) reopenMic()
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
        main.removeCallbacks(watchdog)
        stopStream()
        closeMic()
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
        // An Android pipe holds about 64 KB (2 s of audio): more than that sent means the recognizer is reading.
        private const val PIPE_BYTES = 70_000L
        private const val WATCHDOG_MS = 4000L
    }
}
