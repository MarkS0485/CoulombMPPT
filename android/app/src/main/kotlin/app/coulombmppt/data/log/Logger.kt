package app.coulombmppt.data.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Append-only file logger. One singleton, hand-wired in ServiceLocator.init,
// reachable from anywhere as `AppLogger.i("…")`.
//
// File layout: one file *per launch*, named `coulombmppt-yyyyMMdd-HHmmss.log`,
// living in the app's internal filesDir. On `init()` we scan the directory
// and delete anything older than RETENTION_DAYS so debugging stays focused
// on recent runs without growing unbounded.
//
// Writes happen on a single background thread so callers never block on I/O.
// The current run's file is opened lazily on the first message — there's no
// in-session rotation; the per-launch boundary IS the rotation.
object AppLogger {

    private const val FILE_PREFIX = "coulombmppt-"
    private const val FILE_SUFFIX = ".log"
    private const val RETENTION_DAYS = 7L
    private const val DEFAULT_TAIL_BYTES = 256L * 1024L          // 256 KB

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK)

    private lateinit var dir: File
    private lateinit var sessionFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<String>(capacity = 512)
    @Volatile private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        dir = context.applicationContext.filesDir
        sessionFile = File(dir, "$FILE_PREFIX${fileStamp.format(Date())}$FILE_SUFFIX")
        pruneOldLogs()
        initialised = true
        scope.launch { writer() }
        i("AppLogger", "init dir=${dir.absolutePath} session=${sessionFile.name}")
    }

    fun v(tag: String, msg: String) = enqueue('V', tag, msg)
    fun i(tag: String, msg: String) = enqueue('I', tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = enqueue('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = enqueue('E', tag, msg, t)

    /** Plain unprefixed line (used for hex traces). */
    fun raw(line: String) {
        if (initialised) channel.trySend(line)
    }

    /** Block until any pending writes flush. Cheap; only used on share. */
    suspend fun flush() {
        val marker = "—flush ${System.nanoTime()}—"
        channel.send(marker)
        kotlinx.coroutines.delay(20)
    }

    /** The file the current launch is writing to. */
    fun currentLogFile(): File = sessionFile

    /** Every retained log file, newest first. Useful for a session picker. */
    fun sessionFiles(): List<File> {
        if (!initialised) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Tail of the current launch's log. Memory-efficient even for large files. */
    fun readAll(maxBytes: Long = DEFAULT_TAIL_BYTES): String {
        if (!initialised) return ""
        return runCatching { sessionFile.tail(maxBytes) }.getOrDefault("")
    }

    /** Concatenated tail of *all* retained session files, newest last (so the
     *  current run shows at the bottom — matches how a typical log reader
     *  scrolls). Capped at `maxBytes` total. */
    fun readAllSessions(maxBytes: Long = DEFAULT_TAIL_BYTES): String {
        if (!initialised) return ""
        val files = sessionFiles().reversed()      // oldest first for top-to-bottom reading
        if (files.isEmpty()) return ""
        // Distribute the budget evenly; the current run typically dominates,
        // so give it a bigger slice if there's leftover.
        val perFile = (maxBytes / files.size).coerceAtLeast(8_192L)
        val sb = StringBuilder()
        for ((idx, f) in files.withIndex()) {
            if (idx > 0) sb.append("\n--- ").append(f.name).append(" ---\n")
            else         sb.append("--- ").append(f.name).append(" ---\n")
            sb.append(runCatching { f.tail(perFile) }.getOrDefault(""))
        }
        // Final hard cap in case sum overshoots maxBytes after concatenation.
        return if (sb.length > maxBytes) sb.toString().takeLast(maxBytes.toInt()) else sb.toString()
    }

    /** Delete the current session file's contents (write a fresh header).
     *  Older retained files are kept for context. Returns true on success. */
    fun clearCurrent(): Boolean = runCatching {
        if (initialised && sessionFile.exists()) {
            sessionFile.writeText("")
            i("AppLogger", "logs cleared by user")
        }
    }.isSuccess

    /** Delete *every* retained log file. Intended for a "wipe all" UI action. */
    fun clearAll(): Boolean = runCatching {
        if (!initialised) return@runCatching
        for (f in sessionFiles()) f.delete()
        // Recreate the current session file so subsequent writes have a target.
        i("AppLogger", "all logs cleared by user")
    }.isSuccess

    // --- internals ---

    private fun enqueue(level: Char, tag: String, msg: String, t: Throwable? = null) {
        // Also mirror to Logcat for IDE convenience.
        when (level) {
            'V' -> Log.v(tag, msg, t)
            'I' -> Log.i(tag, msg, t)
            'W' -> Log.w(tag, msg, t)
            'E' -> Log.e(tag, msg, t)
        }
        if (!initialised) return
        val line = buildString {
            append(ts.format(Date()))
            append(' ').append(level).append(' ')
            append(tag).append(": ").append(msg)
            if (t != null) {
                append('\n').append(Log.getStackTraceString(t))
            }
        }
        channel.trySend(line)
    }

    private suspend fun writer() {
        channel.consumeEach { line ->
            runCatching {
                PrintWriter(FileWriter(sessionFile, true)).use { it.println(line) }
            }
        }
    }

    /** Delete log files older than RETENTION_DAYS. Quietly ignores I/O errors —
     *  if a file can't be deleted (held by another process, weird FS state)
     *  we'd rather keep logging than crash. */
    private fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        } ?: return
        for (f in candidates) {
            if (f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    /** Last `bytes` bytes of the file as a String. Uses RandomAccessFile so we
     *  don't allocate the full file even when it's many MB. */
    private fun File.tail(bytes: Long): String {
        if (!exists()) return ""
        val len = length()
        if (len == 0L) return ""
        if (len <= bytes) return readText()
        return RandomAccessFile(this, "r").use { raf ->
            raf.seek(len - bytes)
            val buf = ByteArray(bytes.toInt())
            val read = raf.read(buf)
            String(buf, 0, read)
        }
    }
}
