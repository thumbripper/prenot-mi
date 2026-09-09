# Prenot@mi Sniper

An Android app that helps secure hard-to-get Italian citizenship (jure sanguinis) appointment
slots on [Prenot@mi](https://prenotami.esteri.it) — built for the Consulate General of Italy in
London, where citizenship slots are released twice a week and taken within seconds.

It does **not** bypass or attack the booking system. You log in yourself, and the app assists with
timing and clicking; you complete every booking (day/time selection and the OTP) by hand.

## What it does

- **Pre-warns you** with a full-screen notification a few minutes before a release window
  (default Mondays & Wednesdays 17:00 Europe/London — configurable).
- **NTP-synced countdown** fires the attempt at the true release instant, independent of device
  clock drift.
- **Auto-clicks the BOOK link** for your chosen service in a WebView, then detects the
  "Given the high demand... sold out" popup (both HTML-modal and native-alert variants).
- **Fast retry loop** on sold-out (interval and max attempts configurable).
- **Hands over to you** — vibrate + banner — the moment a bookable page loads, so you finish the
  booking and enter the OTP.
- **Logs every attempt with a timestamped screenshot** to the app's `evidence/` folder. This
  doubles as evidence of booking impossibility for a "denial of justice" court filing.

## Setup

1. Build/install the debug APK (`app/build/outputs/apk/debug/app-debug.apk`) or sideload it.
2. Tap **LOGIN**, sign into Prenot@mi once (the session persists).
3. **SETTINGS** → set the *service keyword* to match your service's table row, the release
   days/time, and tick **Armed**. Grant notification + "Alarms & reminders" permissions.
4. On the Services page, long-press **SETTINGS** to dump the page text to the log — useful for
   calibrating the keyword and detection selectors.
5. **SNIPE NOW** tests an attempt immediately.

## Build

Requires a 64-bit JDK 17–21 (e.g. Android Studio's bundled JBR).

```
gradlew.bat assembleDebug
```

## Configuration notes

- Detection selectors and the sold-out text are tuned for the London consulate's current
  Prenot@mi UI and may need adjustment for other headquarters or after site changes.
- v1 is single-account / single-session; parallel sessions are planned.

## Disclaimer

For personal use with your own account. Automated access may conflict with the portal's terms of
service; use responsibly and at your own risk.
