# CLAUDE.md

Android (Kotlin, single module `app/`) app that books a Prenot@mi citizenship appointment for the
London consulate at the twice-weekly release (Mon/Wed 17:00 Europe/London). Start by reading:

- `README.md`: what the app does, code map, build and deploy, evidence locations.
- `docs/site-notes.md`: **ground truth about the live site** (URLs, page text, calendar DOM
  classes, OTP behaviour, anti-bot). Selectors in injected JS must match it.
- `docs/evidence-analysis-2026-11.md`: latest run analysis and the prioritised v14 to-do list.

## Working rules for this repo

- Logic is in `MainActivity.kt` (snipe, OTP, `AutoBooker`, capture); all injected JS is in `Js.kt`
  as Kotlin raw strings, where a literal `$` must be written `${'$'}`.
- Build with `JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr` (the other installed JDKs
  fail).
- Test every change on the emulator against `assets/mock/` before shipping (README *Emulator
  testing*; use `ANDROID_ADB_SERVER_PORT=5039`). Keep the mocks in line with `docs/site-notes.md`.
- Every build the user installs: bump `versionCode` and `versionName` together in
  `app/build.gradle`, build with `gradlew.bat assembleDebug`, then copy
  `app/build/outputs/apk/debug/prenot-mi-v<N>-debug.apk` to `G:\My Drive\italian_citizenship\`.
  Commit as `vN: <summary>`.
- Evidence from the phone is in `G:\My Drive\italian_citizenship\app\evidence_YYYYMMDD\`
  (`log.txt` plus PNGs). Analyse the newest folder first; `grep -a` because the log has binary-ish
  bytes. Useful markers (v14+): `STAGE ` (timed milestones), `SNIPE START`, `wave N session`,
  `CAL month=`, `NET[`, `MODAL[`, `JS ALERT`, `JS CONFIRM`, `PAGE[` (title + text), `PRENOTAMI NOTIF`,
  `LIFECYCLE`, `ALARM`. Full page snapshots are in `evidence/html/`. (v13 logs use `CALENDAR DUMP`,
  `DAY `, `BAND `.)
- The real site can only be exercised at release time on the user's phone. Verify JS changes
  against the DOM recorded in `docs/site-notes.md` and the log dumps, and add diagnostics (dumps,
  screenshots) for anything still unknown, so the next run produces the missing facts.
- After each analysis, update `docs/site-notes.md` with new site facts and add or refresh an
  analysis doc in `docs/`.
- The evidence log and the Drive folder hold personal documents and data. Never send them to
  external services.
