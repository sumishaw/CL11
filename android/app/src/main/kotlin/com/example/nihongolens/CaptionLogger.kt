package com.example.nihongolens

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * CaptionLogger — writes diagnostic logs to a file.
 *
 * Tries paths in order:
 *   1. /storage/emulated/0/caption_lens_log.txt  (visible in file manager)
 *   2. /sdcard/Android/data/com.example.nihongolens/files/caption_lens_log.txt
 *   3. <app internal files>/caption_lens_log.txt  (always works, needs adb to read)
 *
 * The actual path is printed to logcat on startup — check:
 *   logcat -s CaptionLens | grep "Log file"
 *
 * Also mirrors every line to logcat under tag "CaptionLens" so
 *   logcat -s CaptionLens
 * always works as a backup even if file writing fails.
 */
object CaptionLogger {

    private const val LOGCAT_TAG = "CaptionLens"
    private const val MAX_QUEUE  = 500

    private val fmt    = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val queue  = LinkedBlockingQueue<String>(MAX_QUEUE)
    private var writer:  PrintWriter? = null
    private var logFile: File?        = null
    @Volatile private var running     = false

    fun init(context: Context) {
        if (running) return
        running = true

        // Fixed log path — always writes here
        val candidates = listOf(
            File("/storage/emulated/0/Captionlogger/caption_lens_log.txt"),
        )

        for (candidate in candidates) {
            try {
                candidate.parentFile?.mkdirs()
                val fw = FileWriter(candidate, false)  // false = overwrite on start
                writer  = PrintWriter(fw)
                logFile = candidate
                break
            } catch (_: Exception) {}
        }

        // Background writer thread — drains queue to file
        thread(isDaemon = true, name = "CaptionLogger") {
            while (running || queue.isNotEmpty()) {
                val line = try {
                    queue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) { null } ?: continue
                try {
                    writer?.println(line)
                    writer?.flush()
                } catch (_: Exception) {}
            }
        }

        log("Logger", "=== Caption Lens log started ===")
        if (logFile != null)
            log("Logger", "Log file: ${logFile!!.absolutePath}")
        else
            log("Logger", "WARNING: could not open log file — logcat only")
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.d(LOGCAT_TAG, "[$tag] $msg")
        queue.offer(line)
    }

    fun logState(tag: String, key: String, value: String) = log(tag, "$key=$value")

    fun stop() {
        log("Logger", "=== Caption Lens log stopped ===")
        running = false
        Thread.sleep(300)
        writer?.close()
    }

    fun getLogPath(): String = logFile?.absolutePath ?: "logcat only (no file)"
}
