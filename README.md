# Prenot@mi Sniper

An Android app that tries to secure an Italian citizenship (jure sanguinis) appointment on
[Prenot@mi](https://prenotami.esteri.it) for the Consulate General of Italy in London. Slots are
released Mondays and Wednesdays at 17:00 UK time and are gone within seconds.

The app drives the real website inside WebViews with injected JavaScript. You log in yourself; from
then on, with the default settings, it clicks BOOK, requests and fills the OTP, and tries to pick a
free date and time slot on the calendar. Every attempt is logged with timestamps and screenshots as
evidence that booking was impossible (for a "denial of justice" court filing).

**Current version: v14** (see *What's new in v14* below). Status, known problems and the next steps are in
[`docs/evidence-analysis-2026-11.md`](docs/evidence-analysis-2026-11.md). What we know about the
site's pages and DOM is in [`docs/site-notes.md`](docs/site-notes.md).

## What's new in v14

- **Booking a free slot.** Bookable days are `td.day` without `disabled`/`old`/`new`; free slots are
  `.fascia` without `notAvailableFascia`. BOOK is matched by exact text, outside the site
  header/nav. If the site shows an alert or modal saying a slot is gone, the app tries the next slot,
  then the next day. Confirm dialogs and modals are accepted. "Booked" is only reported when the
  page leaves the calendar or a confirmation appears.
- **Faster.** The sold-out popup ("All appointments for this service are currently…") is now
  recognised, as is a reload of the services list, so retry waves take ~1s instead of the 6.75s
  timeout. SEND NEW CODE is clicked as soon as it appears; the OTP is checked every 150ms and FORWARD
  follows straight away. The calendar scan waits for the page's jQuery AJAX to finish instead of
  fixed delays.
- **Safety.**
  - One snipe and one OTP request per process.
  - `onDestroy` cleanup and wider `configChanges`, which fixes the duplicate snipes behind the 9 Nov
    "Incorrect Code!".
  - At T-60s every session is pre-warmed and the login is checked (alarm if logged out).
  - The screen stays on near release; a WebView renderer crash is recovered; a 5xx page is never
    counted as SUCCESS.
- **Telling you.** An alarm-stream sound plays when the calendar loads, a free slot is seen, after
  booking, and on any problem that needs you. Long-press **STOP** on a calendar to run auto-book by
  hand.
- **Capture.**
  - `STAGE <name> T+<ms>` lines, timed from the NTP-corrected release.
  - The page chain is logged even after the snipe stops.
  - For 3 minutes after the booking page, every load, alert and modal is saved as a full HTML
    snapshot in `evidence/html/`, plus a screenshot.
  - Calendar XHR/fetch calls are logged as `NET` lines; modals as `MODAL` lines; confirm/prompt
    dialogs; the Prenot@mi notification text (`PRENOTAMI NOTIF`); lifecycle lines.
  - Console noise is filtered out, the log rotates at 5MB, and PNGs are encoded off the UI thread.
- Default parallel sessions is now **1**: all sessions share one server session. Existing installs
  keep their saved value, so set it to 1 in SETTINGS.

## How a run works

1. **Pre-warn.** An exact alarm fires `prewarmMinutes` (default 3) before release and shows a
   high-priority notification. Opening it opens the app on the services page.
2. **Countdown.** While the app is open and armed, a countdown corrected by NTP
   (`time.google.com`) fires the snipe at the release instant.
3. **Waves.** Each WebView session clicks the BOOK link for the target (`/Services/Booking/422`)
   and polls the page (`STATE_JS`) for sold-out, the booking form, or a timeout. It retries up to
   `retries` waves, `retryIntervalMs` apart.
4. **Booking form reached ("SUCCESS").** The phone vibrates, the page is dumped to the log, and the
   OTP step runs if enabled: click SEND NEW CODE → `OtpListenerService` reads the code from the
   Gmail notification → fill → tick the privacy box → FORWARD.
5. **Calendar** (`/BookingCalendar`). If auto-book is on, it scans up to 9 months for a bookable
   day and free time slot, then clicks BOOK. It screenshots and dumps every month.
6. **Evidence.** `log.txt` and PNGs are saved in the app's external files dir under `evidence/`.

## Code map

| File | Role |
|---|---|
| `MainActivity.kt` | WebView sessions, snipe waves and outcome polling, OTP fast path, `AutoBooker` (calendar state machine), capture window, countdown/pre-warm, alarms, settings, diagnostics. `SnipeGuard` (single snipe / single OTP request per process) is at the top. |
| `AlarmScheduler.kt` | Works out the next release (Europe/London) and sets the exact pre-warn alarm. |
| `AlarmReceiver.kt` | Shows the pre-warn notification, sets the next alarm, and re-arms after reboot. |
| `OtpListenerService.kt` | Notification listener that extracts the OTP from Prenot@mi emails (`OTP Code:`) into `OtpHolder`. |
| `NtpClient.kt` | Minimal SNTP client for the clock offset. |
| `Js.kt` | All injected JavaScript (snipe click, outcome poll, OTP, calendar snapshot/day/slot/BOOK, modals, document-start net/modal hook). |
| `Logger.kt` | Thread-safe `evidence/log.txt` (rotates at 5MB), off-thread PNG screenshots, `evidence/html/` snapshots. |
| `Prefs.kt` | SharedPreferences-backed settings (defaults listed below). |
| `assets/mock/` | Offline mock of the whole flow (services list with the sold-out popup -> booking form with OTP and FORWARD -> calendar with real DOM classes, fake jQuery AJAX, a failing slot and a working one -> confirmation page) for emulator testing. |

## Using the app

1. Install the APK (see Build). Grant notification permission, "Alarms & reminders", and
   **Notification access** (needed for auto-OTP).
2. Tap **LOGIN** and sign in once. The session persists, but it expires, so log in again a few
   minutes before each release.
3. **Long-press SNIPE NOW** on the Services page to pick the service. This stores
   `bookingId=/Services/Booking/422`.
4. **SETTINGS**: keyword, parallel sessions (1–4, applied after relaunch), max waves, retry
   interval, pre-warn minutes, release time and days, Force English, Auto-OTP, Auto-book, Armed.
5. Keep the app open and in the foreground from ~T−3 min. The countdown only runs in the activity.
6. **Long-press the status bar** for diagnostics, the recent log and *Share log*.
   **Long-press SETTINGS** to dump the current page text to the log.

Defaults: 1 session, 40 waves, 400 ms interval, 3 min pre-warn, Mon+Wed 17:00, everything on
except Armed (the alarm sound and network capture are on too).

Release-day checklist: log in about 5 min before; set parallel sessions to 1; make sure notification
access is on and alarm volume is up; keep the app in the foreground.

Testing offline: see *Emulator testing* below.

## Build and deploy

Needs a 64-bit JDK 17–21. The system `JAVA_HOME` (JDK 22) and the Temurin 17 under
`Program Files (x86)` (32-bit) don't work; use Android Studio's JBR:

```
set JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr
gradlew.bat assembleDebug
```

The output is `app/build/outputs/apk/debug/prenot-mi-v<N>-debug.apk`. Copy it to
`G:\My Drive\italian_citizenship\` (Google Drive), where the phone installs from.

Each release bumps `versionCode` **and** `versionName` in `app/build.gradle`. The version appears in
the APK file name and in the status bar (`vN · …`). Commit messages use `vN: <what changed>`.

## Emulator testing

The AVD is `Medium_Phone_API_36.1`. Another tool on this PC keeps starting an old adb (v40) on the
default port, so use a private adb port: in PowerShell, `$env:ANDROID_ADB_SERVER_PORT='5039'` and the
SDK adb `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

1. Install the APK and grant notification access:
   `adb shell cmd notification allow_listener com.szurgot.prenotasniper.debug/com.szurgot.prenotasniper.OtpListenerService`
2. Set the target: push a `shared_prefs/prenota.xml` with `bookingId=/Services/Booking/101` (via
   `run-as`), or long-press SNIPE on the mock page.
3. Start the app on `file:///android_asset/mock/services.html?reset=1&sold=2` (the citizenship row is
   sold out for the first 2 clicks) and tap SNIPE NOW. Or start it on
   `file:///android_asset/mock/BookingCalendar.html` to test auto-book on its own.
4. Inject the OTP: `adb shell "cmd notification post -t 'Prenot@mi' tag1 'Your OTP Code:123456'"`.
5. Read `/sdcard/Android/data/com.szurgot.prenotasniper.debug/files/evidence/log.txt`.

Expected: SOLD_OUT x2 -> BOOKING_PAGE -> OTP_REQUESTED -> OTP_FILLED -> FORWARD_CLICKED -> CALENDAR ->
SLOT_FREE (day 12, fails with "non è più disponibile") -> SLOT_FREE (day 14) -> JS CONFIRM ->
BOOKED_LIKELY. Scheduled firing can be tested by writing `armed=true` and a release time 2 minutes
ahead (Europe/London) into the prefs.

## Evidence

On the phone: `Android/data/com.szurgot.prenotasniper.debug/files/evidence/`. Copies are kept in
`G:\My Drive\italian_citizenship\app\evidence_YYYYMMDD\`. The latest is
`evidence_20261125` (v13, 14 Oct – 23 Nov 2026). Screenshot names are
`yyyyMMdd_HHmmss_SSS_<tag>.png`, with tags `waveN_SOLD_OUT`, `waveN_NO_SERVI`, `SUCCESS`, `calendar`,
`cal_mN`, `calendar_noavail`, `BOOKED`.

`log.txt` contains page dumps with personal details and OTP codes. Treat it as private.

## Known limitations / unknowns

Still unknown about the live site (v14 captures them; see `docs/evidence-analysis-2026-11.md`):
- the BOOK button's real markup and what follows it (confirm, modal, extra OTP, final URL);
- why FORWARD sometimes returns to `/Services`;
- the calendar AJAX endpoint;
- the full sold-out text in Italian.

Other limitations: all WebViews share one cookie jar (one server session), and the OTP depends on
Gmail delivery speed (usually ~5s).

## Disclaimer

For personal use with your own account. Automated access may conflict with the portal's terms of
service. Use responsibly and at your own risk.
