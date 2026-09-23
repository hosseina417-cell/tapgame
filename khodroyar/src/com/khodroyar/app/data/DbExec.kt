package com.khodroyar.app.data

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.khodroyar.app.CrashGuard
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

    /** Runs [block] on the io thread, then [onDone] on the UI thread with its result.
     *  Any exception is logged via CrashGuard (never crashes the app); [onError] is optional.
     *  NOTE: [onDone] is last so call sites can use trailing-lambda syntax. */
    fun <T> ioThenUi(block: () -> T, onError: (Throwable) -> Unit = {}, onDone: (T) -> Unit) {
        io.execute {
            val result: T? = try { block() } catch (t: Throwable) {
                CrashGuard.log(t)
                main.post { onError(t) }
                return@execute
            }
            main.post { onDone(result as T) }
        }
    }

    fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    fun <T> async(activity: Activity, block: () -> T, onError: (Throwable) -> Unit = {}, onDone: (T) -> Unit = {}) {
        if (activity.isDestroyed || activity.isFinishing) return
        ioThenUi(block, { t ->
            if (!activity.isDestroyed && !activity.isFinishing) onError(t)
        }, { r ->
            if (!activity.isDestroyed && !activity.isFinishing) onDone(r)
        })
    }
}
