package com.szurgot.prenotasniper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Process-wide guard so only ONE snipe runs at a time, even if a stale MainActivity instance is
 * still around (v13 evidence: 4–6 concurrent snipes sharing one server session; a second
 * "send new code" invalidated the first OTP on 9 Nov).
 */
object SnipeGuard {
    @Volatile private var owner = 0
    @Volatile private var ownerSince = 0L
    /** One OTP request per snipe — every extra request invalidates the previous code. */
    val otpRequested = AtomicBoolean(false)

    @Synchronized fun acquire(id: Int): Boolean {
        val now = System.currentTimeMillis()
        if (owner != 0 && owner != id && now - ownerSince < 10 * 60_000) return false
        owner = id; ownerSince = now
        return true
    }

    @Synchronized fun release(id: Int) { if (owner == id) owner = 0 }
    fun ownerId() = owner
}

class MainActivity : AppCompatActivity() {

    companion object {
        private val instanceCounter = AtomicInteger(0)

        /** evaluateJavascript returns the JS value JSON-encoded; unwrap a string result. */
        fun decodeJs(raw: String?): String {
            if (raw == null || raw == "null") return ""
            return try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim('"') }
        }

        private const val PRENOTAMI = "prenotami.esteri.it"
        private const val CAPTURE_WINDOW_MS = 3 * 60_000L
    }

    private val instanceId = instanceCounter.incrementAndGet()

    private lateinit var container: FrameLayout
    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private val sessions = mutableListOf<SnipeSession>()
    private val primary: WebView get() = sessions[0].web

    @Volatile private var ntpOffsetMs: Long = 0
    private var sniping = false
    private var wave = 0
    private var englishEnsured = false
    private val englishTriedUrls = HashSet<String>()

    /** Stage-timing origin: NTP-corrected release instant (scheduled) or start time (manual). */
    private var t0Ms = 0L
    private var winner: SnipeSession? = null
    /** Until this time, every prenotami page load is snapshotted (HTML + screenshot). */
    private var captureUntil = 0L
    private var forwardClickedAt = 0L
    private var otpGen = 0
    private var lastAlert = ""
    private var lastAlertAt = 0L

    private var countdownRunnable: Runnable? = null
    private var fireRunnable: Runnable? = null
    private var releaseMs = 0L
    private var prewarmed = false
    private var sessionCheckPending = false
    private var ringtone: Ringtone? = null

    private val autoBooker = AutoBooker()
    private val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss")

    // Outcome-polling cadence after a click.
    private val POLL_INTERVAL_MS = 150L
    private val POLL_MAX_TICKS = 45          // ~6.75s total -> TIMEOUT
    private val BOOK_SETTLE_TICKS = 20       // booking URL without OTP markers for ~3s

    private fun nowC() = System.currentTimeMillis() + ntpOffsetMs
    private fun log(s: String) = Logger.log(this, s)
    private fun stage(name: String, extra: String = "") {
        val t = if (t0Ms > 0) "T+${nowC() - t0Ms}ms" else "T=?"
        log("STAGE $name $t $extra".trim())
    }
    private fun capturing() = System.currentTimeMillis() < captureUntil

    /** One concurrent booking session: a WebView plus its own poll/attempt state. */
    inner class SnipeSession(var web: WebView, val index: Int) {
        var resultHandled = false
        var consecutiveBook = 0
        var pollTick = 0
        var awaitingLoad = false
        var done = false
        var lastRes = ""
        var clickedAt = 0L
        var servicesReloadAt = 0L

        val poll = object : Runnable {
            override fun run() {
                if (!sniping || resultHandled) return
                pollTick++
                if (servicesReloadAt > 0 && System.currentTimeMillis() - servicesReloadAt > 700) {
                    sessionOutcome(this@SnipeSession, "SOLD_OUT_RELOAD", "click reloaded /Services, no known popup text"); return
                }
                if (pollTick >= POLL_MAX_TICKS) { sessionOutcome(this@SnipeSession, "TIMEOUT"); return }
                web.evaluateJavascript(Js.STATE) { raw ->
                    if (!sniping || resultHandled) return@evaluateJavascript
                    val r = decodeJs(raw)
                    val kind = r.substringBefore('|')
                    val detail = r.substringAfter('|', "")
                    when (kind) {
                        "SOLD" -> sessionOutcome(this@SnipeSession, "SOLD_OUT", "popup: $detail")
                        "SUCCESS" -> sessionOutcome(this@SnipeSession, "SUCCESS")
                        "ERR5XX" -> sessionOutcome(this@SnipeSession, "HTTP5XX", detail)
                        "BOOK" -> {
                            consecutiveBook++
                            if (consecutiveBook >= BOOK_SETTLE_TICKS)
                                sessionOutcome(this@SnipeSession, "SUCCESS", "booking URL held ${BOOK_SETTLE_TICKS} ticks without OTP markers")
                        }
                        else -> consecutiveBook = 0
                    }
                }
                ui.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        fun attempt() {
            resultHandled = false; consecutiveBook = 0; pollTick = 0; done = false
            clickedAt = 0; servicesReloadAt = 0
            web.evaluateJavascript(Js.click(prefs.keyword, prefs.bookingId), null)
            ui.postDelayed(poll, 250)
        }

        fun stopPoll() = ui.removeCallbacks(poll)
    }

    // ---------------------------------------------------------------- Lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        log("LIFECYCLE #$instanceId onCreate v${BuildConfig.VERSION_NAME} saved=${savedInstanceState != null} " +
            "autostart=${intent?.getBooleanExtra("autostart", false)} guardOwner=${SnipeGuard.ownerId()}")

        status = findViewById(R.id.txtStatus)
        container = findViewById(R.id.webContainer)

        CookieManager.getInstance().setAcceptCookie(true)
        for (i in 0 until prefs.parallelSessions) {
            val s = SnipeSession(newWebView(), i)
            sessions.add(s)
            setupWebView(s)
        }
        primary.bringToFront() // primary on top for login / navigation / handover

        findViewById<Button>(R.id.btnSnipe).setOnClickListener { startSnipe(manual = true) }
        findViewById<Button>(R.id.btnSnipe).setOnLongClickListener { enumerateServices(); true }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSnipe("Stopped by user.") }
        findViewById<Button>(R.id.btnStop).setOnLongClickListener {
            val w = frontWeb()
            if ((w.url ?: "").contains("/BookingCalendar", true)) { autoBooker.start(w, "manual"); toast("Auto-book started on this calendar") }
            else toast("Open the booking calendar first, then long-press STOP to auto-book.")
            true
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener { primary.loadUrl(prefs.loginUrl); primary.bringToFront() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnSettings).setOnLongClickListener { dumpPage(); true }
        status.setOnLongClickListener { showDiagnostics(); true }

        requestNotifPermission()
        refreshNtp()

        // Optional override for local testing: adb ... -e url file:///android_asset/mock/services.html
        val override = intent?.getStringExtra("url")
        val start = when {
            override != null -> { prefs.serviceUrl = override; override }
            savedInstanceState == null -> prefs.loginUrl
            else -> prefs.serviceUrl
        }
        primary.loadUrl(start)

        setStatus("Ready · ${sessions.size} session(s). Log in, pick service (long-press SNIPE), then Arm or Snipe.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        log("LIFECYCLE #$instanceId onNewIntent autostart=${intent.getBooleanExtra("autostart", false)}")
        intent.getStringExtra("url")?.let { prefs.serviceUrl = it; primary.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        log("LIFECYCLE #$instanceId onResume")
        if (intent?.getBooleanExtra("autostart", false) == true) {
            intent.removeExtra("autostart")
            if (!sniping) primary.loadUrl(prefs.serviceUrl)
        }
        maybeStartCountdown()
    }

    override fun onPause() {
        super.onPause()
        log("LIFECYCLE #$instanceId onPause sniping=$sniping")
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        log("LIFECYCLE #$instanceId onDestroy finishing=$isFinishing changingConfig=$isChangingConfigurations")
        sniping = false
        otpGen++
        autoBooker.stop("activity destroyed")
        cancelCountdown()
        sessions.forEach { it.stopPoll(); it.resultHandled = true }
        ui.removeCallbacksAndMessages(null)
        SnipeGuard.release(instanceId)
        try { ringtone?.stop() } catch (_: Exception) {}
        sessions.forEach { s ->
            try { container.removeView(s.web); s.web.destroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    private fun newWebView(): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(this)
    }

    private fun frontWeb(): WebView = winner?.web ?: primary

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(s: SnipeSession) {
        val web = s.web
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = true
            allowFileAccess = true   // bundled mock pages (file:///android_asset/mock/...)
            allowContentAccess = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.addJavascriptInterface(Bridge(s), "Android")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, Js.documentStart(prefs.netCapture), setOf("https://$PRENOTAMI"))
            } catch (e: Exception) { log("document-start hook failed: $e") }
        } else if (s.index == 0) log("document-start scripts not supported by this WebView — no net/modal hook")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && (url.contains(PRENOTAMI) || capturing())) log("PAGE START[${s.index}]: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: return
                if (view !== s.web) return
                onPageLoaded(s, u)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val msg = "LOAD ERROR[${s.index}] ${error?.errorCode} '${error?.description}' url=${request.url}"
                    Log.e("PrenotaSniper", msg)
                    log(msg)
                    if (s.index == 0) ui.post { setStatus("Load failed: ${error?.description} (${request.url})") }
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    log("HTTP ERROR[${s.index}] ${errorResponse?.statusCode} url=${request.url}")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                log("SSL ERROR[${s.index}] $error")
                handler?.cancel()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                log("RENDERER GONE[${s.index}] crashed=${detail?.didCrash()} — rebuilding WebView")
                ui.post { rebuildWebView(s) }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val msg = message ?: ""
                val m = msg.lowercase()
                log("JS ALERT[${s.index}]: $msg")
                result?.confirm()
                ui.post {
                    lastAlert = msg; lastAlertAt = System.currentTimeMillis()
                    if (sniping && (m.contains("sold out") || m.contains("high demand") || m.contains("esaurit") ||
                            m.contains("all appointments") || m.contains("currently unavailable"))) {
                        sessionOutcome(s, "SOLD_OUT", "alert: $msg")
                    }
                    if (m.contains("incorrect code") || m.contains("codice errato") || m.contains("codice non")) {
                        stage("OTP_INCORRECT", msg)
                        alarm("otp-incorrect")
                        setStatus("OTP rejected ('$msg'). Request a new code and enter it by hand.")
                    }
                    if (capturing()) { snap("alert"); s.web.evaluateJavascript(Js.snapshotHtml("alert"), null) }
                }
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                log("JS CONFIRM[${s.index}]: $message (capturing=${capturing()})")
                if (capturing() || autoBooker.running) {
                    result?.confirm()
                    ui.post { lastAlert = "CONFIRM: ${message ?: ""}"; lastAlertAt = System.currentTimeMillis() }
                    return true
                }
                return false
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                log("JS PROMPT[${s.index}]: $message default=$defaultValue")
                return false
            }

            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                val msg = cm?.message() ?: return true
                if (s.index == 0 && !isConsoleNoise(msg)) log("JS CONSOLE: ${msg.take(300)} @${cm.lineNumber()}")
                return true
            }
        }
    }

    private fun isConsoleNoise(m: String): Boolean =
        m.startsWith("%c") || m.contains("Permissions-Policy") || m.contains("matomo") ||
            m.contains("Content Security Policy") || m.contains("Unrecognized feature") ||
            m.startsWith("Error: x") || m.contains("OTS parsing") || m.startsWith("_") ||
            m.startsWith("[object") || m.trim().startsWith(": 1") || m.contains("console.group") ||
            m == "Error" || m.contains("No available adapters") || m.contains("violates the document")

    private fun rebuildWebView(s: SnipeSession) {
        try { container.removeView(s.web); s.web.destroy() } catch (_: Exception) {}
        s.web = newWebView()
        setupWebView(s)
        if (s.index == 0 || s === winner) s.web.bringToFront()
        s.web.loadUrl(prefs.serviceUrl)
        setStatus("Browser crashed and was restarted (session ${s.index}). Check you're still logged in.")
        alarm("renderer-crash")
    }

    /** Central page-load handler (onPageFinished). */
    private fun onPageLoaded(s: SnipeSession, u: String) {
        log("PAGE LOADED[${s.index}]: $u")
        val onPrenotami = u.contains(PRENOTAMI, true)
        val path = Uri.parse(u).path ?: ""

        if (onPrenotami && (capturing() || sniping)) {
            s.web.evaluateJavascript(Js.PAGE_SUMMARY) { r -> log("PAGE[${s.index}] ${decodeJs(r)}") }
        }
        if (capturing() && (s === winner || s.index == 0)) {
            snap("load_" + path.trim('/').replace('/', '_').ifEmpty { "home" })
            s.web.evaluateJavascript(Js.snapshotHtml("load_" + path.trim('/').replace('/', '_')), null)
        }

        // T-60s session check (pre-warm load of /Services)
        if (sessionCheckPending && s.index == 0) {
            sessionCheckPending = false
            val expired = u.contains("ReturnUrl", true) || u.contains("iam.esteri.it", true) ||
                !u.contains("/Services", true)
            if (expired) {
                stage("SESSION_EXPIRED", u)
                alarm("login", 6000)
                setStatus("⚠ LOG IN NOW — session expired before release ($u)")
                primary.bringToFront()
            } else log("SESSION OK (pre-warm) $u")
        }

        // After FORWARD: calendar or bounce.
        if (forwardClickedAt > 0 && s === winner && System.currentTimeMillis() - forwardClickedAt < 60_000) {
            val isServicesList = path.equals("/Services", true) || path.equals("/Services/", true)
            if (isServicesList) onBounce(s)
        }

        if (u.contains("/BookingCalendar", ignoreCase = true)) onCalendarLoaded(s, u)

        // English: only on the prenotami portal, never on the iam.esteri.it login flow.
        if (!sniping && s.index == 0 && prefs.forceEnglish && !englishEnsured && onPrenotami) ensureEnglish(s.web, u)

        if (!sniping) return
        val isServices = u.contains("/Services", ignoreCase = true) && !u.contains("/Booking/")
        if (s.awaitingLoad && isServices) {
            s.awaitingLoad = false
            s.attempt()
        } else if (isServices && s.clickedAt > 0 && !s.resultHandled && s.servicesReloadAt == 0L) {
            s.servicesReloadAt = System.currentTimeMillis() // click bounced back to the list = sold out
        }
    }

    inner class Bridge(private val session: SnipeSession) {
        @JavascriptInterface
        fun onResult(status: String) { ui.post { sessionOutcome(session, status) } }

        @JavascriptInterface
        fun onClicked(lookupMs: Int) {
            ui.post {
                session.clickedAt = System.currentTimeMillis()
                if (wave <= 1) stage("CLICK", "session ${session.index} link found after ${lookupMs}ms")
            }
        }

        @JavascriptInterface
        fun onDump(text: String) {
            log("PAGE DUMP:\n$text")
            ui.post { toast("Page dumped to log.txt") }
        }

        @JavascriptInterface
        fun onServices(json: String) { ui.post { showServicesDialog(json) } }

        @JavascriptInterface
        fun onHtml(tag: String, html: String) { Logger.saveHtml(this@MainActivity, tag, html) }

        @JavascriptInterface
        fun onNet(json: String) { log("NET[${session.index}] ${json.take(4500)}") }

        @JavascriptInterface
        fun onModal(text: String) {
            log("MODAL[${session.index}] ${text.take(800)}")
            if (capturing()) ui.post { session.web.evaluateJavascript(Js.snapshotHtml("modal"), null) }
        }
    }

    // ---------------------------------------------------------------- Snipe (fan-out)

    private fun startSnipe(manual: Boolean) {
        if (!SnipeGuard.acquire(instanceId)) {
            log("SNIPE SKIPPED on #$instanceId — instance #${SnipeGuard.ownerId()} is already sniping")
            return
        }
        cancelCountdown()
        sniping = true
        wave = 0
        winner = null
        forwardClickedAt = 0
        otpGen++
        autoBooker.stop("new snipe")
        SnipeGuard.otpRequested.set(false)
        t0Ms = if (manual || releaseMs == 0L) nowC() else releaseMs
        val target = if (prefs.bookingId.isNotEmpty()) prefs.bookingId else prefs.keyword
        log("SNIPE START (${if (manual) "manual" else "scheduled"}) #$instanceId target='$target' sessions=${sessions.size} ntpOffset=${ntpOffsetMs}ms")
        fireWave()
    }

    private fun stopSnipe(msg: String) {
        sniping = false
        sessions.forEach { it.stopPoll(); it.resultHandled = true }
        cancelCountdown()
        autoBooker.stop("stop")
        otpGen++
        SnipeGuard.release(instanceId)
        log("SNIPE STOP: $msg")
        setStatus(msg)
    }

    /** Fire one wave: every session clicks the target at (near) the same instant. */
    private fun fireWave() {
        if (!sniping) return
        wave++
        setStatus("Sniping… wave $wave/${prefs.retries} × ${sessions.size} session(s)")
        sessions.forEach { s ->
            s.done = false
            s.resultHandled = false
            val cur = s.web.url ?: ""
            if (cur.contains("/Services", ignoreCase = true) && !cur.contains("/Booking/")) {
                s.attempt()
            } else {
                s.awaitingLoad = true
                s.web.loadUrl(prefs.serviceUrl) // onPageFinished(services) -> s.attempt()
                // Safety: if the load never reaches /Services (expired session, error page),
                // don't stall the whole wave forever.
                val thisWave = wave
                ui.postDelayed({
                    if (sniping && wave == thisWave && s.awaitingLoad && !s.resultHandled) {
                        s.awaitingLoad = false
                        sessionOutcome(s, "LOAD_TIMEOUT", "never reached /Services (url=${s.web.url})")
                    }
                }, 8000)
            }
        }
    }

    /** Per-session result within a wave; first SUCCESS wins, otherwise wait for the wave to finish. */
    private fun sessionOutcome(s: SnipeSession, res: String, detail: String = "") {
        if (!sniping || s.resultHandled) return
        s.resultHandled = true
        s.stopPoll()
        s.lastRes = res
        log("wave $wave session ${s.index} -> $res ${detail.take(300)}".trim())

        if (res == "SUCCESS") { winSession(s, detail); return }

        s.done = true
        if (sessions.all { it.done }) {
            snap("wave${wave}_${res.take(8)}")
            when {
                sessions.all { it.lastRes == "NO_SERVICE" } ->
                    stopSnipe("Service not found. Long-press SNIPE to pick the service, or set the keyword in SETTINGS.")
                sessions.any { it.lastRes == "LOAD_TIMEOUT" } && sessions.all { it.lastRes == "LOAD_TIMEOUT" } && wave >= 3 -> {
                    alarm("login", 5000)
                    stopSnipe("Pages never loaded the services list 3× — logged out? Log in and retry.")
                }
                wave < prefs.retries -> {
                    val delay = if (sessions.any { it.lastRes == "HTTP5XX" }) 300L else prefs.retryIntervalMs.toLong()
                    ui.postDelayed({ fireWave() }, delay)
                }
                else -> stopSnipe("No slot after ${prefs.retries} waves × ${sessions.size} session(s). Logged as evidence.")
            }
        }
    }

    private fun winSession(s: SnipeSession, note: String) {
        sniping = false
        sessions.forEach { it.stopPoll(); it.resultHandled = true }
        cancelCountdown()
        winner = s
        s.web.bringToFront() // show the session that landed a slot
        captureUntil = System.currentTimeMillis() + CAPTURE_WINDOW_MS
        stage("BOOKING_PAGE", "session ${s.index} wave $wave $note".trim())
        snap("SUCCESS")
        s.web.evaluateJavascript(Js.snapshotHtml("booking_page"), null)
        s.web.evaluateJavascript(Js.BODY_TEXT) { r -> log("BOOKING PAGE TEXT:\n${decodeJs(r).take(3000)}") }
        setStatus("★ BOOKING PAGE (session ${s.index}) — OTP being handled; watch the screen. ★")
        vibrate(longArrayOf(0, 400, 200, 400))
        startOtpAssist(s)
    }

    /** Whole-app-window screenshot (buttons + banner + page), saved to evidence. */
    private fun snap(tag: String) = Logger.screenshot(this, window.decorView, tag)

    // ---------------------------------------------------------------- OTP fast path

    private fun startOtpAssist(win: SnipeSession) {
        if (!prefs.autoOtp) { alarm("manual-otp"); return }
        val gen = ++otpGen
        val startedAt = System.currentTimeMillis()
        OtpHolder.clear()
        if (!notificationAccessEnabled()) log("Auto-OTP: notification access NOT granted — code won't be auto-read.")

        // 1) Click SEND NEW CODE as soon as it exists — exactly once per snipe.
        val reqDeadline = startedAt + 8000
        fun tryRequest() {
            if (gen != otpGen) return
            if (SnipeGuard.otpRequested.get()) { log("OTP request skipped: already requested in this snipe"); return }
            win.web.evaluateJavascript(Js.requestOtp(false)) { r ->
                if (gen != otpGen) return@evaluateJavascript
                val res = decodeJs(r)
                if (res.startsWith("FOUND")) {
                    if (SnipeGuard.otpRequested.compareAndSet(false, true)) {
                        win.web.evaluateJavascript(Js.TURNSTILE) { t -> log("Turnstile token length at OTP request: ${decodeJs(t)}") }
                        win.web.evaluateJavascript(Js.requestOtp(true)) { r2 -> stage("OTP_REQUESTED", decodeJs(r2)) }
                    }
                } else if (System.currentTimeMillis() < reqDeadline) {
                    ui.postDelayed({ tryRequest() }, 100)
                } else {
                    log("OTP request: no SEND NEW CODE button after 8s ($res)")
                    alarm("otp-button")
                    setStatus("Couldn't find SEND NEW CODE — tap it yourself!")
                }
            }
        }
        tryRequest()

        // 2) Wait for the code from the Gmail notification, then fill + submit immediately.
        val poll = object : Runnable {
            override fun run() {
                if (gen != otpGen) return
                val c = OtpHolder.code
                if (c != null && OtpHolder.at >= startedAt) {
                    stage("OTP_RECEIVED", c)
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("OTP", c))
                    } catch (_: Exception) {}
                    setStatus("★ OTP $c — filling + FORWARD… ★")
                    fillAndSubmit(win, c, gen, System.currentTimeMillis())
                    return
                }
                if (System.currentTimeMillis() - startedAt < 180_000) ui.postDelayed(this, 150)
                else {
                    stage("OTP_TIMEOUT")
                    alarm("otp-timeout")
                    setStatus("No OTP email within 3 min. Enter it by hand if it arrives.")
                }
            }
        }
        ui.postDelayed(poll, 150)
    }

    private fun fillAndSubmit(win: SnipeSession, code: String, gen: Int, firstTry: Long) {
        if (gen != otpGen) return
        win.web.evaluateJavascript(Js.fillOtp(code)) { r ->
            if (gen != otpGen) return@evaluateJavascript
            val res = decodeJs(r)
            when {
                res.startsWith("FILLED") -> { stage("OTP_FILLED", res); submitWhenReady(win, gen, System.currentTimeMillis()) }
                System.currentTimeMillis() - firstTry < 3000 -> ui.postDelayed({ fillAndSubmit(win, code, gen, firstTry) }, 200)
                else -> {
                    log("OTP fill failed: $res (url=${win.web.url})")
                    snap("otp_nofield"); win.web.evaluateJavascript(Js.snapshotHtml("otp_nofield"), null)
                    alarm("otp-fill")
                    setStatus("Couldn't fill the OTP — type $code yourself and press FORWARD (copied to clipboard).")
                }
            }
        }
    }

    /** Wait (≤3s) for a Turnstile token if the page has an empty one, then tick privacy + FORWARD. */
    private fun submitWhenReady(win: SnipeSession, gen: Int, since: Long) {
        if (gen != otpGen) return
        win.web.evaluateJavascript(Js.TURNSTILE) { t ->
            if (gen != otpGen) return@evaluateJavascript
            val len = decodeJs(t).toIntOrNull() ?: -2
            if (len == 0 && System.currentTimeMillis() - since < 3000) {
                ui.postDelayed({ submitWhenReady(win, gen, since) }, 100); return@evaluateJavascript
            }
            win.web.evaluateJavascript(Js.snapshotHtml("before_forward"), null)
            win.web.evaluateJavascript(Js.SUBMIT_OTP) { sres ->
                val res = decodeJs(sres)
                forwardClickedAt = System.currentTimeMillis()
                stage("FORWARD_CLICKED", "$res turnstileLen=$len waitedTurnstile=${System.currentTimeMillis() - since}ms")
                if (res.startsWith("NOFWD") || res.startsWith("ERR")) {
                    alarm("forward")
                    setStatus("Couldn't press FORWARD — press it yourself!")
                }
            }
        }
    }

    /** FORWARD led back to the services list instead of the calendar: capture why. */
    private fun onBounce(s: SnipeSession) {
        forwardClickedAt = 0
        stage("BOUNCED_TO_SERVICES", s.web.url ?: "")
        captureUntil = maxOf(captureUntil, System.currentTimeMillis() + 60_000)
        snap("bounce")
        s.web.evaluateJavascript(Js.snapshotHtml("bounce"), null)
        for (d in listOf(300L, 1200L, 3000L)) {
            ui.postDelayed({
                s.web.evaluateJavascript(Js.MODALS) { r -> log("BOUNCE +${d}ms modals=${decodeJs(r)}") }
                s.web.evaluateJavascript(Js.PAGE_SUMMARY) { r -> log("BOUNCE +${d}ms page=${decodeJs(r)}") }
                if (d == 1200L) snap("bounce_1200")
            }, d)
        }
        alarm("bounce")
        setStatus("Sent back to the services list after FORWARD — details captured. Try BOOK again by hand.")
    }

    // ---------------------------------------------------------------- Calendar + auto-book

    private fun onCalendarLoaded(s: SnipeSession, u: String) {
        if (t0Ms == 0L) t0Ms = nowC() // calendar reached by hand: time stages from here
        stage("CALENDAR", u)
        forwardClickedAt = 0
        if (winner == null) winner = s
        s.web.bringToFront()
        captureUntil = maxOf(captureUntil, System.currentTimeMillis() + CAPTURE_WINDOW_MS)
        snap("calendar")
        alarm("calendar", 2000)
        if (prefs.autoBook) autoBooker.start(s.web, "calendar loaded")
        else setStatus("★ On the calendar — pick a green day + slot and press BOOK! ★")
    }

    /**
     * Calendar state machine. Driven by snapshots (Js.CAL_SNAPSHOT) and waits-for-stable instead
     * of fixed delays. Order: free slot on the auto-selected day → other bookable days → next
     * month (≤12). After BOOK: watches URL, alerts, modals, a second OTP, for up to 20s.
     */
    inner class AutoBooker {
        private var gen = 0
        private var web: WebView? = null
        private var months = 0
        private val triedDays = HashSet<String>()
        private val triedFasce = HashSet<String>()
        private var attempts = 0
        private var bookClickedAt = 0L
        private var slotAlarmed = false
        /** Step token: each wait/watch takes a new one; stale continuations drop out. */
        private var step = 0
        private val handledModals = HashSet<String>()
        var running = false
            private set

        fun start(w: WebView, why: String) {
            gen++
            web = w; months = 0; attempts = 0; slotAlarmed = false
            triedDays.clear(); triedFasce.clear(); handledModals.clear()
            running = true
            log("AUTOBOOK START ($why) gen=$gen")
            setStatus("★ On calendar — scanning for a free slot… ★")
            val g = gen
            waitStable(g, null, null, 6000, 1500) { o -> scan(g, o, true) }
        }

        fun stop(reason: String) {
            if (running) log("AUTOBOOK STOP: $reason")
            running = false
            gen++
        }

        private fun snapshot(cb: (JSONObject?) -> Unit) {
            val w = web ?: return cb(null)
            w.evaluateJavascript(Js.CAL_SNAPSHOT) { r -> cb(try { JSONObject(decodeJs(r)) } catch (_: Exception) { null }) }
        }

        /**
         * Poll every 100ms until the calendar is rendered and the signature [key] has been stable
         * for 3 polls (and differs from [changeFrom], if given). [minWaitMs] guards against
         * reading the calendar before its availability AJAX has applied, unless availability
         * markers (slots / red days) are already present.
         */
        private fun waitStable(g: Int, changeFrom: String?, key: String?, maxMs: Long, minWaitMs: Long, cb: (JSONObject?) -> Unit) {
            val start = System.currentTimeMillis()
            val my = ++step   // only the newest wait/watch is allowed to continue
            var prev = ""
            var same = 0
            fun tick() {
                if (g != gen || my != step) return
                snapshot { o ->
                    if (g != gen || my != step) return@snapshot
                    val sig = o?.optString(key ?: "sig") ?: ""
                    val n = o?.optInt("n") ?: 0
                    if (sig == prev) same++ else { same = 0; prev = sig }
                    val el = System.currentTimeMillis() - start
                    val changed = changeFrom == null || sig != changeFrom || el > 2500
                    val markers = (o?.optString("fsig") ?: "").isNotEmpty() || (o?.optString("sig") ?: "").contains("disabled")
                    val busy = (o?.optInt("busy") ?: 0) > 0
                    val allOpen = o?.optBoolean("allOpen") ?: false
                    when {
                        n > 0 && same >= 2 && changed && !busy && !(allOpen && el < 3000) &&
                            (markers || el >= minWaitMs) -> cb(o)
                        el > maxMs -> { log("AUTOBOOK wait timeout ${el}ms n=$n"); cb(o) }
                        else -> ui.postDelayed({ tick() }, 100)
                    }
                }
            }
            tick()
        }

        private fun scan(g: Int, o: JSONObject?, logMonth: Boolean) {
            if (g != gen) return
            if (o == null || o.has("err")) { fail("calendar snapshot failed: ${o?.optString("err")}"); return }
            val month = o.optString("month").ifEmpty { "m$months" }
            val days = o.optJSONArray("days") ?: JSONArray()
            val fasce = o.optJSONArray("fasce") ?: JSONArray()
            val dayList = (0 until days.length()).map { days.getJSONObject(it) }
            val fasceList = (0 until fasce.length()).map { fasce.getJSONObject(it) }
            if (logMonth) {
                log("CAL month='$month' bookable=${dayList.map { it.optString("d") + if (it.optBoolean("active")) "*" else "" }} " +
                    "fasce=${fasceList.map { it.optString("t") + if (it.optBoolean("na")) "[x]" else "" }} " +
                    "bookBtns=${o.optJSONArray("bookBtns")}")
                snap("cal_$months")
            }

            val activeDay = dayList.firstOrNull { it.optBoolean("active") }?.optString("d") ?: "?"
            val dayKey = "$month/$activeDay"

            // 1) Any free slot currently shown (belongs to the selected day).
            val free = fasceList.filter {
                !it.optBoolean("na") && it.optInt("n") > 0 && "$dayKey/${it.optString("t")}" !in triedFasce
            }
            if (free.isNotEmpty()) { bookFascia(g, dayKey, free[0]); return }
            if (activeDay != "?") triedDays.add(dayKey)

            // 2) Another bookable day in this month.
            val nextDay = dayList.firstOrNull { "$month/${it.optString("d")}" !in triedDays }
            if (nextDay != null) {
                val key = "$month/${nextDay.optString("d")}"
                triedDays.add(key)
                web?.evaluateJavascript(Js.clickDay(nextDay.optInt("i"))) { r ->
                    log("AUTOBOOK day click $key -> ${decodeJs(r)}")
                    waitStable(g, o.optString("fsig"), "fsig", 4000, 0) { o2 -> scan(g, o2, false) }
                }
                return
            }

            // 3) Next month.
            months++
            if (months > 12) {
                stage("CAL_NO_AVAIL", "scanned ${months} months")
                snap("calendar_noavail")
                running = false
                setStatus("No free slot in the next 12 months — logged as evidence.")
                return
            }
            web?.evaluateJavascript(Js.NEXT_MONTH) { r ->
                val res = decodeJs(r)
                if (!res.startsWith("NEXT")) { fail("can't find next-month arrow: $res"); return@evaluateJavascript }
                waitStable(g, o.optString("sig"), "sig", 5000, 600) { o2 -> scan(g, o2, true) }
            }
        }

        private fun bookFascia(g: Int, dayKey: String, f: JSONObject) {
            val t = f.optString("t")
            triedFasce.add("$dayKey/$t")
            attempts++
            stage("SLOT_FREE", "$dayKey $t attempt=$attempts")
            if (!slotAlarmed) { slotAlarmed = true; alarm("slot", 4000) }
            setStatus("★★ FREE SLOT $dayKey $t — booking… ★★")
            web?.evaluateJavascript(Js.clickFascia(f.optInt("i"))) { r ->
                log("AUTOBOOK fascia click -> ${decodeJs(r)}")
                ui.postDelayed({ clickBook(g, dayKey, t) }, 250)
            }
        }

        private fun clickBook(g: Int, dayKey: String, t: String) {
            if (g != gen) return
            val w = web ?: return
            w.evaluateJavascript(Js.snapshotHtml("before_book"), null)
            // Alerts can fire synchronously inside the click, before the callback below runs,
            // so the post-book watch window starts *before* the click.
            val preClick = System.currentTimeMillis()
            w.evaluateJavascript(Js.CLICK_BOOK) { r ->
                val res = decodeJs(r)
                if (res.startsWith("CLICKED")) {
                    bookClickedAt = preClick
                    stage("BOOK_CLICKED", "$dayKey $t ${res.take(300)}")
                    snap("after_book_click")
                    watchPostBook(g, preClick)
                } else {
                    stage("BOOK_NOBTN", res.take(600))
                    snap("book_nobtn")
                    w.evaluateJavascript(Js.snapshotHtml("book_nobtn"), null)
                    alarm("press-book", 6000)
                    running = false
                    setStatus("★★ Slot $dayKey $t selected — PRESS BOOK/PRENOTA YOURSELF NOW ★★")
                }
            }
        }

        private fun watchPostBook(g: Int, startedAt: Long) {
            var otpFilled = false
            val startUrl = web?.url ?: ""
            val my = ++step
            fun tick() {
                if (g != gen || my != step) return
                val w = web ?: return
                val el = System.currentTimeMillis() - startedAt
                val url = w.url ?: ""
                if (url != startUrl && !url.contains("/BookingCalendar", true)) {
                    val path = Uri.parse(url).path ?: ""
                    if (path.equals("/Services", true)) {
                        stage("POSTBOOK_BOUNCED", url)
                        alarm("bounce"); running = false
                        setStatus("After BOOK the site went back to the services list — captured. Check My appointments.")
                    } else booked("url changed to $url")
                    return
                }
                if (lastAlertAt > startedAt) {
                    val a = lastAlert; lastAlertAt = 0
                    stage("POSTBOOK_ALERT", a)
                    val l = a.lowercase()
                    if (l.contains("confermat") || l.contains("confirmed") || l.contains("success") || l.contains("effettuat")) {
                        booked("alert: $a"); return
                    }
                    if (!l.startsWith("confirm:")) { retryNext(g, "alert: $a"); return }
                }
                if (OtpHolder.code != null && OtpHolder.at > startedAt && !otpFilled) {
                    otpFilled = true
                    val c = OtpHolder.code!!
                    stage("POSTBOOK_OTP", c)
                    w.evaluateJavascript(Js.fillOtp(c)) { r -> log("POSTBOOK OTP fill -> ${decodeJs(r)}") }
                }
                w.evaluateJavascript(Js.MODALS) { r ->
                    if (g != gen || my != step) return@evaluateJavascript
                    val arr = try { JSONArray(decodeJs(r)) } catch (_: Exception) { JSONArray() }
                    for (i in 0 until arr.length()) {
                        val m = arr.getString(i)
                        if (!handledModals.add(m)) continue
                        stage("POSTBOOK_MODAL", m.take(400))
                        w.evaluateJavascript(Js.snapshotHtml("postbook_modal"), null)
                        snap("postbook_modal")
                        val l = m.lowercase()
                        when {
                            l.contains("confermat") || l.contains("confirmed") || l.contains("successo") ||
                                l.contains("successfully") || l.contains("effettuat") -> { booked("modal: $m"); return@evaluateJavascript }
                            l.contains("non disponibil") || l.contains("not available") || l.contains("esaurit") ||
                                l.contains("no longer") || l.contains("occupat") || l.contains("nessuna fascia") ||
                                l.contains("not been selected") -> { retryNext(g, "modal: $m"); return@evaluateJavascript }
                            else -> w.evaluateJavascript(Js.CONFIRM_MODAL) { c -> log("POSTBOOK modal confirm -> ${decodeJs(c)}") }
                        }
                    }
                    if (el > 20_000) {
                        stage("POSTBOOK_TIMEOUT", "url=${w.url}")
                        snap("postbook_timeout")
                        w.evaluateJavascript(Js.snapshotHtml("postbook_timeout"), null)
                        alarm("postbook-unknown", 6000)
                        running = false
                        setStatus("BOOK pressed — outcome unclear after 20s. CHECK THE SCREEN / My appointments / email.")
                    } else ui.postDelayed({ tick() }, 150)
                }
            }
            tick()
        }

        private fun retryNext(g: Int, why: String) {
            if (attempts >= 8) { fail("gave up after $attempts slot attempts ($why)"); return }
            log("AUTOBOOK retry next slot/day: $why")
            waitStable(g, null, null, 4000, 0) { o -> scan(g, o, false) }
        }

        private fun booked(detail: String) {
            running = false
            setStatus("★★★ LOOKS BOOKED — check the screen, My appointments and your email ★★★")
            stage("BOOKED_LIKELY", detail)
            snap("BOOKED")
            web?.evaluateJavascript(Js.snapshotHtml("booked"), null)
            web?.evaluateJavascript(Js.BODY_TEXT) { r -> log("BOOKED PAGE TEXT:\n${decodeJs(r).take(3000)}") }
            alarm("booked", 8000)
            vibrate(longArrayOf(0, 600, 200, 600, 200, 600, 200, 600))
            setStatus("★★★ LOOKS BOOKED — check the screen, My appointments and your email ★★★")
        }

        private fun fail(why: String) {
            running = false
            stage("AUTOBOOK_FAIL", why)
            snap("autobook_fail")
            web?.evaluateJavascript(Js.snapshotHtml("autobook_fail"), null)
            alarm("autobook-fail", 5000)
            setStatus("Auto-book stopped ($why) — continue by hand!")
        }
    }

    // ---------------------------------------------------------------- Alerts to the user

    private fun vibrate(pattern: LongArray) {
        val v = ContextCompat.getSystemService(this, android.os.Vibrator::class.java)
        try { v?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
    }

    /** Loud alarm-stream sound + vibration at key moments (or problems needing the user). */
    private fun alarm(kind: String, ms: Long = 3000) {
        log("ALARM $kind")
        vibrate(longArrayOf(0, 500, 150, 500))
        if (!prefs.alarmSound) return
        try {
            ringtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(this, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            ringtone = r
            ui.postDelayed({ try { r.stop() } catch (_: Exception) {} }, ms)
        } catch (e: Exception) { log("alarm sound failed: $e") }
    }

    // ---------------------------------------------------------------- English / misc

    private fun ensureEnglish(web: WebView, url: String) {
        if (url in englishTriedUrls) return   // one attempt per page, avoids reload loops
        englishTriedUrls.add(url)
        web.evaluateJavascript(Js.ENGLISH) { r ->
            when (decodeJs(r)) {
                "EN" -> englishEnsured = true  // already English (cookie carried) -> stop checking
                "CLICKED" -> log("clicked EN toggle on $url")
            }
        }
    }

    private fun notificationAccessEnabled(): Boolean {
        return try {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(packageName)
        } catch (_: Exception) { false }
    }

    private fun openNotificationAccess() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
    }

    private fun dumpPage() {
        val w = frontWeb()
        w.evaluateJavascript(
            "(function(){try{Android.onDump(document.body?document.body.innerText:'(no body)');}catch(e){Android.onDump('ERR '+e);}})();",
            null
        )
        w.evaluateJavascript(Js.snapshotHtml("manual_dump"), null)
        snap("dump")
    }

    private fun enumerateServices() {
        val url = primary.url ?: ""
        if (!url.contains("/Services", ignoreCase = true) || url.contains("/Booking/")) {
            primary.loadUrl(prefs.serviceUrl)
            toast("Loading services page… once it's shown, long-press SNIPE again.")
            return
        }
        primary.evaluateJavascript(Js.SERVICES, null)
    }

    private fun showServicesDialog(json: String) {
        val names = ArrayList<String>()
        val hrefs = ArrayList<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val parts = arr.getString(i).split(" @@ ")
                names.add(parts[0])
                hrefs.add(if (parts.size > 1) parts[1] else "")
            }
        } catch (_: Exception) {
        }
        if (names.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No services found")
                .setMessage("No booking rows detected. Log in (LOGIN), navigate to the citizenship services list, then long-press SNIPE again.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pick the service to snipe")
            .setItems(names.toTypedArray()) { _, which ->
                val href = hrefs[which]
                val idx = href.indexOf("/Services/Booking/")
                val id = if (idx >= 0) href.substring(idx) else href
                prefs.bookingId = id
                prefs.keyword = names[which].take(40)
                log("Service picked: '${names[which]}' -> bookingId='$id'")
                toast("Target set: ${names[which].take(40)}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val target = if (prefs.bookingId.isEmpty())
            "(none — matching by keyword '${prefs.keyword}')" else prefs.bookingId
        val msg = "v${BuildConfig.VERSION_NAME} · instance #$instanceId · sessions: ${sessions.size}\n" +
            "NTP offset: ${ntpOffsetMs}ms\nEvidence folder:\n${Logger.evidencePath(this)}\n\n" +
            "Target: $target\n\n--- recent log ---\n${Logger.readTail(this)}"
        AlertDialog.Builder(this)
            .setTitle("Diagnostics / log")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Pick service") { _, _ -> enumerateServices() }
            .setNegativeButton("Share log") { _, _ -> shareLog() }
            .show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Prenota Sniper log")
            putExtra(Intent.EXTRA_TEXT, Logger.readTail(this@MainActivity, 500_000))
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    // ---------------------------------------------------------------- Countdown

    private fun maybeStartCountdown() {
        if (sniping || !prefs.armed) return
        val release = AlarmScheduler.nextRelease(prefs)
        val rMs = release.toInstant().toEpochMilli()
        val delay = rMs - nowC()
        if (delay in -1500..(15 * 60 * 1000L)) {
            cancelCountdown()
            releaseMs = rMs
            prewarmed = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.postDelayed({ window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, delay.coerceAtLeast(0) + 10 * 60_000L)
            log("COUNTDOWN armed #$instanceId release=${release} in ${delay}ms")
            fireRunnable = Runnable { startSnipe(manual = false) }
            countdownRunnable = object : Runnable {
                override fun run() {
                    val left = releaseMs - nowC()
                    if (!prewarmed && left in 15_000..60_000) {
                        prewarmed = true
                        prewarmSessions()
                    }
                    if (left <= 300) {
                        ui.postDelayed(fireRunnable!!, left.coerceAtLeast(0))
                    } else {
                        setStatus("Armed. Firing in ${left / 1000}s (NTP-synced). Keep app open.")
                        ui.postDelayed(this, minOf(250L, left - 300))
                    }
                }
            }
            ui.post(countdownRunnable!!)
        } else {
            val whenStr = release.withZoneSameInstant(ZoneId.systemDefault()).format(fmt)
            setStatus("Armed. Next release: $whenStr (local). You'll be alerted ${prefs.prewarmMinutes} min before.")
        }
    }

    /** ~T-60s: load the services list in every session (warm) and verify we're still logged in. */
    private fun prewarmSessions() {
        log("PREWARM: loading services list in ${sessions.size} session(s); checking login")
        refreshNtp()
        sessionCheckPending = true
        sessions.forEach { it.web.loadUrl(prefs.serviceUrl) }
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { ui.removeCallbacks(it) }
        fireRunnable?.let { ui.removeCallbacks(it) }
        countdownRunnable = null
        fireRunnable = null
    }

    private fun refreshNtp() {
        thread {
            val off = NtpClient.fetchOffsetMs()
            if (off != null) {
                ntpOffsetMs = off
                log("NTP offset = ${off}ms (#$instanceId)")
            }
        }
    }

    // ---------------------------------------------------------------- Settings

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply { setText(value); box.addView(this) }
        }
        fun check(label: String, value: Boolean): CheckBox =
            CheckBox(this).apply { text = label; isChecked = value; box.addView(this) }

        val kw = field("Service keyword (matches the table row)", prefs.keyword)
        val sessionsField = field("Parallel sessions 1-4 (relaunch to apply; 1 recommended)", prefs.parallelSessions.toString())
        val retries = field("Max waves", prefs.retries.toString())
        val interval = field("Retry interval (ms)", prefs.retryIntervalMs.toString())
        val prewarm = field("Alert minutes before release", prefs.prewarmMinutes.toString())
        val time = field("Release time HH:MM (Europe/London)",
            "%02d:%02d".format(prefs.releaseHour, prefs.releaseMinute))
        val days = field("Release days (1=Mon..7=Sun)", prefs.releaseDays)
        val englishCb = check("Force English site", prefs.forceEnglish)
        val otpCb = check("Auto-OTP (request + read from Gmail notification)", prefs.autoOtp)
        val bookCb = check("Auto-book (find a free slot + press BOOK)", prefs.autoBook)
        val soundCb = check("Alarm sound at key moments", prefs.alarmSound)
        val netCb = check("Capture calendar network traffic (relaunch to apply)", prefs.netCapture)
        val armCb = check("Armed (alert + auto-fire at release)", prefs.armed)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.keyword = kw.text.toString().trim()
                prefs.parallelSessions = sessionsField.text.toString().toIntOrNull() ?: prefs.parallelSessions
                prefs.retries = retries.text.toString().toIntOrNull() ?: prefs.retries
                prefs.retryIntervalMs = interval.text.toString().toIntOrNull() ?: prefs.retryIntervalMs
                prefs.prewarmMinutes = prewarm.text.toString().toIntOrNull() ?: prefs.prewarmMinutes
                parseTime(time.text.toString())
                prefs.releaseDays = days.text.toString().trim()
                prefs.forceEnglish = englishCb.isChecked
                if (englishCb.isChecked) { englishEnsured = false; englishTriedUrls.clear() }
                prefs.autoOtp = otpCb.isChecked
                prefs.autoBook = bookCb.isChecked
                prefs.alarmSound = soundCb.isChecked
                prefs.netCapture = netCb.isChecked
                if (otpCb.isChecked && !notificationAccessEnabled()) {
                    toast("Grant 'Notification access' to Prenota Sniper so it can read the OTP.")
                    openNotificationAccess()
                }

                val wantArmed = armCb.isChecked
                if (wantArmed && !ensureExactAlarms()) {
                    prefs.armed = false
                    toast("Grant 'Alarms & reminders' permission, then arm again.")
                } else {
                    prefs.armed = wantArmed
                    if (wantArmed) {
                        AlarmScheduler.schedule(this, prefs)
                        toast("Armed. Evidence log: ${Logger.evidencePath(this)}")
                    } else {
                        AlarmScheduler.cancel(this)
                    }
                    maybeStartCountdown()
                }
                if (sessionsField.text.toString().toIntOrNull() != null &&
                    sessionsField.text.toString().toInt() != sessions.size
                ) toast("Relaunch the app to apply the new session count.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseTime(s: String) {
        val parts = s.split(":")
        if (parts.size == 2) {
            parts[0].trim().toIntOrNull()?.let { if (it in 0..23) prefs.releaseHour = it }
            parts[1].trim().toIntOrNull()?.let { if (it in 0..59) prefs.releaseMinute = it }
        }
    }

    private fun ensureExactAlarms(): Boolean {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (am.canScheduleExactAlarms()) return true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) } catch (_: Exception) {}
        }
        return false
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 99)
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun setStatus(s: String) { status.text = "v${BuildConfig.VERSION_NAME} · $s" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = frontWeb()
        if (w.canGoBack()) w.goBack() else super.onBackPressed()
    }
}
