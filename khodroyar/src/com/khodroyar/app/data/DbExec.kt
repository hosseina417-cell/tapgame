package com.khodroyar.app.data

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single background executor for all DB work + helpers to hop back to the UI thread.
 */
object DbExec {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "khodroyar-db").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun runOnIo(block: () -> Unit) {
        io.execute(block)
    }

    /** Runs [block] on the io thread, then [onDone] on the UI thread with its result. */
    fun <T> ioThenUi(block: () -> T, onDone: (T) -> Unit) {
        io.execute {
            val result = try { block() } catch (t: Throwable) {
                main.post { throw t }
                return@execute
            }
            main.post { onDone(result) }
        }
    }

    fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    fun <T> async(activity: Activity, block: () -> T, onDone: (T) -> Unit = {}) {
        if (activity.isDestroyed || activity.isFinishing) return
        ioThenUi(block, { r ->
            if (!activity.isDestroyed && !activity.isFinishing) onDone(r)
        })
    }
}
