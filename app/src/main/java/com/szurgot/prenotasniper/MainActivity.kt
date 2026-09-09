package com.szurgot.prenotasniper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
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
import org.json.JSONArray
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private val sessions = mutableListOf<SnipeSession>()
    private val primary: WebView get() = sessions[0].web

    @Volatile private var ntpOffsetMs: Long = 0
    private var sniping = false
    private var wave = 0

    private var countdownRunnable: Runnable? = null
    private var fireRunnable: Runnable? = null

    private val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss")

    // Outcome-polling cadence after a click.
    private val POLL_INTERVAL_MS = 150L
    private val POLL_MAX_TICKS = 45          // ~6.75s total before treating as inconclusive
    private val BOOK_SETTLE_TICKS = 20       // staying on the booking page this long (~3s) = real slot

    /** One concurrent booking session: a WebView plus its own poll/attempt state. */
    inner class SnipeSession(val web: WebView, val index: Int) {
        var resultHandled = false
        var consecutiveBook = 0
        var pollTick = 0
        var awaitingLoad = false
        var done = false
        var lastRes = ""

        val poll = object : Runnable {
            override fun run() {
                if (!sniping || resultHandled) return
                pollTick++
                if (pollTick >= POLL_MAX_TICKS) { sessionOutcome(this@SnipeSession, "SOLD_OUT"); return }
                web.evaluateJavascript(STATE_JS) { raw ->
                    if (!sniping || resultHandled) return@evaluateJavascript
                    when (raw?.trim('"')) {
                        "SOLD" -> sessionOutcome(this@SnipeSession, "SOLD_OUT")
                        "SUCCESS" -> sessionOutcome(this@SnipeSession, "SUCCESS")
                        "BOOK" -> { consecutiveBook++; if (consecutiveBook >= BOOK_SETTLE_TICKS) sessionOutcome(this@SnipeSession, "SUCCESS") }
                        else -> consecutiveBook = 0
                    }
                }
                ui.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        fun attempt() {
            resultHandled = false; consecutiveBook = 0; pollTick = 0; done = false
            val kw = prefs.keyword.replace("\"", "").replace("\\", "")
            val id = prefs.bookingId.replace("\"", "").replace("\\", "")
            web.evaluateJavascript(clickJs(kw, id), null)
            ui.postDelayed(poll, 250)
        }

        fun stopPoll() = ui.removeCallbacks(poll)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.txtStatus)
        container = findViewById(R.id.webContainer)

        CookieManager.getInstance().setAcceptCookie(true)
        val n = prefs.parallelSessions
        for (i in 0 until n) {
            val w = WebView(this)
            w.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            container.addView(w)
            val s = SnipeSession(w, i)
            sessions.add(s)
            setupWebView(s)
        }
        primary.bringToFront() // primary on top for login / navigation / handover

        findViewById<Button>(R.id.btnSnipe).setOnClickListener { startSnipe(manual = true) }
        findViewById<Button>(R.id.btnSnipe).setOnLongClickListener { enumerateServices(); true }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSnipe("Stopped by user.") }
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

        setStatus("Ready (${sessions.size} sessions). Log in once, pick the service (long-press SNIPE), then Arm or Snipe Now.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("url")?.let { prefs.serviceUrl = it; primary.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("autostart", false) == true) {
            intent.removeExtra("autostart")
            primary.loadUrl(prefs.serviceUrl)
        }
        maybeStartCountdown()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWebView(s: SnipeSession) {
        val web = s.web
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = true
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.addJavascriptInterface(Bridge(s), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: return
                Logger.log(this@MainActivity, "PAGE LOADED[${s.index}]: $u")
                if (!sniping) return
                if (s.awaitingLoad && u.contains("/Services", ignoreCase = true) && !u.contains("/Booking/")) {
                    s.awaitingLoad = false
                    s.attempt()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val msg = "LOAD ERROR[${s.index}] ${error?.errorCode} '${error?.description}' url=${request.url}"
                    Log.e("PrenotaSniper", msg)
                    Logger.log(this@MainActivity, msg)
                    if (s.index == 0) ui.post { setStatus("Load failed: ${error?.description} (${request.url})") }
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    Logger.log(this@MainActivity, "HTTP ERROR[${s.index}] ${errorResponse?.statusCode} url=${request.url}")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Logger.log(this@MainActivity, "SSL ERROR[${s.index}] $error")
                handler?.cancel()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val m = (message ?: "").lowercase()
                Logger.log(this@MainActivity, "JS ALERT[${s.index}]: $message")
                result?.confirm()
                if (sniping && (m.contains("sold out") || m.contains("high demand") || m.contains("esaurit"))) {
                    ui.post { sessionOutcome(s, "SOLD_OUT") }
                }
                return true
            }

            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                if (s.index == 0) cm?.let { Logger.log(this@MainActivity, "JS CONSOLE: ${it.message()} @${it.lineNumber()}") }
                return true
            }
        }
    }

    inner class Bridge(private val session: SnipeSession?) {
        @JavascriptInterface
        fun onResult(status: String) {
            val s = session ?: return
            ui.post { sessionOutcome(s, status) }
        }

        @JavascriptInterface
        fun onDump(text: String) {
            Logger.log(this@MainActivity, "PAGE DUMP:\n$text")
            ui.post { toast("Page dumped to log.txt") }
        }

        @JavascriptInterface
        fun onServices(json: String) {
            ui.post { showServicesDialog(json) }
        }
    }

    // ---------------------------------------------------------------- Snipe (fan-out)

    private fun startSnipe(manual: Boolean) {
        cancelCountdown()
        sniping = true
        wave = 0
        val target = if (prefs.bookingId.isNotEmpty()) prefs.bookingId else prefs.keyword
        Logger.log(this, "SNIPE START (${if (manual) "manual" else "scheduled"}) target='$target' sessions=${sessions.size}")
        fireWave()
    }

    private fun stopSnipe(msg: String) {
        sniping = false
        sessions.forEach { it.stopPoll(); it.resultHandled = true }
        cancelCountdown()
        Logger.log(this, "SNIPE STOP: $msg")
        setStatus(msg)
    }

    /** Fire one wave: every session clicks the target at (near) the same instant. */
    private fun fireWave() {
        if (!sniping) return
        wave++
        setStatus("Sniping… wave $wave/${prefs.retries} × ${sessions.size} sessions")
        sessions.forEach { s ->
            s.done = false
            s.resultHandled = false
            val cur = s.web.url ?: ""
            if (cur.contains("/Services", ignoreCase = true) && !cur.contains("/Booking/")) {
                s.attempt()
            } else {
                s.awaitingLoad = true
                s.web.loadUrl(prefs.serviceUrl) // onPageFinished(services) -> s.attempt()
            }
        }
    }

    /** Per-session result within a wave; first SUCCESS wins, otherwise wait for the wave to finish. */
    private fun sessionOutcome(s: SnipeSession, res: String) {
        if (!sniping || s.resultHandled) return
        s.resultHandled = true
        s.stopPoll()
        s.lastRes = res
        Logger.log(this, "wave $wave session ${s.index} -> $res")

        if (res == "SUCCESS") { winSession(s); return }

        s.done = true
        if (sessions.all { it.done }) {
            Logger.screenshot(this, primary, "wave${wave}_${res.take(8)}")
            when {
                sessions.all { it.lastRes == "NO_SERVICE" } ->
                    stopSnipe("Service not found. Long-press SNIPE to pick the service, or set the keyword in SETTINGS.")
                wave < prefs.retries ->
                    ui.postDelayed({ fireWave() }, prefs.retryIntervalMs.toLong())
                else ->
                    stopSnipe("No slot after ${prefs.retries} waves × ${sessions.size} sessions. Logged as evidence.")
            }
        }
    }

    private fun winSession(s: SnipeSession) {
        sniping = false
        sessions.forEach { it.stopPoll(); it.resultHandled = true }
        cancelCountdown()
        s.web.bringToFront() // show the session that landed a slot
        Logger.log(this, "*** SUCCESS on session ${s.index} — bookable page stayed open. Handing over. ***")
        Logger.screenshot(this, s.web, "SUCCESS")
        setStatus("★ SLOT AVAILABLE (session ${s.index}) — TAKE OVER NOW: pick day/time, then enter the OTP. ★")
        val v = ContextCompat.getSystemService(this, android.os.Vibrator::class.java)
        try {
            v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        } catch (_: Exception) {}
    }

    private fun dumpPage() {
        primary.evaluateJavascript(
            "(function(){try{Android.onDump(document.body?document.body.innerText:'(no body)');}catch(e){Android.onDump('ERR '+e);}})();",
            null
        )
        Logger.screenshot(this, primary, "dump")
    }

    private fun enumerateServices() {
        val url = primary.url ?: ""
        if (!url.contains("/Services", ignoreCase = true) || url.contains("/Booking/")) {
            primary.loadUrl(prefs.serviceUrl)
            toast("Loading services page… once it's shown, long-press SNIPE again.")
            return
        }
        primary.evaluateJavascript(servicesJs(), null)
    }

    private fun servicesJs(): String = """
        (function(){
          try{
            var out=[];
            var rows=document.querySelectorAll('tr');
            for(var i=0;i<rows.length;i++){
              var as=rows[i].querySelectorAll('a,button');
              var href='';
              for(var j=0;j<as.length;j++){
                var h=(as[j].getAttribute('href')||'');
                if(h.indexOf('/Services/Booking/')>=0){ href=h; break; }
              }
              if(href){
                var txt=(rows[i].innerText||'').replace(/\s+/g,' ').trim();
                out.push(txt.substring(0,100)+' @@ '+href);
              }
            }
            Android.onServices(JSON.stringify(out));
          }catch(e){ Android.onServices('["ERR: '+e+'"]'); }
        })();
    """.trimIndent()

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
                Logger.log(this, "Service picked: '${names[which]}' -> bookingId='$id'")
                toast("Target set: ${names[which].take(40)}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val target = if (prefs.bookingId.isEmpty())
            "(none — matching by keyword '${prefs.keyword}')" else prefs.bookingId
        val msg = "Sessions: ${sessions.size}\nEvidence folder:\n${Logger.evidencePath(this)}\n\n" +
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

    private fun clickJs(keyword: String, bookingId: String): String = """
        (function(){
          try{
            // Dismiss any leftover sold-out modal from a previous attempt.
            var conf=document.querySelector('.sweet-alert button.confirm,.swal2-confirm,.confirm,.sa-button-container button');
            if(!conf){ var bs=document.querySelectorAll('button'); for(var b=0;b<bs.length;b++){ if((bs[b].innerText||'').trim().toLowerCase()==='ok'){ conf=bs[b]; break; } } }
            if(conf){ try{conf.click();}catch(e){} }
            var idsub="$bookingId";
            var kw="$keyword".toLowerCase();
            var target=null;
            // Preferred: match by (untranslated) booking URL substring.
            if(idsub){
              var links=document.querySelectorAll('a,button');
              for(var k=0;k<links.length;k++){
                var h=(links[k].getAttribute('href')||'');
                if(h.indexOf(idsub)>=0){ target=links[k]; break; }
              }
            }
            // Fallback: match the row whose text contains the keyword.
            if(!target){
              var rows=document.querySelectorAll('tr');
              for(var i=0;i<rows.length;i++){
                var t=(rows[i].innerText||'').toLowerCase();
                if(kw && t.indexOf(kw)>=0){
                  var as=rows[i].querySelectorAll('a,button');
                  for(var j=0;j<as.length;j++){
                    var el=as[j];
                    var et=(el.innerText||'').toLowerCase();
                    var href=(el.getAttribute('href')||'');
                    if(et.indexOf('book')>=0 || et.indexOf('prenot')>=0 || href.indexOf('/Services/Booking/')>=0){ target=el; break; }
                  }
                  if(!target){ var a=rows[i].querySelector('a'); if(a) target=a; }
                  if(target) break;
                }
              }
            }
            if(!target){ Android.onResult('NO_SERVICE'); return; }
            target.click();  // outcome is polled from Kotlin via STATE_JS
          }catch(e){ Android.onResult('ERR:'+e); }
        })();
    """.trimIndent()

    /**
     * Evaluated repeatedly after a click. Returns SOLD / SUCCESS / BOOK / WAIT.
     */
    private val STATE_JS: String = """
        (function(){
          try{
            var body=((document.body?document.body.innerText:'')+'').toLowerCase();
            if(body.indexOf('esaurit')>=0||body.indexOf('elevata richiesta')>=0||
               body.indexOf('sold out')>=0||body.indexOf('high demand')>=0){ return 'SOLD'; }
            if(location.href.indexOf('/Services/Booking/')>=0){
              var cal=document.querySelector('.ui-datepicker-calendar,#calendar,.calendar,.datepicker,td.day,input[type=date],select[name*=ora],select[name*=hour]');
              if(cal && cal.offsetParent!==null){ return 'SUCCESS'; }
              return 'BOOK';
            }
            return 'WAIT';
          }catch(e){ return 'WAIT'; }
        })();
    """.trimIndent()

    // ---------------------------------------------------------------- Countdown

    private fun maybeStartCountdown() {
        if (sniping || !prefs.armed) return
        val release = AlarmScheduler.nextRelease(prefs)
        val releaseMs = release.toInstant().toEpochMilli()
        val correctedNow = System.currentTimeMillis() + ntpOffsetMs
        val delay = releaseMs - correctedNow
        if (delay in -1500..(15 * 60 * 1000L)) {
            cancelCountdown()
            fireRunnable = Runnable { startSnipe(manual = false) }
            ui.postDelayed(fireRunnable!!, delay.coerceAtLeast(0))
            countdownRunnable = object : Runnable {
                override fun run() {
                    val left = releaseMs - (System.currentTimeMillis() + ntpOffsetMs)
                    if (left > 0) {
                        setStatus("Armed. Firing in ${left / 1000}s (NTP-synced). Keep app open.")
                        ui.postDelayed(this, 250)
                    }
                }
            }
            ui.post(countdownRunnable!!)
        } else {
            val whenStr = release.withZoneSameInstant(ZoneId.systemDefault()).format(fmt)
            setStatus("Armed. Next release: $whenStr (local). You'll be alerted ${prefs.prewarmMinutes} min before.")
        }
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
                Logger.log(this, "NTP offset = ${off}ms")
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

        val kw = field("Service keyword (matches the table row)", prefs.keyword)
        val sessionsField = field("Parallel sessions 1-4 (relaunch to apply)", prefs.parallelSessions.toString())
        val retries = field("Max waves", prefs.retries.toString())
        val interval = field("Retry interval (ms)", prefs.retryIntervalMs.toString())
        val prewarm = field("Alert minutes before release", prefs.prewarmMinutes.toString())
        val time = field("Release time HH:MM (Europe/London)",
            "%02d:%02d".format(prefs.releaseHour, prefs.releaseMinute))
        val days = field("Release days (1=Mon..7=Sun)", prefs.releaseDays)
        val armCb = CheckBox(this).apply {
            text = "Armed (alert + auto-fire at release)"
            isChecked = prefs.armed
            box.addView(this)
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.keyword = kw.text.toString().trim()
                prefs.parallelSessions = sessionsField.text.toString().toIntOrNull() ?: prefs.parallelSessions
                prefs.retries = retries.text.toString().toIntOrNull() ?: prefs.retries
                prefs.retryIntervalMs = interval.text.toString().toIntOrNull() ?: prefs.retryIntervalMs
                prefs.prewarmMinutes = prewarm.text.toString().toIntOrNull() ?: prefs.prewarmMinutes
                parseTime(time.text.toString())
                prefs.releaseDays = days.text.toString().trim()

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
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
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

    private fun setStatus(s: String) { status.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onBackPressed() {
        if (primary.canGoBack()) primary.goBack() else super.onBackPressed()
    }
}
