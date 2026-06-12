package com.rfsat.dms.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DBM file logger. Writes timestamped entries to app-private storage
 * (filesDir/logs/dbm-YYYYMMDD.log), mirrors to logcat, and installs an
 * uncaught-exception handler so any crash leaves a full stack trace in the
 * log file before the process dies.
 *
 * Files rotate daily; logs older than 7 days are pruned on init.
 * View/share from the app: header "Logs" button.
 */
object DLog {

    private const val TAG = "DBM"
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lock = Any()
    @Volatile private var ready = false

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        ready = true
        // prune old logs
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e(TAG, "FATAL crash in thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        i(TAG, "==== DBM logger initialised ====")
    }

    fun currentLogFile(): File = File(dir, "dbm-${dayFmt.format(Date())}.log")

    fun i(tag: String, msg: String) = write("I", tag, msg, null).also { Log.i(tag, msg) }
    fun w(tag: String, msg: String, e: Throwable? = null) =
        write("W", tag, msg, e).also { Log.w(tag, msg, e) }
    fun e(tag: String, msg: String, e: Throwable? = null) =
        write("E", tag, msg, e).also { Log.e(tag, msg, e) }

    /** Tail of today's log for the in-app viewer. */
    fun tail(maxChars: Int = 12_000): String {
        val f = currentLogFile()
        if (!f.exists()) return "(log empty)"
        val s = f.readText()
        return if (s.length <= maxChars) s else "…" + s.takeLast(maxChars)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (!ready) return
        runCatching {
            synchronized(lock) {
                currentLogFile().appendText(buildString {
                    append(fmt.format(Date())).append(' ').append(level)
                        .append('/').append(tag).append(": ").append(msg).append('\n')
                    t?.let { append(Log.getStackTraceString(it)).append('\n') }
                })
            }
        }
    }
}
