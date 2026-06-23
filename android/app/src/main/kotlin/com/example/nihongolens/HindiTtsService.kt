package com.example.nihongolens

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * HindiTtsService — Android built-in TTS, zero latency.
 *
 * NO Kokoro server needed — hindi_tts_server.py is NOT required.
 *
 * Loop prevention:
 *   Mic is muted by MainActivity when overlay starts (see startOverlay handler).
 *   TTS plays through speakers but mic is off → Live Captions cannot hear it.
 *
 * Token dedup:
 *   Each sentence gets a unique token. Once spoken, token stored in spokenTokens.
 *   Same sentence never spoken twice in a session.
 *
 * Gender detection:
 *   Polls whisper_server /gender endpoint (port 8765).
 *   Whisper server already has raw audio PCM from the video and runs FFT.
 *   Male F0 < 165Hz → pitch=1.0, Female F0 ≥ 165Hz → pitch=0.80.
 */
object HindiTtsService {

    private const val TAG = "HindiTTS"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled        = false
    @JvmField @Volatile var selectedGender = Gender.AUTO
    @Volatile var ttsSpeedMultiplier       = 1.5f
    @Volatile var detectedGender           = Gender.MALE
    @Volatile var isSpeaking               = false
    @Volatile private var speakingUntilMs  = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Token dedup — never re-speak the same sentence
    private val spokenTokens = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private var uttId = 0

    // Gender polling
    private var genderJob: Job? = null
    private val genderHistory = ArrayDeque<Gender>()
    private val GENDER_HIST = 6

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        // Block Live Captions from capturing TTS audio output
        // ALLOW_CAPTURE_BY_NONE prevents any system service from capturing this app's audio
        // This is the only reliable way to prevent the TTS→LiveCaptions→TTS loop
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.allowedCapturePolicy = android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi TTS voice not installed. Go to: Settings → General management → Language → Text-to-speech → Google TTS → Install voice data → Hindi")
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { isSpeaking = true }
                    override fun onDone(id: String?) {
                        if (tts?.isSpeaking == false) {
                            isSpeaking = false
                            speakingUntilMs = System.currentTimeMillis() + 1_500L
                        }
                    }
                    override fun onError(id: String?) {
                        isSpeaking = false
                        speakingUntilMs = System.currentTimeMillis() + 1_000L
                    }
                })
                ttsReady = true
                Log.d(TAG, "TTS ready")
            }
        }
        startGenderPoller()
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || !ttsReady || hindi.isBlank()) return
        val engine = tts ?: return

        // Token dedup — hash of normalised text
        val norm  = hindi.trim().replace(Regex("\\s+"), " ")
        val token = norm.hashCode()
        if (spokenTokens.putIfAbsent(token, true) != null) {
            Log.d(TAG, "TTS skip (already spoken): '${norm.take(30)}'")
            return
        }
        // Limit token cache size
        if (spokenTokens.size > 200) spokenTokens.clear()

        val emotion = detectEmotion(hindi)
        val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val gender  = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        val pitch   = if (gender == Gender.FEMALE) 1.3f else 1.0f

        engine.setSpeechRate(speed)
        engine.setPitch(pitch)
        isSpeaking = true   // set before speak() to block enqueue immediately

        val id = "utt_${uttId++}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM,
                android.media.AudioManager.STREAM_MUSIC.toString())
        }
        engine.speak(hindi, TextToSpeech.QUEUE_ADD, params, id)
        Log.d(TAG, "TTS speak pitch=$pitch speed=$speed '${hindi.take(30)}'")
    }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            tts?.stop()
            isSpeaking = false
            spokenTokens.clear()
        }
    }

    fun setGender(g: Gender) { selectedGender = g }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun destroy() {
        genderJob?.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false; scope.cancel()
    }

    // ── Gender polling from whisper_server /gender ────────────────────────────
    // whisper_server.py already processes raw audio PCM from the video.
    // It runs FFT and stores gender in _gender_cache.
    // We poll every 2s — no mic needed, no loop risk.

    private fun startGenderPoller() {
        genderJob?.cancel()
        genderJob = scope.launch {
            Log.d(TAG, "Gender poller started → :8765/gender")
            while (isActive) {
                if (selectedGender == Gender.AUTO && !isSuppressed()) {
                    try {
                        val conn = URL("http://127.0.0.1:8765/gender")
                            .openConnection() as HttpURLConnection
                        conn.connectTimeout = 1_500
                        conn.readTimeout    = 2_000
                        conn.requestMethod  = "GET"
                        if (conn.responseCode == 200) {
                            val json = org.json.JSONObject(
                                conn.inputStream.bufferedReader().readText())
                            val g    = json.optString("gender", "")
                            val conf = json.optInt("confidence", 0)
                            if ((g == "female" || g == "male") && conf >= 2) {
                                val newG = if (g == "female") Gender.FEMALE else Gender.MALE
                                genderHistory.addLast(newG)
                                if (genderHistory.size > GENDER_HIST) genderHistory.removeFirst()
                                val fCount = genderHistory.count { it == Gender.FEMALE }
                                val majority = if (fCount > genderHistory.size / 2)
                                    Gender.FEMALE else Gender.MALE
                                if (majority != detectedGender) {
                                    detectedGender = majority
                                    Log.d(TAG, "Gender → $majority ($g conf=$conf)")
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
                delay(2_000)
            }
        }
    }

    // ── Emotion helpers ───────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","cry","sorry").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate").any           { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","खुश","love","great").any  { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f; Emotion.HAPPY   -> 1.05f
        Emotion.CURIOUS -> 0.97f; Emotion.SAD      -> 0.88f
        Emotion.ANGRY   -> 1.08f; Emotion.NEUTRAL  -> 1.00f
    }
}
