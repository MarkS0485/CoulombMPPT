package app.coulombmppt.data.log

import android.util.Log
import kotlinx.coroutines.runBlocking

// Captures uncaught exceptions before the process dies so the stack
// trace makes it into the per-launch log file. Without this, fatals
// only show up in Logcat, which the user can't see once they've
// disconnected adb — and "the app just disappeared" is the worst kind
// of bug report to debug.
//
// Install once from CoulombMpptApp.onCreate AFTER AppLogger.init.
object CrashHandler {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                AppLogger.e("CRASH", "uncaught on thread ${thread.name}", t)
                // Best-effort flush — the writer thread is on IO, and we
                // have ~maybe a few ms before Android kills the process.
                runBlocking { AppLogger.flush() }
            }
            // Always chain so AndroidRuntime still gets to log it and the
            // system can show its standard "App has stopped" dialog.
            try {
                previous?.uncaughtException(thread, t)
            } catch (chained: Throwable) {
                Log.e("CrashHandler", "chained handler threw", chained)
            }
        }
        AppLogger.i("CrashHandler", "installed")
    }
}
