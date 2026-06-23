package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * HindiTtsService — TTS-driven subtitle sync
 *
 * Core principle: subtitle only shows when TTS speaks it.
 * Pipeline per sentence:
 *   1. Fetch WAV from Kokoro server
 *   2. Show subtitle on overlay
 *   3. Play WAV
 *   4. Clear subtitle when done
 *   5. Next sentence immediately (no gap)
 *
 * Gender: mic-based YIN pitch detection.
 *   Mic picks up speaker audio from tablet speakers.
 *   F0 < 165Hz → male  (sid=33)
 *   F0 ≥ 165Hz → female (sid=31)
 *   Paused during TTS playback to avoid self-detection.
 *
 * Speed: ttsSpeedMultiplier applied to Kokoro speed parameter.
 *   Kokoro 2x = speech twice as fast = shorter WAV = faster throughput.
 */
object HindiTtsService {

    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"

    private const val SID_FEMALE = 31   // hf_alpha — real Indian female voice
    private const val SID_MALE   = 33   // hm_omega — Indian male voice

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false
    @Volatile private var speakingUntilMs     = 0L

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cacheDir: java.io.File? = null

    // Single FIFO queue — one worker processes it sequentially
    // Unbounded so no sentence is ever dropped
    private val queue      = LinkedBlockingQueue<String>()
    private val readyQueue = LinkedBlockingQueue<Triple<String, ByteArray, Long>>()
    private var worker:   Job? = null
    private var pitchJob: Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var lastSpokenNorm = ""

    // Pitch history for gender smoothing
    private val pitchHistory = ArrayDeque<Gender>()
    private val PITCH_HISTORY = 10

    // ── Init / lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        cacheDir = context.cacheDir
        startWorker()
        startPitchDetector()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            queue.clear(); readyQueue.clear()
            stopMp(); lastSpokenNorm = ""
        }
    }

    fun setGender(g: Gender) {
        selectedGender = g
        if (g != Gender.AUTO) {
            pitchJob?.cancel(); pitchJob = null; pitchHistory.clear()
        } else {
            startPitchDetector()
        }
    }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        pitchJob?.cancel(); worker?.cancel()
        queue.clear(); readyQueue.clear()
        stopMp(); scope.cancel()
    }

    // ── Enqueue sentence ─────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled) return
        if (hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n
        queue.offer(n)  // unbounded — never drops
        Log.d(TAG, "TTS enqueued q=${queue.size} '${n.take(30)}'")
    }

    private fun startWorker() {
        // Stage A: Fetch worker — converts text → WAV in background
        val fetchJob2 = scope.launch {
            while (isActive) {
                // take() — blocks until text available, never misses a sentence
                val text = try { queue.take() }
                           catch (_: InterruptedException) { continue }
                if (!enabled) continue
                val emotion = detectEmotion(text)
                val speed   = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
                val sid     = effectiveSid()
                val wav     = fetchWav(text, sid, speed)
                if (wav != null && wav.size > 44) {
                    val sr   = readInt(wav, 24).coerceAtLeast(8_000)
                    val nCh  = readShort(wav, 22).coerceAtLeast(1)
                    val bits = readShort(wav, 34).coerceAtLeast(8)
                    val dur  = ((wav.size - 44).toLong() * 1000) / (sr.toLong() * nCh * (bits / 8))
                    // Keep max 3 in ready queue — drop oldest if far behind
                    while (readyQueue.size >= 3) readyQueue.poll()
                    readyQueue.offer(Triple(text, wav, dur))
                    Log.d(TAG, "WAV ready ${dur}ms readyQ=${readyQueue.size}")
                } else {
                    Log.w(TAG, "Empty WAV — is hindi_tts_server.py running on :8766?")
                }
            }
        }

        // Stage B: Play worker — plays WAV and shows subtitle in sync
        worker = scope.launch {
            while (isActive) {
                // take() blocks until item available — never skips or times out
                val item = try { readyQueue.take() }
                           catch (_: InterruptedException) { continue }
                if (!enabled) continue
                val (text, wav, dur) = item
                try {
                    isSpeaking = true
                    showSubtitle(text)
                    playWav(wav, dur)
                    hideSubtitle()
                    speakingUntilMs = System.currentTimeMillis() + 200L
                } catch (e: Exception) {
                    Log.e(TAG, "Play: ${e.message}")
                } finally {
                    isSpeaking = false
                    hideSubtitle()
                }
            }
        }
    }

    private fun effectiveSid(): Int {
        val g = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        return if (g == Gender.FEMALE) SID_FEMALE else SID_MALE
    }

    // ── Subtitle sync (show/hide driven by TTS) ───────────────────────────────

    private fun showSubtitle(text: String) {
        mainHandler.post {
            OverlayService.showTtsText(text)
        }
    }

    private fun hideSubtitle() {
        mainHandler.post {
            OverlayService.clearTtsText()
        }
    }

    // ── WAV fetch from Kokoro server ─────────────────────────────────────────

    private suspend fun fetchWav(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc = java.net.URLEncoder.encode(text, "UTF-8")
                val url = "$TTS_URL?text=$enc&sid=$sid&speed=$speed"
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (e: Exception) {
                Log.e(TAG, "fetchWav: ${e.message}"); null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

    // ── WAV playback ─────────────────────────────────────────────────────────

    private suspend fun playWav(wav: ByteArray, durMs: Long) {
        Log.d(TAG, "playWav dur=${durMs}ms size=${wav.size}B")

        // Write WAV to cache dir — must happen on IO thread
        val f = withContext(Dispatchers.IO) {
            try {
                val dir = cacheDir ?: run {
                    Log.e(TAG, "cacheDir null — init() not called?"); return@withContext null
                }
                val file = java.io.File(dir, "tts_hindi.wav")
                file.writeBytes(wav)
                file
            } catch (e: Exception) {
                Log.e(TAG, "write WAV: ${e.message}"); null
            }
        } ?: return

        val latch = java.util.concurrent.CountDownLatch(1)

        withContext(Dispatchers.Main) {
            try {
                stopMp()
                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(f.absolutePath)
                mp.setOnPreparedListener {
                    Log.d(TAG, "MP prepared, starting")
                    it.start()
                }
                mp.setOnCompletionListener {
                    Log.d(TAG, "MP complete")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP error what=$w extra=$x")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    latch.countDown()
                    true
                }
                mediaPlayer = mp
                mp.prepareAsync()   // non-blocking — fires onPreparedListener when ready
                Log.d(TAG, "MP prepareAsync called")
            } catch (e: Exception) {
                Log.e(TAG, "playWav setup: ${e.message}")
                latch.countDown()
            }
        }

        // Wait for completion using actual duration as timeout
        val timeout = (durMs + 2_000L).coerceIn(3_000L, 60_000L)
        val done = withContext(Dispatchers.IO) {
            latch.await(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (!done) Log.w(TAG, "playWav timeout after ${timeout}ms")
    }

    private fun stopMp() {
        try { mediaPlayer?.stop()    } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ── Gender detection — polls whisper_server /gender endpoint ─────────────
    // whisper_server.py already has raw audio from Kokoro/Whisper and runs FFT.
    // The /gender endpoint returns the actual speaker's F0-based gender.
    // Polled every 1.5s, paused during TTS playback.
    // History-smoothed over 8 readings to prevent rapid flipping.

    private fun startPitchDetector() {
        if (pitchJob?.isActive == true) return
        pitchJob = scope.launch {
            Log.d(TAG, "Gender poller started → polling :8765/gender every 1.5s")
            while (isActive) {
                if (selectedGender == Gender.AUTO && !isSuppressed()) {
                    try {
                        val conn = URL("http://127.0.0.1:8765/gender")
                            .openConnection() as HttpURLConnection
                        conn.connectTimeout = 1_500
                        conn.readTimeout    = 2_000
                        conn.requestMethod  = "GET"
                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader().readText()
                            val json = org.json.JSONObject(body)
                            val g    = json.optString("gender", "")
                            val conf = json.optInt("confidence", 0)
                            if (g == "female" || g == "male") {
                                val gender = if (g == "female") Gender.FEMALE else Gender.MALE
                                pitchHistory.addLast(gender)
                                if (pitchHistory.size > PITCH_HISTORY) pitchHistory.removeFirst()
                                val fCount   = pitchHistory.count { it == Gender.FEMALE }
                                val majority = if (fCount > pitchHistory.size / 2)
                                    Gender.FEMALE else Gender.MALE
                                if (majority != detectedGender) {
                                    detectedGender = majority
                                    Log.d(TAG, "Gender → $majority ($g conf=$conf f=$fCount/${pitchHistory.size})")
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) { }
                }
                delay(1_500)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","cry","sorry").any { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any     { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","amazing","खुश","love").any { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f
        Emotion.HAPPY   -> 1.05f
        Emotion.CURIOUS -> 0.97f
        Emotion.SAD     -> 0.85f
        Emotion.ANGRY   -> 1.08f
        Emotion.NEUTRAL -> 1.00f
    }

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl 8)  or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
}
