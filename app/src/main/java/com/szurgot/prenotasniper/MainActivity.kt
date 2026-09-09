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

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var ntpOffsetMs: Long = 0
    private var sniping = false
    private var attempt = 0
    private var resultHandled = false
    private var awaitingLoadThenAttempt = false
    private var consecutiveBook = 0
    private var pollTick = 0

    private var countdownRunnable: Runnable? = null
    private var fireRunnable: Runnable? = null

    private val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss")

    // Outcome-polling cadence after a click.
    private val POLL_INTERVAL_MS = 150L
    private val POLL_MAX_TICKS = 45          // ~6.75s total before treating as inconclusive
    private val BOOK_SETTLE_TICKS = 20       // staying on the booking page this long (~3s) = real slot

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.txtStatus)
        web = findViewById(R.id.webview)

        setupWebView()

        findViewById<Button>(R.id.btnSnipe).setOnClickListener { startSnipe(manual = true) }
        findViewById<Button>(R.id.btnSnipe).setOnLongClickListener { enumerateServices(); true }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSnipe("Stopped by user.") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { web.loadUrl(prefs.loginUrl) }
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
        web.loadUrl(start)

        setStatus("Ready. Log in once, set the service keyword in SETTINGS, then Arm or Snipe Now.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("url")?.let { prefs.serviceUrl = it; web.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("autostart", false) == true) {
            intent.removeExtra("autostart")
            web.loadUrl(prefs.serviceUrl)
        }
        maybeStartCountdown()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
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
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.log(this@MainActivity, "PAGE LOADED: $url")
                val u = url ?: return
                if (!sniping) return
                // Only kick off the first attempt once the services list has loaded.
                // (Do NOT treat reaching /Services/Booking/ as success — sold-out lands
                //  there too, briefly, before bouncing back and showing the modal.)
                if (awaitingLoadThenAttempt && u.contains("/Services", ignoreCase = true) && !u.contains("/Booking/")) {
                    awaitingLoadThenAttempt = false
                    doAttempt()
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val msg = "LOAD ERROR ${error?.errorCode} '${error?.description}' url=${request.url}"
                    Log.e("PrenotaSniper", msg)
                    Logger.log(this@MainActivity, msg)
                    ui.post { setStatus("Load failed: ${error?.description} (${request.url})") }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    Logger.log(
                        this@MainActivity,
                        "HTTP ERROR ${errorResponse?.statusCode} url=${request.url}"
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                Logger.log(this@MainActivity, "SSL ERROR $error")
                handler?.cancel()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val m = (message ?: "").lowercase()
                Logger.log(this@MainActivity, "JS ALERT: $message")
                result?.confirm()
                if (sniping && (m.contains("sold out") || m.contains("high demand") || m.contains("esaurit"))) {
                    ui.post { handleOutcome("SOLD_OUT") }
                }
                return true
            }

            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                cm?.let { Logger.log(this@MainActivity, "JS CONSOLE: ${it.message()} @${it.lineNumber()}") }
                return true
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onResult(status: String) {
            ui.post { handleOutcome(status) }
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

    // ---------------------------------------------------------------- Snipe

    private fun startSnipe(manual: Boolean) {
        cancelCountdown()
        sniping = true
        attempt = 0
        val target = if (prefs.bookingId.isNotEmpty()) prefs.bookingId else prefs.keyword
        Logger.log(this, "SNIPE START (${if (manual) "manual" else "scheduled"}) target='$target'")
        setStatus("Sniping… attempt 0/${prefs.retries}")
        val current = web.url ?: ""
        if (current.contains("/Services", ignoreCase = true) && !current.contains("/Booking/")) {
            doAttempt()
        } else {
            awaitingLoadThenAttempt = true
            web.loadUrl(prefs.serviceUrl) // onPageFinished -> doAttempt()
        }
    }

    private fun stopSnipe(msg: String) {
        sniping = false
        awaitingLoadThenAttempt = false
        ui.removeCallbacks(pollRunnable)
        cancelCountdown()
        Logger.log(this, "SNIPE STOP: $msg")
        setStatus(msg)
    }

    /** One attempt: click the target's Prenota, then poll the outcome from Kotlin. */
    private fun doAttempt() {
        if (!sniping) return
        resultHandled = false
        consecutiveBook = 0
        pollTick = 0
        attempt++
        setStatus("Sniping… attempt $attempt/${prefs.retries}")
        val kw = prefs.keyword.replace("\"", "").replace("\\", "")
        val id = prefs.bookingId.replace("\"", "").replace("\\", "")
        web.evaluateJavascript(clickJs(kw, id), null)
        ui.postDelayed(pollRunnable, 250)
    }

    /**
     * Polls the page after a click. Sold-out shows the "esauriti/elevata richiesta"
     * modal and bounces back off /Services/Booking/; an available service STAYS on
     * the booking page (optionally with a calendar). So: modal -> SOLD_OUT; staying
     * on the booking page -> SUCCESS.
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!sniping || resultHandled) return
            pollTick++
            if (pollTick >= POLL_MAX_TICKS) { handleOutcome("SOLD_OUT"); return } // inconclusive -> retry
            // The JS callback can be dropped during a navigation, so we reschedule the
            // tick unconditionally below rather than from inside the callback.
            web.evaluateJavascript(STATE_JS) { raw ->
                if (!sniping || resultHandled) return@evaluateJavascript
                when (raw?.trim('"')) {
                    "SOLD" -> handleOutcome("SOLD_OUT")
                    "SUCCESS" -> handleOutcome("SUCCESS")
                    "BOOK" -> { consecutiveBook++; if (consecutiveBook >= BOOK_SETTLE_TICKS) handleOutcome("SUCCESS") }
                    else -> consecutiveBook = 0 // WAIT / mid-navigation / bounced to list
                }
            }
            ui.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** Central outcome handler; also reachable from the JS bridge (native alert / NO_SERVICE). */
    private fun handleOutcome(res: String) {
        if (!sniping || resultHandled) return
        resultHandled = true
        ui.removeCallbacks(pollRunnable)
        Logger.log(this, "attempt $attempt -> $res")
        Logger.screenshot(this, web, "a${attempt}_${res.take(12)}")
        when (res) {
            "SUCCESS" -> handleSuccess()
            "NO_SERVICE" -> stopSnipe("Service not found. Long-press SNIPE to pick the service, or set the keyword in SETTINGS.")
            else -> { // SOLD_OUT / ERR / inconclusive
                if (attempt < prefs.retries) ui.postDelayed({ doAttempt() }, prefs.retryIntervalMs.toLong())
                else stopSnipe("No slot after ${prefs.retries} attempts (last: $res). Logged as evidence.")
            }
        }
    }

    private fun handleSuccess() {
        sniping = false
        ui.removeCallbacks(pollRunnable)
        cancelCountdown()
        Logger.log(this, "*** SUCCESS — bookable page stayed open. Handing over. ***")
        Logger.screenshot(this, web, "SUCCESS")
        setStatus("★ SLOT AVAILABLE — TAKE OVER NOW: pick day/time, then enter the OTP. ★")
        val v = ContextCompat.getSystemService(this, android.os.Vibrator::class.java)
        try {
            v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        } catch (_: Exception) {}
    }

    private fun dumpPage() {
        web.evaluateJavascript(
            "(function(){try{Android.onDump(document.body?document.body.innerText:'(no body)');}catch(e){Android.onDump('ERR '+e);}})();",
            null
        )
        Logger.screenshot(this, web, "dump")
    }

    /** Enumerate every bookable service row on the current Services page. */
    private fun enumerateServices() {
        val url = web.url ?: ""
        if (!url.contains("/Services", ignoreCase = true) || url.contains("/Booking/")) {
            web.loadUrl(prefs.serviceUrl)
            toast("Loading services page… once it's shown, long-press SNIPE again.")
            return
        }
        web.evaluateJavascript(servicesJs(), null)
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
        val msg = "Evidence folder:\n${Logger.evidencePath(this)}\n\n" +
                "Target: $target\n\n--- recent log ---\n${Logger.readTail(this)}"
        AlertDialog.Builder(this)
            .setTitle("Diagnostics / log")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Pick service") { _, _ -> enumerateServices() }
            .show()
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
     * Evaluated repeatedly after a click. Returns one of:
     *  SOLD    - the sold-out modal / message is present
     *  SUCCESS - a visible calendar/date-picker is showing on a booking page
     *  BOOK    - on a /Services/Booking/ page, no modal yet (may settle into SUCCESS)
     *  WAIT    - anything else (mid-navigation, bounced back to the list)
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
        val retries = field("Max attempts", prefs.retries.toString())
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
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
