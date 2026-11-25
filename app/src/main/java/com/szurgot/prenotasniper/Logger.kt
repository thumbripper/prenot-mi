package com.szurgot.prenotasniper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Appends attempt records to evidence/log.txt, saves PNG screenshots and HTML snapshots.
 * The timestamped log doubles as evidence for a "denial of justice" court filing.
 *
 * Thread-safe: log() is called from the UI thread, JS-bridge threads, the NTP thread and the
 * notification listener. Heavy work (PNG encode, HTML write) runs on a single background thread.
 */
object Logger {
    private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val fileTs = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault())
    private val io = Executors.newSingleThreadExecutor()
    private const val MAX_LOG_BYTES = 5_000_000L

    private fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "evidence")
        if (!d.exists()) d.mkdirs()
        return d
    }

    @Synchronized
    fun log(ctx: Context, line: String) {
        val stamped = "${ts.format(Instant.now())}  $line\n"
        try {
            val f = File(dir(ctx), "log.txt")
            if (f.length() > MAX_LOG_BYTES) {
                f.renameTo(File(dir(ctx), "log_${fileTs.format(Instant.now())}.txt"))
            }
            FileOutputStream(f, true).use { it.write(stamped.toByteArray()) }
        } catch (_: Exception) {
        }
    }

    /** Draw [view] to a bitmap (must be called on the UI thread); encode + save off-thread. */
    fun screenshot(ctx: Context, view: View, tag: String) {
        try {
            if (view.width == 0 || view.height == 0) return
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val f = File(dir(ctx), "${fileTs.format(Instant.now())}_${safe(tag)}.png")
            io.execute {
                try {
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                } catch (_: Exception) {
                } finally {
                    bmp.recycle()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Save a page snapshot to evidence/html/<ts>_<tag>.html (off-thread). */
    fun saveHtml(ctx: Context, tag: String, html: String) {
        val name = "${fileTs.format(Instant.now())}_${safe(tag)}.html"
        io.execute {
            try {
                val d = File(dir(ctx), "html")
                if (!d.exists()) d.mkdirs()
                File(d, name).writeText(html)
            } catch (_: Exception) {
            }
        }
        log(ctx, "HTML SAVED html/$name (${html.length} chars)")
    }

    private fun safe(tag: String) = tag.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)

    fun evidencePath(ctx: Context): String = dir(ctx).absolutePath

    /** Return the last [maxChars] characters of the log for in-app display. */
    fun readTail(ctx: Context, maxChars: Int = 6000): String {
        return try {
            val f = File(dir(ctx), "log.txt")
            if (!f.exists()) return "(log is empty — no attempts yet)"
            val text = f.readText()
            if (text.length <= maxChars) text else text.substring(text.length - maxChars)
        } catch (e: Exception) {
            "(could not read log: $e)"
        }
    }
}
