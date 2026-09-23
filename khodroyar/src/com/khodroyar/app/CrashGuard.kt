package com.khodroyar.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches unexpected crashes: writes the stack trace to files/crash-last.txt
 * and shows a short toast, so a bug never dies silently.
 */
object CrashGuard {

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())

    fun install(application: Application) {
        app = application
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { log(e) } catch (_: Throwable) {}
            try {
                main.post {
                    try {
                        Toast.makeText(
                            app,
                            app.getString(R.string.crash_toast, e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Throwable) {}
                }
                Thread.sleep(250)
            } catch (_: Throwable) {}
            prior?.uncaughtException(thread, e)
        }
    }

    /** Append a non-fatal error to the crash log (used by background workers). */
    fun log(t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            logFile().appendText(
                "\n==== " + System.currentTimeMillis() + " / " + t.javaClass.name + " ====\n" + sw
            )
        } catch (_: Throwable) {}
    }

    fun lastCrashReport(): String? {
        val f = logFile()
        return if (f.exists() && f.length() > 0) f.readText().take(4000) else null
    }

    private fun logFile(): File = File(app.filesDir.apply { mkdirs() }, "crash-last.txt")
}
