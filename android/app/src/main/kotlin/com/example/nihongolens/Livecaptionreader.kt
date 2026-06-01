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
import java.util.concurrent.atomic.AtomicLong

/**
 * LiveCaptionReader
 *
 * Reads Live Captions window → translates via whisper_server → Hindi overlay.
 *
 * Architecture:
 *  - onAccessibilityEvent (event-driven) + Watchdog (1500ms poll) = never misses updates
 *  - readFromCaptionWindow: suffix-diff tracks only new content since last read
 *  - Debounce 500ms + ForceJob 3s: balances word-correction wait vs. continuous speech
 *  - Normalized dedup: prevents same text translated multiple times
 *  - Startup grace: ignores first 1s of events to avoid burst of identical lines
 *  - FIFO queue (unbounded): never drops a translation
 *  - Language switch detection: clears stale state immediately
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG             = "LCReader"
        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 500L
        private const val MAX_WAIT_MS     = 3_000L
        private const val WATCHDOG_MS     = 1_500L
        // Ignore events for this many ms after service connect — avoids startup burst
        private const val STARTUP_GRACE_MS = 1_000L

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        @Volatile var isRunning = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var forceJob:     Job? = null
    private var translateJob: Job? = null
    private var watchdogJob:  Job? = null

    // FIFO translation queue — unbounded, never drops
    private val translateQueue = LinkedBlockingQueue<String>()

    // Dedup state
    private var lastEnqueuedNorm  = ""
    private var lastSentText      = ""

    // Language tracking
    private var lastDetectedLang  = ""

    // Window reader state
    private var lastRawCaption    = ""
    private var lastSentText2     = ""
    private var captionWasVisible = false

    // Startup grace — ignore events fired immediately on connect
    private var startupTime = 0L

    // Diagnostics
    private val eventsReceived  = AtomicLong(0)
    private val enqueued        = AtomicLong(0)
    private val translated      = AtomicLong(0)
    private val translateErrors = AtomicLong(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance     = this
        isRunning    = true
        startupTime  = System.currentTimeMillis()

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            info.packageNames = null   // events from ALL packages (required for WINDOWS_CHANGED)
        }

        resetState()
        startTranslateWorker()
        startWatchdog()
        startStatsLogger()

        CaptionLogger.log(TAG, "=== Connected | WATCHDOG=${WATCHDOG_MS}ms DEBOUNCE=${DEBOUNCE_MS}ms ===")
        scope.launch(Dispatchers.Main) { MainActivity.instance?.onLiveCaptionReaderConnected() }
    }

    override fun onInterrupt() {
        CaptionLogger.log(TAG, "!!! onInterrupt !!!")
    }

    override fun onDestroy() {
        CaptionLogger.log(TAG, "=== Destroyed | enqueued=${enqueued.get()} " +
            "translated=${translated.get()} errors=${translateErrors.get()} ===")
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        watchdogJob?.cancel(); translateJob?.cancel()
        translateQueue.clear()
        scope.cancel()
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
        OverlayService.updateText("", "")
        CaptionLogger.stop()
        super.onDestroy()
    }

    // ── Event-driven read path ────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        // Skip events during startup grace period to avoid burst of identical lines
        if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) return

        val n = eventsReceived.incrementAndGet()
        if (n % 100L == 0L) CaptionLogger.log(TAG, "Events: $n")

        val text = readFromCaptionWindow() ?: return
        CaptionLogger.log(TAG, "EV '${text.take(60)}'")
        scheduleTranslation(text)
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            CaptionLogger.log(TAG, "Watchdog started")
            var tick = 0L
            while (isActive && isRunning) {
                delay(WATCHDOG_MS)
                tick++
                // Skip watchdog during startup grace too
                if (System.currentTimeMillis() - startupTime < STARTUP_GRACE_MS) continue

                val text = withContext(Dispatchers.Main) {
                    try { readFromCaptionWindow() } catch (e: Exception) {
                        CaptionLogger.log(TAG, "WD exception: ${e.message}"); null
                    }
                } ?: run {
                    if (tick % 20L == 0L) CaptionLogger.log(TAG,
                        "WD tick=$tick null | visible=$captionWasVisible rawLen=${lastRawCaption.length}")
                    return@run null
                } ?: continue

                if (text == lastSentText) continue   // watchdog dedup

                CaptionLogger.log(TAG, "WD tick=$tick '${text.take(60)}'")
                scheduleTranslation(text)
            }
            CaptionLogger.log(TAG, "Watchdog stopped")
        }
    }

    // ── Stats logger ──────────────────────────────────────────────────────────

    private fun startStatsLogger() {
        scope.launch {
            while (isActive && isRunning) {
                delay(30_000L)
                CaptionLogger.log(TAG, "STATS events=${eventsReceived.get()} " +
                    "enqueued=${enqueued.get()} translated=${translated.get()} " +
                    "errors=${translateErrors.get()} qSize=${translateQueue.size} " +
                    "visible=$captionWasVisible lang=$lastDetectedLang " +
                    "lastSent='${lastSentText.take(40)}'")
            }
        }
    }

    // ── Window reader ─────────────────────────────────────────────────────────

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (e: Exception) {
            CaptionLogger.log(TAG, "windows() exception: ${e.message}"); return null
        }

        // Find Live Captions window
        var captionRoot: AccessibilityNodeInfo? = null
        allWindows?.forEach { window ->
            if (captionRoot != null) return@forEach
            val root = try { window.root } catch (_: Exception) { null } ?: return@forEach
            if (root.packageName?.toString() in LIVE_CAPTION_PACKAGES)
                captionRoot = root
            else
                root.recycle()
        }

        // Window gone → silence → reset state for clean next session
        if (captionRoot == null) {
            if (captionWasVisible) {
                captionWasVisible = false
                lastRawCaption    = ""
                lastSentText2     = ""
                CaptionLogger.log(TAG, "LC gone → reset")
            }
            return null
        }

        // Collect text nodes
        val nodes = mutableListOf<String>()
        collectAllText(captionRoot, nodes)
        captionRoot.recycle()

        val fullText = nodes
            .filter  { isValidCaption(it) }
            .filter  { !isStaticUiLabel(it) }
            .maxByOrNull { it.length }
            ?.trim() ?: run {
                val raw = nodes.joinToString(" | ") { "'${it.take(40)}'" }
                CaptionLogger.log(TAG, "No valid nodes (${nodes.size}): $raw")
                return null
            }

        // Fresh session after silence
        if (!captionWasVisible) {
            captionWasVisible = true
            lastRawCaption    = ""
            lastSentText2     = ""
            CaptionLogger.log(TAG, "LC appeared: '${fullText.take(60)}'")
        }

        // No change
        if (fullText == lastRawCaption) return null

        // Extract new suffix
        val prev = lastSentText2
        lastRawCaption = fullText

        val newPart = when {
            prev.isNotEmpty() && fullText.startsWith(prev) ->
                fullText.substring(prev.length).trim()
            else -> {
                CaptionLogger.log(TAG, "Non-append: prevLen=${prev.length} newLen=${fullText.length}")
                fullText.takeLast(150).trim()
            }
        }
        lastSentText2 = fullText

        // Minimum new content: 1 char for CJK (each char = word), 4 for Latin
        val isCjk = newPart.any { it.code in 0x3000..0x9FFF || it.code in 0xAC00..0xD7AF }
        val minNew = if (isCjk) 1 else 4
        if (newPart.length < minNew) {
            CaptionLogger.log(TAG, "newPart short (${newPart.length}): '$newPart'")
            return null
        }

        // Return last 150 chars of full text for CT2 context
        return if (fullText.length > 150) fullText.takeLast(150).trim() else fullText.trim()
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun normalize(t: String) = t.trim().replace(Regex("\\s+"), " ")

    private fun scheduleTranslation(text: String) {
        // Detect language switch → clear stale state
        val script = detectScript(text)
        if (script != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            CaptionLogger.log(TAG, "LANG SWITCH $lastDetectedLang→$script")
            lastSentText      = ""
            lastEnqueuedNorm  = ""
            lastRawCaption    = ""
            lastSentText2     = ""
            translateQueue.clear()
        }
        lastDetectedLang = script

        // Debounce: restart 500ms timer on every new event
        // (lets LC finish word-correction before we translate)
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            enqueueForTranslation(lastSentText2.ifBlank { text })
        }

        // Force-send: guarantee translation every MAX_WAIT_MS during continuous speech
        // Only one force job at a time
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                enqueueForTranslation(lastSentText2.ifBlank { text })
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel(); forceJob = null
        if (text.isBlank()) return

        val norm = normalize(text)
        if (norm == lastEnqueuedNorm) {
            CaptionLogger.log(TAG, "SKIP dup: '${text.take(50)}'")
            return
        }

        lastEnqueuedNorm = norm
        lastSentText     = text
        translateQueue.offer(text)   // FIFO — never drop
        enqueued.incrementAndGet()
        CaptionLogger.log(TAG, "ENQ #${enqueued.get()} q=${translateQueue.size} '${text.take(60)}'")
    }

    // ── Translation worker ────────────────────────────────────────────────────

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            CaptionLogger.log(TAG, "Worker started")
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                CaptionLogger.log(TAG, "DEQUEUE '${text.take(60)}'")
                val t0    = System.currentTimeMillis()
                val hindi = translate(text)
                val ms    = System.currentTimeMillis() - t0

                if (hindi.isNullOrBlank()) {
                    translateErrors.incrementAndGet()
                    CaptionLogger.log(TAG, "NULL/BLANK in ${ms}ms for '${text.take(40)}'")
                    // Clear dedup so this text can be retried if LC shows it again
                    if (lastEnqueuedNorm == normalize(text)) lastEnqueuedNorm = ""
                    continue
                }

                translated.incrementAndGet()
                CaptionLogger.log(TAG, "OK #${translated.get()} ${ms}ms '${text.take(30)}'→'${hindi.take(30)}'")

                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text
                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
            CaptionLogger.log(TAG, "Worker stopped")
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) { CaptionLogger.log(TAG, "HTTP $code"); return null }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "translate EX: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetState() {
        lastSentText      = ""; lastEnqueuedNorm  = ""
        lastDetectedLang  = ""; lastRawCaption    = ""
        lastSentText2     = ""; captionWasVisible = false
        SpeechCaptureService.latestHindi    = ""
        SpeechCaptureService.latestEnglish  = ""
        SpeechCaptureService.latestOriginal = ""
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val t = node.text?.toString()?.trim() ?: ""
        if (t.isNotBlank()) out.add(t)
        val d = node.contentDescription?.toString()?.trim() ?: ""
        if (d.isNotBlank() && d != t) out.add(d)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val l = text.lowercase()
        if (l.contains("united states") || l.contains("united kingdom")) return true
        if (l.contains("simplified")    || l.contains("traditional"))    return true
        if (text == "Hide" || text == "Settings" || text == "Feedback")   return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        if (text.count { it.isLetter() } < 2) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        if (text.contains("http") || text.contains("www.")) return false
        return true
    }

    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = c.code
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        return if (text.any { it.isLetter() && it.code in 0x00C0..0x024F })
            "latin_foreign" else "latin_en"
    }
}
