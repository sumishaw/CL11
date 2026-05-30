package com.example.nihongolens

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * CaptionLogger — in-memory ring buffer + logcat.
 *
 * No file I/O, no permissions needed.
 * Last 500 lines kept in memory, readable via getLogs MethodChannel call.
 * Every line also goes to logcat under tag "CaptionLens".
 */
object CaptionLogger {

    private const val LOGCAT_TAG = "CaptionLens"
    private const val MAX_LINES  = 500

    private val fmt    = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = LinkedList<String>()
    private val lock   = Any()

    fun init(context: Context) {
        log("Logger", "=== Caption Lens logger ready (in-memory, ${MAX_LINES} lines) ===")
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.d(LOGCAT_TAG, "[$tag] $msg")
        synchronized(lock) {
            buffer.addLast(line)
            if (buffer.size > MAX_LINES) buffer.removeFirst()
        }
    }

    fun logState(tag: String, key: String, value: String) = log(tag, "$key=$value")

    /** Return last N lines joined by newline — called from MethodChannel getLogs */
    fun getRecentLines(n: Int = MAX_LINES): String {
        synchronized(lock) {
            val lines = if (buffer.size <= n) buffer.toList()
                        else buffer.toList().takeLast(n)
            return lines.joinToString("\n")
        }
    }

    /** Clear the buffer — called from MethodChannel clearLogs */
    fun clearLines() {
        synchronized(lock) { buffer.clear() }
        log("Logger", "Log buffer cleared")
    }

    fun stop() = log("Logger", "=== Logger stopped ===")

    fun getLogPath(): String = "in-memory (use Log tab in app)"
}
