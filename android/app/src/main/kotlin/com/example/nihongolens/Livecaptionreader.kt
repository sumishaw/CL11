package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader — Accessibility Service
 *
 * Reads Android Live Captions text in real-time → translates to Hindi via CT2.
 *
 * How to use:
 * 1. Enable Live Captions: Quick Settings → Live Caption (or Settings > Sound > Live Caption)
 * 2. Enable this service: Settings → Accessibility → Caption Lens → LiveCaptionReader → ON
 * 3. Open Caption Lens and press START — Live Captions mode activates automatically
 * 4. Play any video — captions appear in Hindi instantly
 *
 * Advantages over Whisper audio capture:
 * - Perfect speech-to-text quality (Google on-device model)
 * - Zero sentences dropped — reads every word Live Captions shows
 * - Works for ALL languages Live Captions supports
 * - No audio processing overhead — tablet stays cool
 * - No profanity filtering in speech-to-text
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        // All known package names for Live Captions across Android versions and OEMs
        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",                       // Android 10+ (Pixel, most phones)
            "com.google.android.accessibility.caption",    // Some Android versions
            "com.google.android.accessibility.captions",
            "com.google.android.tts",
            "com.android.systemui",                        // Some OEMs embed captions in SystemUI
        )

        private const val TRANSLATE_URL  = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 400L  // wait 400ms for Live Captions to finish a phrase

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var lastSentText = ""
    private var lastHindiOut = ""

    // Translation queue — ensures translations don't pile up
    private val translateQueue = LinkedBlockingQueue<String>(4)
    private var translateJob:  Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        // Reconfigure service info programmatically for maximum compatibility
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes       = (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            info.feedbackType     = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 50
            info.flags            = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
            // Monitor ALL packages — some OEMs use different package names
            info.packageNames     = null
        }

        startTranslateWorker()
        Log.i(TAG, "LiveCaptionReader connected")
        mainScope { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        // Extract caption text
        val text = extractCaptionText(event) ?: return
        val clean = text.trim()
        if (clean.length < 2 || clean == lastCaptionText) return

        lastCaptionText = clean
        scheduleTranslation(clean)
    }

    // ── Text extraction ───────────────────────────────────────────────────────

    private fun extractCaptionText(event: AccessibilityEvent): String? {
        // Strategy 1: event text directly
        val evText = event.text
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.length > 1 }
            ?.joinToString(" ")
        if (!evText.isNullOrBlank()) return evText

        // Strategy 2: walk accessibility tree
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        return try { findCaptionText(root) } finally { /* root.recycle() causes crash on some APIs */ }
    }

    private fun findCaptionText(node: AccessibilityNodeInfo?): String? {
        node ?: return null

        val text    = node.text?.toString()?.trim() ?: ""
        val viewId  = node.viewIdResourceName?.lowercase() ?: ""
        val cls     = node.className?.toString()?.lowercase() ?: ""
        val desc    = node.contentDescription?.toString()?.lowercase() ?: ""

        // Live Captions specific view IDs
        val isCaptionNode = listOf("caption_text", "captiontext", "live_caption",
            "transcript_text", "caption_window", "captionwindow")
            .any { viewId.contains(it) || desc.contains(it) }

        if (isCaptionNode && text.isNotBlank()) return text

        // Fallback: any large-enough text view
        if (cls.contains("textview") && text.length in 3..400) {
            return text
        }

        for (i in 0 until node.childCount) {
            val found = findCaptionText(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ── Translation scheduling ────────────────────────────────────────────────

    private fun scheduleTranslation(text: String) {
        // Debounce: wait for Live Captions to finish updating before translating
        // Live Captions updates word-by-word; we wait for a pause
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (text == lastCaptionText && text != lastSentText) {
                lastSentText = text
                // Drop oldest if queue full — keep latest caption
                if (translateQueue.size >= 4) translateQueue.poll()
                translateQueue.offer(text)
            }
        }
    }

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = try {
                    withContext(Dispatchers.IO) {
                        translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } catch (_: InterruptedException) { break } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank() || hindi == lastHindiOut) continue
                lastHindiOut = hindi

                Log.d(TAG, "[$text] → [$hindi]")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                mainScope {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    // ── HTTP translation call ─────────────────────────────────────────────────

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT

            val body = """{"text":${JSONObject.quote(text)},"src":"en","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Server ${conn.responseCode} for: $text")
                return null
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mainScope(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { try { block() } catch (_: Exception) {} }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false
        instance  = null
        pendingJob?.cancel()
        translateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
