package com.szurgot.prenotasniper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends attempt records to evidence/log.txt and saves PNG screenshots.
 * The timestamped log doubles as evidence for a "denial of justice" court filing.
 */
object Logger {
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)
    private val fileTs = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.UK)

    private fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "evidence")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun log(ctx: Context, line: String) {
        val stamped = "${ts.format(Date())}  $line\n"
        try {
            FileOutputStream(File(dir(ctx), "log.txt"), true).use { it.write(stamped.toByteArray()) }
        } catch (_: Exception) {
        }
    }

    /** Draw the WebView to a bitmap and save it as timestamped proof of the state. */
    fun screenshot(ctx: Context, view: View, tag: String) {
        try {
            if (view.width == 0 || view.height == 0) return
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val f = File(dir(ctx), "${fileTs.format(Date())}_$tag.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (_: Exception) {
        }
    }

    fun evidencePath(ctx: Context): String = dir(ctx).absolutePath
}
