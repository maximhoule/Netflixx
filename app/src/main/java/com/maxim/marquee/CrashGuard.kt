package com.maxim.marquee

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps the app on screen when something goes wrong instead of closing to the TV's home screen.
 *
 * - An error on the main thread (screen code, a coroutine on the main thread) is caught, written
 *   to a log file, and the screen it happened on is rebuilt: the player closes back to the app,
 *   other screens are recreated in place.
 * - An error on a background thread only ends that piece of work.
 * - Several errors in a short time (a real loop of failures) are let through, so the app restarts
 *   cleanly instead of being stuck.
 */
object CrashGuard {
    private var current: Activity? = null
    private val recent = ArrayDeque<Long>()
    @Volatile private var guarding = false

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { current = a }
            override fun onActivityPaused(a: Activity) { if (current === a) current = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) { if (current === a) current = null }
        })

        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (thread === Looper.getMainLooper().thread) {
                // Hand it to the guarded loop below (it's running on this thread right now);
                // before that loop has started, the normal crash handling applies.
                if (guarding) throw e else system?.uncaughtException(thread, e)
            } else {
                record(app, e)
                // A background job failed: that job ends, the app carries on.
                Log.w("Marquee", "background error in ${thread.name}", e)
                if (e is OutOfMemoryError) trimCaches(app)
            }
        }

        // Run the main thread's message loop inside a try/catch, for good.
        Handler(Looper.getMainLooper()).post {
            guarding = true
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    record(app, e)
                    if (tooMany()) {
                        guarding = false
                        system?.uncaughtException(Thread.currentThread(), e)
                        return@post
                    }
                    if (e is OutOfMemoryError) trimCaches(app)
                    recover()
                }
            }
        }
    }

    /** More than 4 errors in 20 seconds: something is stuck failing. */
    private fun tooMany(): Boolean {
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 20_000) recent.removeFirst()
        return recent.size > 4
    }

    private fun recover() {
        val a = current ?: return
        try {
            if (a is PlayerActivity) a.finish() else a.recreate()
        } catch (e: Throwable) {
            Log.w("Marquee", "couldn't recover the screen", e)
        }
    }

    private fun trimCaches(app: Application) {
        runCatching { coil.Coil.imageLoader(app).memoryCache?.clear() }
        System.gc()
    }

    private fun record(app: Application, e: Throwable) {
        Log.e("Marquee", "caught error", e)
        runCatching {
            val f = app.filesDir.resolve("last_error.txt")
            f.writeText("${java.util.Date()}\n${Log.getStackTraceString(e)}")
        }
    }
}
