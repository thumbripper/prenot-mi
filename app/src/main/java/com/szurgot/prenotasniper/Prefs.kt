package com.szurgot.prenotasniper

import android.content.Context

/** Simple SharedPreferences-backed config. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prenota", Context.MODE_PRIVATE)

    var serviceUrl: String
        get() = sp.getString("serviceUrl", "https://prenotami.esteri.it/Services") ?: ""
        set(v) = sp.edit().putString("serviceUrl", v).apply()

    var loginUrl: String
        get() = sp.getString("loginUrl", "https://prenotami.esteri.it/Login") ?: ""
        set(v) = sp.edit().putString("loginUrl", v).apply()

    /** Keyword matched against each service table row to find the right BOOK link. */
    var keyword: String
        get() = sp.getString("keyword", "cittadin") ?: ""
        set(v) = sp.edit().putString("keyword", v).apply()

    /**
     * Preferred: a substring of the target service's booking URL, e.g.
     * "/Services/Booking/1234". Language-independent. Set by "Pick service".
     * Takes priority over [keyword] when non-empty.
     */
    var bookingId: String
        get() = sp.getString("bookingId", "") ?: ""
        set(v) = sp.edit().putString("bookingId", v).apply()

    /** How many times to re-attempt on a "sold out" result before giving up. */
    var retries: Int
        get() = sp.getInt("retries", 40)
        set(v) = sp.edit().putInt("retries", v).apply()

    /** Delay between re-attempts, milliseconds. */
    var retryIntervalMs: Int
        get() = sp.getInt("retryIntervalMs", 400)
        set(v) = sp.edit().putInt("retryIntervalMs", v).apply()

    /** Minutes before release time to fire the "open the app" notification. */
    var prewarmMinutes: Int
        get() = sp.getInt("prewarmMinutes", 3)
        set(v) = sp.edit().putInt("prewarmMinutes", v).apply()

    /** Release time hour/minute in Europe/London. */
    var releaseHour: Int
        get() = sp.getInt("releaseHour", 17)
        set(v) = sp.edit().putInt("releaseHour", v).apply()

    var releaseMinute: Int
        get() = sp.getInt("releaseMinute", 0)
        set(v) = sp.edit().putInt("releaseMinute", v).apply()

    /** Comma list of release weekdays (java.time DayOfWeek values 1=Mon..7=Sun). */
    var releaseDays: String
        get() = sp.getString("releaseDays", "1,3") ?: "1,3"
        set(v) = sp.edit().putString("releaseDays", v).apply()

    var armed: Boolean
        get() = sp.getBoolean("armed", false)
        set(v) = sp.edit().putBoolean("armed", v).apply()
}
