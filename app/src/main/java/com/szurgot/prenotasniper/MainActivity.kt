package com.szurgot.prenotasniper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
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

    private var countdownRunnable: Runnable? = null
    private var fireRunnable: Runnable? = null

    private val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.txtStatus)
        web = findViewById(R.id.webview)

        setupWebView()

        findViewById<Button>(R.id.btnSnipe).setOnClickListener { startSnipe(manual = true) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSnipe("Stopped by user.") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { web.loadUrl(prefs.loginUrl) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnSettings).setOnLongClickListener { dumpPage(); true }

        requestNotifPermission()
        refreshNtp()

        // First launch shows login; afterwards restore the services page (still logged in).
        val start = if (savedInstanceState == null) prefs.loginUrl else prefs.serviceUrl
        web.loadUrl(start)

        setStatus("Ready. Log in once, set the service keyword in SETTINGS, then Arm or Snipe Now.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: return
                if (!sniping) return
                if (u.contains("/Services/Booking/")) {
                    onResult("SUCCESS")
                } else if (u.contains("/Services", ignoreCase = true)) {
                    injectClick()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val m = (message ?: "").lowercase()
                Logger.log(this@MainActivity, "JS ALERT: $message")
                result?.confirm()
                if (sniping && (m.contains("sold out") || m.contains("high demand") || m.contains("esaurit"))) {
                    ui.post { onResult("SOLD_OUT") }
                }
                return true
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onResult(status: String) {
            ui.post { this@MainActivity.onResult(status) }
        }

        @JavascriptInterface
        fun onDump(text: String) {
            Logger.log(this@MainActivity, "PAGE DUMP:\n$text")
            ui.post { toast("Page dumped to log.txt") }
        }
    }

    // ---------------------------------------------------------------- Snipe

    private fun startSnipe(manual: Boolean) {
        cancelCountdown()
        sniping = true
        attempt = 0
        Logger.log(this, "SNIPE START (${if (manual) "manual" else "scheduled"}) keyword='${prefs.keyword}'")
        setStatus("Sniping… attempt 0/${prefs.retries}")
        val current = web.url ?: ""
        if (current.contains("/Services", ignoreCase = true) && !current.contains("/Booking/")) {
            injectClick()
        } else {
            web.loadUrl(prefs.serviceUrl) // onPageFinished -> injectClick()
        }
    }

    private fun stopSnipe(msg: String) {
        sniping = false
        cancelCountdown()
        Logger.log(this, "SNIPE STOP: $msg")
        setStatus(msg)
    }

    private fun injectClick() {
        if (!sniping) return
        resultHandled = false
        attempt++
        setStatus("Sniping… attempt $attempt/${prefs.retries}")
        val kw = prefs.keyword.replace("\"", "").replace("\\", "")
        web.evaluateJavascript(clickJs(kw), null)
    }

    private fun onResult(res: String) {
        if (!sniping || resultHandled) return
        resultHandled = true
        Logger.log(this, "attempt $attempt -> $res")
        Logger.screenshot(this, web, "a${attempt}_${res.take(12)}")

        when {
            res == "SUCCESS" -> handleSuccess()
            res == "NO_SERVICE" -> {
                if (attempt <= 1) {
                    web.loadUrl(prefs.serviceUrl) // maybe not loaded yet; retry via onPageFinished
                } else {
                    stopSnipe("Service not found. Check the keyword in SETTINGS (long-press SETTINGS to dump the page).")
                }
            }
            else -> { // SOLD_OUT / TIMEOUT / ERR
                if (attempt < prefs.retries) {
                    ui.postDelayed({ injectClick() }, prefs.retryIntervalMs.toLong())
                } else {
                    stopSnipe("No slot after ${prefs.retries} attempts (last: $res). Logged as evidence.")
                }
            }
        }
    }

    private fun handleSuccess() {
        sniping = false
        cancelCountdown()
        Logger.log(this, "*** SUCCESS — a bookable page loaded. Handing over. ***")
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

    private fun clickJs(keyword: String): String = """
        (function(){
          try{
            var conf=document.querySelector('.sweet-alert button.confirm, .swal2-confirm, .confirm');
            if(conf){ try{conf.click();}catch(e){} }
            var kw="$keyword".toLowerCase();
            var rows=document.querySelectorAll('tr');
            var target=null;
            for(var i=0;i<rows.length;i++){
              var t=(rows[i].innerText||'').toLowerCase();
              if(t.indexOf(kw)>=0){
                var as=rows[i].querySelectorAll('a,button');
                for(var j=0;j<as.length;j++){
                  var el=as[j];
                  var et=(el.innerText||'').toLowerCase();
                  var href=(el.getAttribute('href')||'');
                  if(et.indexOf('book')>=0 || href.indexOf('/Services/Booking/')>=0){ target=el; break; }
                }
                if(!target){ var a=rows[i].querySelector('a'); if(a) target=a; }
                if(target) break;
              }
            }
            if(!target){ Android.onResult('NO_SERVICE'); return; }
            target.click();
            var tries=0;
            var iv=setInterval(function(){
              tries++;
              var body=((document.body?document.body.innerText:'')+'').toLowerCase();
              if(body.indexOf('sold out')>=0||body.indexOf('high demand')>=0||body.indexOf('esaurit')>=0){
                clearInterval(iv); Android.onResult('SOLD_OUT'); return;
              }
              if(location.href.indexOf('/Services/Booking/')>=0){ clearInterval(iv); Android.onResult('SUCCESS'); return; }
              var cal=document.querySelector('.ui-datepicker-calendar,#calendar,.calendar,input[type=date],select[name*=hour],select[name*=ora]');
              if(cal){ clearInterval(iv); Android.onResult('SUCCESS'); return; }
              if(tries>50){ clearInterval(iv); Android.onResult('TIMEOUT'); }
            },100);
          }catch(e){ Android.onResult('ERR:'+e); }
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
