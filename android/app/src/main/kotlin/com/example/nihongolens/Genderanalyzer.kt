package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * GenderAnalyzer v9 — Gender + Emotion detection from USAGE_MEDIA internal audio.
 *
 * Uses AudioPlaybackCaptureConfiguration(USAGE_MEDIA) — pure internal audio.
 * No mic. Works with speakers and headphones.
 * Hindi TTS (USAGE_ASSISTANT) excluded at Android audio mixer level.
 *
 * Detects 23 emotion/voice types via 5 acoustic features per 128ms window.
 * Sets HindiTtsService.currentEmotion → TTS server applies speed+pitch.
 */
object GenderAnalyzer {

    private const val TAG        = "GenderAnalyzer"
    private const val SR         = 16_000
    private const val WIN        = 2048
    private const val F0_FEMALE  = 165f
    private const val YIN_THRESH = 0.25f
    private const val RMS_FLOOR  = 80f
    private const val HIST       = 3

    @Volatile var enabled    = false
    @Volatile var lastStatus = "waiting for screen capture permission"
    @Volatile var detectedEmotion: HindiTtsService.Emotion = HindiTtsService.Emotion.NEUTRAL

    private val history       = ArrayDeque<HindiTtsService.Gender>()
    private val emotionHistory = ArrayDeque<HindiTtsService.Emotion>()
    private val accum         = ShortArray(WIN)
    private var accumFill     = 0

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job?        = null
    private var captureRec: AudioRecord? = null

    private var prevF0     = 0f
    private var f0History  = FloatArray(8)
    private var f0HistIdx  = 0
    private var frameCount = 0
    private var analyzeCount = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(projection: MediaProjection? = null) {
        if (enabled) return
        if (projection == null) {
            lastStatus = "no projection — grant screen capture permission"
            CaptionLogger.log(TAG, "start() — no projection")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastStatus = "API < Q — not supported"; return
        }
        stop()
        captureJob = scope.launch { captureLoop(projection) }
    }

    fun stop() {
        enabled = false
        captureJob?.cancel(); captureJob = null
        try { captureRec?.stop()    } catch (_: Exception) {}
        try { captureRec?.release() } catch (_: Exception) {}
        captureRec = null
        history.clear(); emotionHistory.clear()
        accumFill = 0
        if (lastStatus != "waiting for screen capture permission")
            CaptionLogger.log(TAG, "stopped")
    }

    // ── USAGE_MEDIA capture loop ──────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        } catch (e: Exception) {
            enabled = false
            lastStatus = "config failed: ${e.message}"
            CaptionLogger.log(TAG, "config failed: ${e.message}")
            return@withContext
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 4)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            enabled = false
            lastStatus = "AudioRecord failed: ${e.message}"
            CaptionLogger.log(TAG, "AudioRecord failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            enabled = false; rec.release()
            lastStatus = "AudioRecord state=${rec.state}"
            CaptionLogger.log(TAG, "AudioRecord not initialized")
            return@withContext
        }

        captureRec = rec; enabled = true
        lastStatus = "capturing USAGE_MEDIA SR=${SR}Hz"
        rec.startRecording()
        CaptionLogger.log(TAG, ">>> INTERNAL AUDIO CAPTURE STARTED SR=${SR}Hz <<<")

        val buf = ByteArray(WIN * 2)
        var readCount = 0
        try {
            while (currentCoroutineContext().isActive && enabled) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> {
                        readCount++
                        if (readCount == 1)
                            CaptionLogger.log(TAG, "FIRST read: $n bytes — media audio flowing!")
                        ingest(buf, n)
                    }
                    n < 0 -> { CaptionLogger.log(TAG, "read error=$n"); break }
                }
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            captureRec = null; enabled = false
            CaptionLogger.log(TAG, "captureLoop ended reads=$readCount")
        }
    }

    // ── PCM ingestion ─────────────────────────────────────────────────────────

    private fun ingest(bytes: ByteArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            accum[accumFill++] = ((hi shl 8) or lo).toShort()
            i += 2
            if (accumFill >= WIN) { analyze(); accumFill = 0 }
        }
    }

    // ── YIN + emotion feature extraction ─────────────────────────────────────

    private fun analyze() {
        analyzeCount++

        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) return

        val tauMin = (SR / 300).coerceAtLeast(1)
        val tauMax = (SR / 60).coerceAtMost(WIN / 2 - 1)
        val half   = WIN / 2

        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until half) {
                val diff = accum[j].toFloat() / 32768f - accum[j + tau].toFloat() / 32768f
                s += diff * diff
            }
            d[tau] = s
        }

        val c = FloatArray(tauMax + 1); c[0] = 1f; var rs = 0f
        for (tau in 1..tauMax) {
            rs += d[tau]
            c[tau] = if (rs > 0f) d[tau] * tau / rs else 1f
        }

        var tau = tauMin
        var minCmndf = 1f
        while (tau < tauMax - 1) {
            if (c[tau] < minCmndf) minCmndf = c[tau]
            if (c[tau] < YIN_THRESH) {
                val best = if (tau + 1 < tauMax && c[tau + 1] < c[tau]) tau + 1 else tau
                val f0   = SR.toFloat() / best
                val hnr  = 1f - minCmndf
                onPitch(f0, rms, hnr)
                return
            }
            tau++
        }

        prevF0 = 0f
    }

    // ── Gender + Emotion classification ──────────────────────────────────────

    private fun onPitch(f0: Float, rms: Float, hnr: Float) {
        frameCount++

        // GENDER
        val gender = if (f0 >= F0_FEMALE) HindiTtsService.Gender.FEMALE
                     else                  HindiTtsService.Gender.MALE
        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()
        val fCount   = history.count { it == HindiTtsService.Gender.FEMALE }
        val majGender = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                        else                            HindiTtsService.Gender.MALE
        if (majGender != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majGender
            HindiTtsService.spokenTokens.clear()
            lastStatus = "MEDIA audio → $majGender (F0=${f0.toInt()}Hz)"
            CaptionLogger.log(TAG, ">>> Gender SWITCHED to $majGender F0=${f0.toInt()}Hz <<<")
        }

        // EMOTION FEATURES
        val f0Slope = if (prevF0 > 0f) (f0 - prevF0) / prevF0 else 0f
        prevF0 = f0

        f0History[f0HistIdx % f0History.size] = f0; f0HistIdx++
        val validF0  = f0History.filter { it > 0f }
        val f0Mean   = if (validF0.isEmpty()) f0 else validF0.average().toFloat()
        val f0Jitter = if (validF0.size < 2) 0f else
            validF0.map { abs(it - f0Mean) }.average().toFloat() / f0Mean.coerceAtLeast(1f)
        val rmsNorm  = (rms / 3000f).coerceIn(0f, 3f)

        // EMOTION RULES — 23 types, priority: rhythmic > intense > breathive > warm > basic
        val emotion: HindiTtsService.Emotion = when {
            // Rhythmic & Expressive
            f0Slope > 0.20f && rmsNorm > 1.5f && f0Jitter > 0.15f ->
                HindiTtsService.Emotion.GASPING
            f0Jitter > 0.18f && rmsNorm > 1.0f && f0 > f0Mean * 1.02f ->
                HindiTtsService.Emotion.PANTING
            f0 < f0Mean * 0.85f && f0Jitter < 0.06f && rmsNorm in 0.3f..1.0f && hnr > 0.4f ->
                HindiTtsService.Emotion.MOANING
            f0Slope < -0.12f && rmsNorm < 0.6f && hnr > 0.3f ->
                HindiTtsService.Emotion.SIGHING
            // Intense & Physiological
            f0 > f0Mean * 1.12f && rmsNorm > 1.3f && f0Jitter > 0.10f && hnr < 0.5f ->
                HindiTtsService.Emotion.STRAINED
            f0 < f0Mean * 0.80f && hnr < 0.30f && f0Jitter > 0.10f ->
                HindiTtsService.Emotion.GRAVELLY
            hnr < 0.35f && rmsNorm > 1.0f && f0Jitter > 0.08f ->
                HindiTtsService.Emotion.RASPY
            hnr < 0.45f && rmsNorm in 0.7f..1.5f && f0Jitter in 0.05f..0.12f ->
                HindiTtsService.Emotion.HUSKY
            // Basic high-energy
            f0Slope > 0.15f && rmsNorm > 0.8f ->
                HindiTtsService.Emotion.SURPRISED
            rmsNorm > 1.4f && hnr < 0.5f ->
                HindiTtsService.Emotion.ANGRY
            f0 > f0Mean * 1.05f && f0Jitter > 0.12f ->
                HindiTtsService.Emotion.FEARFUL
            // Breathive & Low-Intensity
            rmsNorm < 0.25f && hnr < 0.25f ->
                HindiTtsService.Emotion.WHISPERY
            f0 < f0Mean * 0.88f && rmsNorm < 0.4f && hnr < 0.4f ->
                HindiTtsService.Emotion.MURMURED
            rmsNorm < 0.35f && hnr < 0.40f && f0Jitter < 0.06f ->
                HindiTtsService.Emotion.HUSHED
            hnr < 0.40f && rmsNorm in 0.2f..0.8f && f0Jitter < 0.07f ->
                HindiTtsService.Emotion.BREATHY
            // Warm & Affectionate
            f0 < f0Mean * 0.92f && hnr > 0.65f && f0Jitter < 0.05f && rmsNorm < 0.9f ->
                HindiTtsService.Emotion.SULTRY
            rmsNorm < 0.45f && hnr > 0.60f && f0Jitter < 0.05f ->
                HindiTtsService.Emotion.TENDER
            f0 < f0Mean * 0.97f && hnr > 0.70f && f0Jitter < 0.04f ->
                HindiTtsService.Emotion.VELVETY
            hnr > 0.65f && f0Jitter < 0.05f && rmsNorm in 0.4f..1.1f ->
                HindiTtsService.Emotion.WARM
            // Basic emotional states
            f0 > f0Mean * 1.08f && f0Jitter < 0.08f && rmsNorm > 0.6f && hnr > 0.6f ->
                HindiTtsService.Emotion.HAPPY
            f0 < f0Mean * 0.93f && abs(f0Slope) < 0.05f && rmsNorm < 0.7f ->
                HindiTtsService.Emotion.SAD
            f0Slope < -0.10f && rmsNorm < 0.9f && hnr < 0.45f ->
                HindiTtsService.Emotion.DISGUST
            else -> HindiTtsService.Emotion.NEUTRAL
        }

        // Smooth over 5 frames
        emotionHistory.addLast(emotion)
        if (emotionHistory.size > 5) emotionHistory.removeFirst()
        val counts = emotionHistory.groupingBy { it }.eachCount()
        val smoothed: HindiTtsService.Emotion = counts.maxByOrNull { it.value }?.key
            ?: HindiTtsService.Emotion.NEUTRAL

        if (smoothed != detectedEmotion) {
            detectedEmotion = smoothed
            HindiTtsService.currentEmotion = smoothed
            CaptionLogger.log(TAG, "Emotion→${smoothed.name}[${smoothed.category}] " +
                "F0=${f0.toInt()}Hz slope=${f0Slope.fmt()} jitter=${f0Jitter.fmt()} " +
                "rms=${rmsNorm.fmt()} hnr=${hnr.fmt()} " +
                "spd=${smoothed.speedMult} pch=${smoothed.pitchMult}")
        }

        if (frameCount % 5 == 0)
            CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz → $gender | ${smoothed.name} " +
                "spd=${smoothed.speedMult} pch=${smoothed.pitchMult}")
    }

    private fun Float.fmt() = String.format("%.2f", this)
}
