package com.szurgot.prenotasniper

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Holds the most recently captured OTP so MainActivity can pick it up. */
object OtpHolder {
    @Volatile var code: String? = null
    @Volatile var at: Long = 0
    fun set(c: String) { code = c; at = System.currentTimeMillis() }
    fun clear() { code = null; at = 0 }
}

/**
 * Reads OTP codes out of incoming notifications (e.g. the Gmail notification the
 * Prenot@mi OTP email produces). Requires the user to grant "Notification access".
 */
class OtpListenerService : NotificationListenerService() {

    private val digits = Regex("\\d{4,8}")

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            sbn ?: return
            val pkg = sbn.packageName ?: ""
            val ex = sbn.notification?.extras ?: return
            val blob = listOf(
                "android.title", "android.text", "android.bigText",
                "android.subText", "android.summaryText"
            ).mapNotNull { ex.getCharSequence(it)?.toString() }.joinToString("  ")
            if (blob.isBlank()) return

            val low = blob.lowercase()
            val relevant = pkg.contains("gm", true) || pkg.contains("gmail", true) ||
                pkg.contains("email", true) || low.contains("otp") ||
                low.contains("prenot") || low.contains("codice") ||
                low.contains("esteri") || low.contains("one-time") || low.contains("verification")
            if (!relevant) return

            // Prefer a code right after an OTP keyword, e.g. "OTP Code:621941".
            val keyed = Regex("(?:otp|code|codice)\\D{0,12}(\\d{4,8})", RegexOption.IGNORE_CASE)
                .find(blob)?.groupValues?.getOrNull(1)
            val found = digits.findAll(blob).map { it.value }.toList()
            val code = keyed
                ?: found.firstOrNull { it.length == 6 }
                ?: found.firstOrNull { it.length in 5..6 }
                ?: found.firstOrNull() ?: return

            OtpHolder.set(code)
            Logger.log(applicationContext, "OTP captured from $pkg: $code")
        } catch (_: Exception) {
        }
    }
}
