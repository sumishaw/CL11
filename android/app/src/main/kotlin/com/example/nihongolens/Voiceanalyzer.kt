package com.example.nihongolens

import kotlin.math.*

/**
 * VoiceAnalyzer — Real-time vocal metrics engine
 *
 * Measures all 4 categories of vocal voice metrics from raw PCM audio:
 *   Category 1: F0/Pitch — mean, std, floor, ceiling
 *   Category 2: Spectral — centroid, flux, ZCR (MFCC proxy)
 *   Category 3: Voice quality — jitter, shimmer, HNR
 *   Category 4: Temporal — syllable rate, speech ratio, RMS energy
 *
 * Publishes a VoiceProfile every ~2s to HindiTtsService which maps
 * each metric directly to Android TTS parameters (pitch, rate, SSML).
 *
 * Runs on the same raw PCM stream as GenderAnalyzer — no extra capture needed.
 */
object VoiceAnalyzer {

    private const val TAG       = "VoiceAnalyzer"
    private const val SAMPLE_RATE = 44100f
    private const val FRAME_SIZE  = 2048   // ~46ms at 44100Hz
    private const val HOP_SIZE    = 512    // ~12ms hop

    // ── Published VoiceProfile ────────────────────────────────────────────────
    @Volatile var current = VoiceProfile()
    @Volatile var ready   = false           // true once first profile computed

    // ── Internal accumulators ─────────────────────────────────────────────────
    private val f0History      = ArrayDeque<Float>(200)
    private val rmsHistory     = ArrayDeque<Float>(200)
    private val centroidHistory= ArrayDeque<Float>(200)
    private val zcRateHistory  = ArrayDeque<Float>(200)
    private val fluxHistory    = ArrayDeque<Float>(200)

    // Jitter/shimmer: need consecutive F0 and amplitude values
    private val f0Consecutive  = ArrayDeque<Float>(50)
    private val ampConsecutive = ArrayDeque<Float>(50)

    private var prevSpectrum   = FloatArray(FRAME_SIZE / 2)
    private var frameCount     = 0
    private var voicedFrames   = 0
    private var totalFrames    = 0
    private var updateCounter  = 0

    // Syllable rate: count energy peaks (nuclei)
    private var prevRms        = 0f
    private var syllableCount  = 0
    private var syllableWindowMs = 0L
    private var windowStartMs  = System.currentTimeMillis()

    // ── Entry point: called with each raw PCM short[] frame ──────────────────
    fun processFrame(pcm: ShortArray, rmsIn: Float, f0In: Float) {
        frameCount++
        totalFrames++
        val isVoiced = rmsIn > 150f && f0In > 60f

        // ── Category 1: F0 tracking ───────────────────────────────────────────
        if (isVoiced) {
            f0History.addLast(f0In)
            if (f0History.size > 200) f0History.removeFirst()
            f0Consecutive.addLast(f0In)
            if (f0Consecutive.size > 50) f0Consecutive.removeFirst()
            voicedFrames++
        }

        // ── Category 4: RMS tracking ──────────────────────────────────────────
        rmsHistory.addLast(rmsIn)
        if (rmsHistory.size > 200) rmsHistory.removeFirst()

        // Syllable detection: RMS peak above threshold after a dip
        val rmsThreshold = 300f
        if (rmsIn > rmsThreshold && prevRms <= rmsThreshold) syllableCount++
        prevRms = rmsIn

        // ── Category 2: Spectral analysis from PCM ────────────────────────────
        if (pcm.isNotEmpty() && pcm.size >= FRAME_SIZE) {
            val frame = FloatArray(FRAME_SIZE) { i ->
                // Hann window
                val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (FRAME_SIZE - 1)))
                pcm[i % pcm.size].toFloat() * w
            }

            // Simple magnitude spectrum via DFT (first 256 bins for efficiency)
            val BINS = 256
            val spectrum = FloatArray(BINS)
            for (k in 0 until BINS) {
                var re = 0f; var im = 0f
                val step = FRAME_SIZE / BINS
                for (n in 0 until FRAME_SIZE step step) {
                    val angle = 2f * PI.toFloat() * k * n / FRAME_SIZE
                    re += frame[n] * cos(angle)
                    im += frame[n] * sin(angle)
                }
                spectrum[k] = sqrt(re * re + im * im)
            }

            // Spectral centroid (Category 2)
            val totalPower = spectrum.sum().coerceAtLeast(1f)
            val centroid = spectrum.mapIndexed { k, mag ->
                k.toFloat() * (SAMPLE_RATE / 2f / BINS) * mag
            }.sum() / totalPower
            centroidHistory.addLast(centroid)
            if (centroidHistory.size > 200) centroidHistory.removeFirst()

            // Spectral flux: diff from previous frame (Category 2)
            val flux = spectrum.zip(prevSpectrum.take(BINS).toList())
                .sumOf { (a, b) -> max(a - b, 0f).toDouble() }.toFloat()
            fluxHistory.addLast(flux)
            if (fluxHistory.size > 200) fluxHistory.removeFirst()
            prevSpectrum = spectrum + FloatArray(FRAME_SIZE / 2 - BINS)

            // Zero-crossing rate (MFCC brightness proxy, Category 2)
            var zc = 0
            for (i in 1 until pcm.size.coerceAtMost(FRAME_SIZE)) {
                if ((pcm[i-1] >= 0) != (pcm[i] >= 0)) zc++
            }
            val zcRate = zc.toFloat() / FRAME_SIZE
            zcRateHistory.addLast(zcRate)
            if (zcRateHistory.size > 200) zcRateHistory.removeFirst()
        }

        // ── Category 3: Amplitude for shimmer ────────────────────────────────
        if (isVoiced) {
            ampConsecutive.addLast(rmsIn)
            if (ampConsecutive.size > 50) ampConsecutive.removeFirst()
        }

        // ── Publish profile every ~2s (200 frames × ~10ms) ───────────────────
        updateCounter++
        if (updateCounter >= 200) {
            updateCounter = 0
            computeAndPublish()
        }
    }

    // ── Compute all metrics and publish VoiceProfile ──────────────────────────
    private fun computeAndPublish() {
        val f0List = f0History.toList().filter { it > 0f }
        val rmsList = rmsHistory.toList()
        if (f0List.size < 10) return   // not enough data yet

        // ── Category 1: F0 metrics ────────────────────────────────────────────
        val meanF0   = f0List.average().toFloat()
        val medianF0 = f0List.sorted().let { it[it.size / 2] }
        val f0Std    = sqrt(f0List.map { (it - meanF0).pow(2) }.average()).toFloat()
        val f0Floor  = f0List.filter { it > 50f }.minOrNull() ?: meanF0
        val f0Ceil   = f0List.maxOrNull() ?: meanF0

        // ── Category 2: Spectral metrics ─────────────────────────────────────
        val centroid   = centroidHistory.toList().average().toFloat()
        val spectFlux  = fluxHistory.toList().average().toFloat()
        val zcRate     = zcRateHistory.toList().average().toFloat()

        // ── Category 3: Voice quality metrics ────────────────────────────────
        // Jitter: mean absolute F0 difference between consecutive voiced frames
        val f0Con = f0Consecutive.toList()
        val jitter = if (f0Con.size > 2) {
            val diffs = (1 until f0Con.size).map { abs(f0Con[it] - f0Con[it-1]) }
            (diffs.average() / meanF0.coerceAtLeast(1f) * 100f).toFloat()
        } else 0.5f  // default healthy jitter

        // Shimmer: mean absolute amplitude difference between consecutive voiced frames
        val ampCon = ampConsecutive.toList()
        val shimmer = if (ampCon.size > 2) {
            val meanAmp = ampCon.average().toFloat()
            val diffs = (1 until ampCon.size).map { abs(ampCon[it] - ampCon[it-1]) }
            (diffs.average() / meanAmp.coerceAtLeast(1f) * 100f).toFloat()
        } else 3.0f  // default healthy shimmer

        // HNR approximation: ratio of energy in harmonic vs noise bands
        // Estimated from ZCR: low ZCR + voiced = harmonic; high ZCR = noisy
        val hnr = if (zcRate < 0.08f && meanF0 > 60f) 22f
                  else if (zcRate < 0.15f) 17f
                  else 11f  // high ZCR = noisy/breathy

        // ── Category 4: Temporal metrics ─────────────────────────────────────
        val nowMs = System.currentTimeMillis()
        val windowSec = ((nowMs - windowStartMs) / 1000f).coerceAtLeast(0.5f)
        val syllableRate = syllableCount / windowSec  // syllables per second
        val speechRatio  = if (totalFrames > 0) voicedFrames.toFloat() / totalFrames else 0.5f
        val rmsEnergy    = rmsList.average().toFloat()

        // Reset syllable counter
        syllableCount    = 0
        syllableWindowMs = nowMs
        windowStartMs    = nowMs
        voicedFrames     = 0
        totalFrames      = 0

        val profile = VoiceProfile(
            meanF0       = meanF0,
            medianF0     = medianF0,
            f0Std        = f0Std,
            f0Floor      = f0Floor,
            f0Ceiling    = f0Ceil,
            spectralCentroid = centroid,
            spectralFlux = spectFlux,
            zcRate       = zcRate,
            jitter       = jitter,
            shimmer      = shimmer,
            hnr          = hnr,
            syllableRate = syllableRate,
            speechRatio  = speechRatio,
            rmsEnergy    = rmsEnergy
        )

        current = profile
        ready   = true

        // Publish to HindiTtsService for TTS parameter mapping
        HindiTtsService.applyVoiceProfile(profile)

        CaptionLogger.log(TAG,
            "PROFILE f0=${meanF0.toInt()}±${f0Std.toInt()}Hz " +
            "floor=${f0Floor.toInt()} ceil=${f0Ceil.toInt()} " +
            "jitter=${"%.1f".format(jitter)}% shimmer=${"%.1f".format(shimmer)}% " +
            "hnr=${"%.0f".format(hnr)}dB " +
            "syllRate=${"%.1f".format(syllableRate)}/s " +
            "centroid=${centroid.toInt()}Hz zcr=${"%.3f".format(zcRate)}")
    }

    fun reset() {
        f0History.clear(); rmsHistory.clear()
        centroidHistory.clear(); zcRateHistory.clear(); fluxHistory.clear()
        f0Consecutive.clear(); ampConsecutive.clear()
        prevSpectrum = FloatArray(FRAME_SIZE / 2)
        frameCount = 0; voicedFrames = 0; totalFrames = 0
        updateCounter = 0; syllableCount = 0
        prevRms = 0f; ready = false
        windowStartMs = System.currentTimeMillis()
        CaptionLogger.log(TAG, "reset")
    }
}

// ── VoiceProfile: all measured vocal metrics ─────────────────────────────────
data class VoiceProfile(
    // Category 1: F0/Pitch
    val meanF0        : Float = 0f,
    val medianF0      : Float = 0f,
    val f0Std         : Float = 0f,   // expressiveness: high=dynamic, low=monotone
    val f0Floor       : Float = 0f,   // lowest pitch boundary
    val f0Ceiling     : Float = 0f,   // highest pitch boundary

    // Category 2: Spectral/Resonance
    val spectralCentroid: Float = 2000f,  // brightness: high=bright/young, low=dark/bass
    val spectralFlux    : Float = 0f,     // articulation speed
    val zcRate          : Float = 0.1f,   // zero-crossing rate (noise proxy)

    // Category 3: Voice Quality
    val jitter   : Float = 0.5f,   // % F0 cycle variation: 0.2-1.0=healthy, >1.5=raspy
    val shimmer  : Float = 3.0f,   // % amplitude variation: <5=healthy, >8=breathy
    val hnr      : Float = 20f,    // dB: >20=clean/resonant, <15=breathy/whispery

    // Category 4: Temporal
    val syllableRate : Float = 4.0f,   // syllables/second: slow<3, normal 4-5, fast>6
    val speechRatio  : Float = 0.6f,   // % time actually speaking
    val rmsEnergy    : Float = 1000f   // volume/energy level
)
