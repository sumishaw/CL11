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
 * LiveCaptionReader v6 — KEY FIX: use windows list, NOT rootInActiveWindow
 *
 * rootInActiveWindow returns the FOCUSED window (Termux, browser etc.)
 * We must iterate windows list and find the com.google.android.as window specifically.
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000

        // 500ms debounce — short enough to catch rapid dialogue,
        // long enough for Live Captions to finish word-correction on each line
        private const val DEBOUNCE_MS     = 500L

        // Force-send after this long even if Live Captions keeps updating
        // Prevents infinite deferral during fast continuous speech
        private const val MAX_WAIT_MS     = 3_000L

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var forceJob:      Job? = null   // fires after MAX_WAIT_MS regardless of updates
    private var translateJob:  Job? = null
    private var lastSentText   = ""
    private var lastHindiOut   = ""
    private var lastDetectedLang = ""        // track language switches
    private val translateQueue = LinkedBlockingQueue<String>(8)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 50
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            // Monitor confirmed Live Captions package only
            info.packageNames = LIVE_CAPTION_PACKAGES.toTypedArray()
        }

        startTranslateWorker()
        // Clear cached state from previous session
        lastSentText            = ""
        lastHindiOut            = ""
        lastCaptionText         = ""
        lastTranslatedSentence  = ""
        lastDetectedLang        = ""
        sentenceBuffer.clear()
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
        Log.i(TAG, "LiveCaptionReader v6 connected")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) return

        // CRITICAL FIX: find the com.google.android.as window from the windows list
        // Do NOT use rootInActiveWindow — that returns the focused window (wrong app)
        val captionText = readFromCaptionWindow() ?: return
        if (captionText == lastCaptionText) return
        lastCaptionText = captionText
        Log.d(TAG, "Caption: $captionText")
        scheduleTranslation(captionText)
    }

    private var lastTranslatedSentence = ""

    // Rolling buffer of recent caption sentences for context
    private val sentenceBuffer = ArrayDeque<String>()
    private val BUFFER_MAX = 3  // keep last 3 sentences for context

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (_: Exception) { return null }
        if (allWindows.isNullOrEmpty()) return null

        for (window in allWindows) {
            val root = try { window.root } catch (_: Exception) { continue } ?: continue
            val windowPkg = root.packageName?.toString() ?: ""

            if (windowPkg in LIVE_CAPTION_PACKAGES) {
                val textNodes = mutableListOf<String>()
                collectAllText(root, textNodes)
                root.recycle()

                val validTexts = textNodes
                    .filter { isValidCaption(it) }
                    .filter { !isStaticUiLabel(it) }

                if (validTexts.isEmpty()) return null

                // Take the longest text node — most complete Live Captions accumulation
                val fullText = validTexts.maxByOrNull { it.length } ?: return null

                // Extract all new complete sentences from the full text
                val newSentences = extractNewSentences(fullText)
                if (newSentences.isEmpty()) return null

                // Add new sentences to buffer
                for (s in newSentences) {
                    if (s !in sentenceBuffer) {
                        sentenceBuffer.addLast(s)
                    }
                }
                while (sentenceBuffer.size > BUFFER_MAX) sentenceBuffer.removeFirst()

                // Send joined context — CT2 translates short lines much better with context
                // e.g. "Yeah." alone → empty; "I understand. Yeah. Good." → हाँ। ठीक है।
                val contextText = sentenceBuffer.joinToString(" ").trim()
                if (contextText == lastTranslatedSentence) return null
                if (contextText.length < 3) return null

                lastTranslatedSentence = contextText
                return contextText
            } else {
                root.recycle()
            }
        }
        return null
    }

    private fun extractNewSentences(text: String): List<String> {
        // Split on sentence boundaries (Japanese + English + common punctuation)
        val raw = text.split(Regex("[。！？!?]+|(?<=[.])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 }

        if (raw.isEmpty()) {
            return if (text.trim().length >= 3) listOf(text.trim()) else emptyList()
        }

        // Return only sentences not already in our buffer
        return raw.filter { it !in sentenceBuffer }
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val lower = text.lowercase()
        // Drop Live Captions UI locale strings e.g. "English (United States)"
        // These always match the pattern: "Word (Word)" and are short
        if (text.matches(Regex("[A-Za-zÀ-ÿ ]+\\([A-Za-zÀ-ÿ ]+\\)")) && text.length < 60) return true
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("simplified") || lower.contains("traditional")) return true
        // Drop single words with no space — Live Captions UI buttons, not captions
        if (!text.contains(" ") && text.length < 15) return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 3 || text.length > 400) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.35) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        // Reject locale strings like "English (United States)"
        if (text.matches(Regex(".*\\(.*\\).*")) && text.length < 50) return false
        return true
    }

    private fun scheduleTranslation(text: String) {
        // Detect language switch — if script changes, clear dedup so first line
        // of new language is never silently dropped
        val scriptNow = detectScript(text)
        if (scriptNow != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            lastSentText           = ""
            lastTranslatedSentence = ""
            lastHindiOut           = ""
            sentenceBuffer.clear()
        }
        lastDetectedLang = scriptNow

        // Debounce: cancel existing 500ms timer, restart it
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            enqueueForTranslation(readFromCaptionWindow() ?: lastCaptionText)
        }

        // Force-send: if no force job is running, start one for MAX_WAIT_MS
        // This fires even if Live Captions keeps updating continuously,
        // so fast dialogue always produces output every ~3s at minimum
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()   // cancel the debounce — we're sending now
                enqueueForTranslation(readFromCaptionWindow() ?: lastCaptionText)
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel()
        forceJob = null
        if (text.isBlank() || text == lastSentText) return
        lastSentText = text
        if (translateQueue.size >= 8) translateQueue.poll()
        translateQueue.offer(text)
    }

    /** Coarse script detection for language-switch tracking only. */
    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = ord(c)
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        return "latin"
    }

    private fun ord(c: Char) = c.code

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank()) continue
                // Don't block on lastHindiOut equality — short repeated lines
                // (e.g. "हाँ।" "ठीक है।") are valid subtitles in rapid dialogue

                Log.i(TAG, "✓ ${text.take(40)} → ${hindi.take(40)}")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
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
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
