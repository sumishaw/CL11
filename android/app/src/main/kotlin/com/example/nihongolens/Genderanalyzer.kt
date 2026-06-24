package com.example.nihongolens

import android.util.Log
import kotlin.math.*

/**
 * GenderAnalyzer — detects speaker gender from PCM audio fed by SpeechCaptureService.
 *
 * Architecture (v2 — shared PCM):
 *   Android only allows ONE AudioPlaybackCaptureConfiguration per MediaProjection.
 *   SpeechCaptureService owns the AudioRecord. After each raw read it calls
 *   GenderAnalyzer.feedPcm(bytes, count) directly — no second AudioRecord needed.
 *
 * Method: spectral centroid + F0 band energy ratio on 2048-sample FFT windows.
 *   Female speakers: F0 165-300Hz, spectral centroid > 1600Hz
 *   Male speakers:   F0  80-165Hz, spectral centroid < 1600Hz
 *
 * Smoothed over 3 windows (~150ms). Updates HindiTtsService.detectedGender.
 * Skips analysis during TTS playback to avoid detecting own voice.
 */
object GenderAnalyzer {

    private const val TAG  = "GenderAnalyzer"
    private const val SR   = 16_000
    private const val N    = 2048            // FFT window size — 128ms at 16kHz
    private const val HIST = 3               // history depth — switch on 2/3 majority

    @Volatile var enabled = false

    private val history = ArrayDeque<HindiTtsService.Gender>()

    // Accumulation buffer — filled from raw readBuf bytes fed by SpeechCaptureService
    private val accum     = ShortArray(N)
    private var accumFill = 0

    // Reusable FFT arrays
    private val re  = FloatArray(N)
    private val im  = FloatArray(N)

    fun start() {
        enabled = true
        history.clear()
        accumFill = 0
        Log.d(TAG, "GenderAnalyzer started (PCM-feed mode)")
        CaptionLogger.log(TAG, "started")
    }

    fun stop() {
        enabled = false
        history.clear()
        accumFill = 0
        Log.d(TAG, "GenderAnalyzer stopped")
    }

    /**
     * Called by SpeechCaptureService on every raw AudioRecord.read().
     * bytes: raw PCM 16-bit LE mono, count: number of valid bytes read.
     * Runs on the AudioCaptureThread — must be fast (no I/O, no blocking).
     */
    fun feedPcm(bytes: ByteArray, count: Int) {
        if (!enabled) return
        // Skip during TTS playback — avoid analyzing own Hindi voice
        if (HindiTtsService.isSuppressed()) { accumFill = 0; return }

        // Convert bytes → shorts and fill accumulator
        var i = 0
        while (i + 1 < count && accumFill < N) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            accum[accumFill++] = ((hi shl 8) or lo).toShort()
            i += 2
        }

        if (accumFill >= N) {
            analyze()
            accumFill = 0
        }
    }

    private fun analyze() {
        // RMS — skip silence / very quiet audio
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / N)
        if (rms < 30.0) return

        // Hann window + load into FFT arrays
        for (i in 0 until N) {
            val w = 0.5f * (1f - cos(2.0 * PI * i / (N - 1)).toFloat())
            re[i] = accum[i] * w / 32768f
            im[i] = 0f
        }

        fft(re, im, N)

        // Magnitude spectrum
        val mag = FloatArray(N / 2) { b -> sqrt(re[b] * re[b] + im[b] * im[b]) }

        // Spectral centroid over speech range 200–4000 Hz
        val speechLo = (200f * N / SR).toInt()
        val speechHi = (4000f * N / SR).toInt().coerceAtMost(N / 2 - 1)
        var wSum = 0.0; var eSum = 0.0
        for (b in speechLo..speechHi) {
            wSum += b.toDouble() * SR / N * mag[b]
            eSum += mag[b]
        }
        val centroid = if (eSum < 1e-4) 0f else (wSum / eSum).toFloat()

        // F0 band energy (80–165 Hz male, 165–300 Hz female)
        val maleLo   = (80f  * N / SR).toInt()
        val maleHi   = (165f * N / SR).toInt()
        val femaleLo = (165f * N / SR).toInt()
        val femaleHi = (300f * N / SR).toInt()
        val maleE    = (maleLo..maleHi).sumOf   { mag[it].toDouble() }.toFloat()
        val femaleE  = (femaleLo..femaleHi).sumOf { mag[it].toDouble() }.toFloat()
        val totalE   = maleE + femaleE + 1e-6f
        val femaleRatio = femaleE / totalE

        // Gender decision — widened female thresholds
        // Use ?: return so detected is smart-cast to non-null Gender
        val detected: HindiTtsService.Gender = when {
            centroid > 1800f && femaleRatio > 0.40f -> HindiTtsService.Gender.FEMALE
            centroid > 1600f && femaleRatio > 0.50f -> HindiTtsService.Gender.FEMALE
            centroid > 1500f && femaleRatio > 0.60f -> HindiTtsService.Gender.FEMALE
            centroid < 1500f && maleE > femaleE * 1.2f -> HindiTtsService.Gender.MALE
            centroid < 1700f && maleE > femaleE * 1.6f -> HindiTtsService.Gender.MALE
            else -> return  // ambiguous frame — skip
        }

        history.addLast(detected)
        if (history.size > HIST) history.removeFirst()

        val fCount   = history.count { it == HindiTtsService.Gender.FEMALE }
        val majority = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE
                       else HindiTtsService.Gender.MALE

        if (majority != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majority
            HindiTtsService.spokenTokens.clear()
            Log.d(TAG, "Gender(audio)→$majority centroid=${centroid.toInt()} femaleRatio=${"%.2f".format(femaleRatio)} rms=${rms.toInt()}")
            CaptionLogger.log(TAG, "Gender→$majority (c=${centroid.toInt()} fr=${"%.2f".format(femaleRatio)})")
        }
    }

    // ── Cooley-Tukey radix-2 in-place FFT ────────────────────────────────────

    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var uRe = 1f; var uIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = uRe * re[i+k+len/2] - uIm * im[i+k+len/2]
                    val tIm = uRe * im[i+k+len/2] + uIm * re[i+k+len/2]
                    re[i+k+len/2] = re[i+k] - tRe
                    im[i+k+len/2] = im[i+k] - tIm
                    re[i+k] += tRe; im[i+k] += tIm
                    val nRe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
