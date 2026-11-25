# Evidence analysis — v13 runs, Oct–Nov 2026

Source: `G:\My Drive\italian_citizenship\app\evidence_20261125\` (`log.txt` plus screenshots),
produced by v13. Release slot is Mon/Wed 17:00 Europe/London, service `/Services/Booking/422`
(Citizenship by descent / "Citizenship by Birth").

## Run-by-run

| Date | Reached booking page | OTP mail arrived | FORWARD clicked | Result |
|---|---|---|---|---|
| 14 Oct | — | — | — | `NO_SERVICE` on both sessions at T+0.4s, stopped |
| 19 Oct | never | — | — | 10 waves of "SOLD_OUT", each ~7.4s apart (see finding 3) |
| 21 Oct | never | — | — | same as 19 Oct |
| 26 Oct | — | — | — | app opened at 17:00:42, too late |
| 2 Nov | T+0.4s | **T+117s** (slow Gmail) | — | page had already been left; `NOFIELD` |
| 9 Nov | T+0.85s (4 snipes running at once) | T+7.3s | T+10.2s | **"Incorrect Code!" ×5**: two "send new code" clicks |
| 11 Nov | T+1.0s (6 snipes running at once) | T+7.7s | T+12.6s | bounced back to `/Services` at T+14.7s |
| 16 Nov | T+0.6s, lost; T+2.8s on session 1 (5 snipes running) | T+5.9s | T+9.0s | **calendar at T+10.6s; a FREE slot was on screen at T+17.4s but wasn't clicked** |
| 23 Nov | T+0.87s (one snipe, clean) | T+7.8s | T+10.4s | bounced back to `/Services` at T+11.3s |

The step from the services list to the booking page works: the booking page loads within a second
of 17:00. Everything that loses happens later.

## Findings, by impact

### 1. Auto-book missed a free slot on 16 Nov (the most important finding)

At 17:00:17.4 the calendar showed **May 2027: day 4 selected, with time slots 10:31–10:44,
10:45–11:00 and 11:30–11:45 each showing `(1)` free**. See `20261116_170017_422_cal_m6.png` and the
CALENDAR DUMP right after it in the log. The scanner logged `NODAY` and moved on.

Why it missed:
- `FIND_GREEN_DAY_JS` looks for an "avail"/"green" class or a green background. The bookable day's
  real markup is `<td class="day active">` with a **blue** background `rgb(51,122,183)`: the site
  auto-selects the first bookable day when the month renders. Days that are full or not offered all
  carry `disabled`.
- Even if a day had been found, `BOOK_BAND_JS` searches for the BOOK button by "contains 'book'".
  The first match in page order is the **"Book" link in the site's nav menu**, which would have
  navigated away from the calendar.

Correct selectors (from the real DOM, see `site-notes.md`):
- bookable day: `td.day:not(.disabled):not(.old):not(.new)`. The site already selects it
  (`.active`) after each month renders.
- free time slot: `.fascia:not(.notAvailableFascia)`, with the count `(n)` in its text > 0.
- BOOK button: exact text `BOOK`/`PRENOTA` (or a submit button) **outside** `header`/`nav`.
  Its id is still unknown, so capture `outerHTML` of the button area on the next calendar visit.

Also, scanning takes ~950 ms per month because of fixed waits. The free month was the 7th, found
7s in. Wait for the day cells to re-render (poll every ~100 ms), not a fixed 800–900 ms.

### 2. Several copies of the snipe run at once (duplicate activity instances)

The log shows 4–6 `SNIPE START (scheduled)` lines within ~100 ms on 9, 11 and 16 Nov. It also shows
several `NTP offset` lines before 17:00, i.e. `onCreate` ran several times. Old `MainActivity`
instances are never cleaned up: there is no `onDestroy`, their `Handler` runnables stay queued and
their WebViews stay alive. Each one then fires its own snipe, and they all share **one cookie jar,
i.e. one Prenot@mi server session**.

Consequences seen in the log:
- 9 Nov: two instances each clicked "SEND NEW CODE"; the second code invalidated the first →
  "Incorrect Code!" ×5. **This lost a run in which the booking page had been reached.**
- 16 Nov: a second instance clicked "send new code" again at T+4.4s on the shared server session,
  resetting the OTP. (Correction after review: the instance whose session 0 reached the booking
  page at T+0.76s kept its page and is the one that reached `/BookingCalendar`. Each instance has
  its own WebViews; they only share cookies.)
- The root trigger isn't proven: on 11 Nov there were 6 `onCreate`s in 76s, together with a user
  STOP. Any destroy and recreate within the process leaks timers. Log `onCreate` and `onDestroy`
  with an instance id and `isChangingConfigurations` to find it.
- Dozens of spurious `NO_SERVICE` / `SNIPE STOP` lines.

Likely cause: an activity recreation for a config change not listed in `android:configChanges`
(e.g. `uiMode`, `screenLayout`, `smallestScreenSize`, `density`), with the lock-screen,
full-screen-intent and `showWhenLocked` flow as a probable trigger. Fix all three:
1. `onDestroy`: cancel the countdown and polls, set `sniping=false`, `destroy()` the WebViews.
2. A process-wide guard (an `AtomicBoolean` in an `object`) so only one snipe runs per process.
3. Widen `configChanges`.

23 Nov had a single instance and ran cleanly.

### 3. The sold-out popup text isn't recognised, so each wave waits out the 6.75s timeout

On 19/21 Oct each "SOLD_OUT" arrived ~5.5–6.2s after the page loaded, which is the poll timeout
(45 × 150 ms). The screenshot shows the real English popup: **"All appointments for this service
are currently…"**. `STATE_JS` only matches `sold out` / `high demand` / `esaurit` /
`elevata richiesta`. Add `all appointments`, `currently unavailable` and the Italian equivalent
(capture the exact text with a dump). Waves would then drop from ~7.4s to ~1–2s. Also log
timeouts as `TIMEOUT`, not `SOLD_OUT`: the log is meant as court evidence.

### 4. The OTP path has ~3s of avoidable delay

Timeline for 23 Nov: booking page at T+0.87 → OTP requested at **T+2.5** (fixed 1500 ms delay plus
the poll) → mail at T+7.8 → filled at T+8.0 → submitted at **T+10.4**.
- Click "SEND NEW CODE" as soon as it exists: poll every ~100 ms from the moment
  `/Services/Booking/` loads. Saves ~1.5s.
- Poll `OtpHolder` every 100–150 ms instead of 700 ms, and submit right after the fill with no
  500 ms delay. Saves ~1s.
- The ~5s Gmail delivery time is now the main cost and is outside the app's control. Keep Gmail
  sync and notifications at high priority; on 2 Nov the mail took 117s.
- Only **one** OTP request per booking page (see finding 2). Never click "send new code" twice.

### 5. Bounce to `/Services` after FORWARD (11 and 23 Nov) isn't understood yet

After FORWARD the site sometimes returns to `/Services` instead of `/BookingCalendar`. It is **not** simply "nothing left": on 5 Oct
(`evidence_20261007/log.txt` line 1291) FORWARD reached a calendar with zero availability. The cause
is unknown because nothing is captured once `sniping=false` (`MainActivity.kt:200`). On any page load
after the OTP submit, log the body text and any alert or modal text and take a screenshot.

### 6. Session 1 always returns `NO_SERVICE` on wave 1

Every run: `PAGE LOADED[1] /Services` and then `NO_SERVICE` ~10 ms later. The screenshot is blank.
Session 1 is cold (never loaded before 17:00), and the page carries a bot-manager script
(`stormcaster.js`, `__uzdbm_*`) and Cloudflare Turnstile. The first `onPageFinished` probably fires
before the real table exists. Fixes:
- pre-warm every session by loading `/Services` at T−30s;
- in `clickJs`, retry finding the link for ~1s before reporting `NO_SERVICE`.

Note that both sessions share one server session (one cookie jar), so the second session adds
little and can interfere. Consider defaulting to **1** session until the cookies are separated.

## 25 Nov 17:00 live run (v14): re-login loop, then too late

Evidence: `evidence_20261125_1`. Timeline (T = 17:00:00 London):

| Time | What happened |
|---|---|
| 16:13 | Logged in, services list OK. App left idle until 16:59. |
| T-60s | Pre-warm loaded `/Services` and got `/Home?ReturnUrl=%2fServices` (the session had **expired while idle**). Alarm "log in now". |
| T-55s ... T-10s | **Re-login loop.** User taps LOGIN and goes to `iam.esteri.it`; the activity pauses and resumes (keyboard or password manager). v14 reset `prewarmed=false` in `maybeStartCountdown()` on every resume, so each resume re-ran the pre-warm, which navigated **away from the login page** back to `/Home?ReturnUrl` and sounded the alarm again. This happened 6 times. The notification created instance #3, but the loop carried on. It stopped only once the countdown passed T-15s. |
| T-5s | User finished logging in by hand (`/UserArea`, then `/Services`). |
| T+18ms | BOOK clicked. `/Services/Booking/422` -> **`/UserArea`** (twice). No popup, so v14 waited out the timeout. |
| T+6.8s | `wave 1 -> TIMEOUT` (**7s lost**). |
| T+8.4s | Wave 2: booking page. SEND NEW CODE at T+8.5s. |
| T+13.0s | OTP arrived by email (4.5s); filled; JS confirm accepted; FORWARD at T+13.2s. |
| T+14.2s | **Bounced to `/Services` with "All appointments for this service are currently booked."** |

Conclusions:
1. The re-login loop was an app bug: the pre-warm was not "once per release".
2. The session expires in well under 45 min idle. A keep-alive is needed, and the login check
   should come early enough to log in calmly.
3. The bounce after FORWARD is the sold-out popup, i.e. we were too late. Without the 7s redirect
   timeout, FORWARD would have been at about T+6s.

### v15 changes
- Pre-release steps run **once per release, process-wide** (`PreRelease.done`), so resuming or
  recreating the activity can't repeat them:
  - keep-alive reloads of the services list at T-10 and T-5 min;
  - session check at T-3 min (was T-60s);
  - fresh pre-warm at T-20s.
- These steps **never navigate a session that is on `iam.esteri.it`** or mid-booking.
- After an expiry warning, a finished login (`/UserArea`) is moved on to the services list
  automatically.
- Logged-out detection also matches `/Home...` and `/Account/Login`.
- During the snipe, a page outside `/Services` after the click is decided at once: `LOGGED_OUT` stops
  with a login alarm; otherwise `REDIRECT` retries after 200ms instead of a 7s timeout.
- The sold-out key now includes `currently booked`. The modal selectors include `.jconfirm-box` and
  `.app-modal`, so the bounce popup is logged as `MODAL`.
- Mock: `services.html?reset=<token>&sold=N&redir=N` (`redir` simulates the 25 Nov `/UserArea`
  redirect; the reset is one-shot per token because retries reload the same URL).
  `mock/UserArea.html` was added.
- Emulator: the full flow passed (REDIRECT, then SOLD_OUT, then booked). A scheduled run, with the app
  left and resumed twice after the T-3 min check, ran each pre-release step exactly once and fired
  on time.

### Advice for the next run
Log in **5-10 minutes before release** and leave the app in the foreground. The keep-alive keeps the
session warm. If the T-3 min alarm sounds, log in: the app no longer interferes.

## v14 status

Everything below and in the review addendum is implemented in v14 (25 Nov) and passes the
emulator mock flow end to end. Only the unknowns at the bottom remain; they need a live release
run.

## Suggested v14 scope (in priority order)

1. Calendar: correct day, slot and BOOK-button selectors; event-driven scanning; dump the BOOK
   button `outerHTML`.
2. Single-instance guard, `onDestroy` cleanup, wider `configChanges`.
3. Recognise the sold-out popup text; separate `TIMEOUT` outcome.
4. OTP fast path: immediate request, 100 ms polls, immediate submit, one request only.
5. Capture what happens after FORWARD (body text, modal, screenshot).
6. Pre-warm sessions at T−30s; retry the link lookup for ~1s.

## Review addendum (background review, 25 Nov)

Confirmed: the 4 May 2027 slot was real (`td.day active`, `(1)` slots appear only in that month's
dump, green dots in `cal_m6`). The sold-out waves were pure timeouts (0.25s + 45×150ms).

Additions to the v14 scope:
- **Book by explicit selection.** For each `td.day:not(.disabled):not(.old):not(.new)`: click it and
  wait for `.events-content` to change → click a free `.fascia` → click the button whose exact text
  is PRENOTA/BOOK, outside `header, nav`. The 5 Oct alert *"Non è stata selezionata nessuna fascia!
  … cliccare su PRENOTA"* confirms a slot must be selected first. On that alert, or if a slot has
  vanished, try the next slot, then the next day.
- **After BOOK.** Override `onJsConfirm`/`onJsPrompt` (log and accept). Poll ~10s for HTML modals
  (`.modal.show`, `.swal2-popup`, `.sweet-alert`), dump them and confirm. Report BOOKED only when the
  URL moves to `/Reservation`/`/UserArea`, confirmation text appears, or the confirmation email
  arrives. `SELECTED_NOBOOKBTN` must not count as success.
- **No false SUCCESS on 5xx pages.** On 9 Nov a 502 page at Booking/422 counted as SUCCESS. Require
  the OTP field or SEND NEW CODE to be present; treat 5xx as reload-after-300ms.
- **Sold-out signal:** the click reloading `/Services` (~1.5s) means sold out, even without the
  popup text.
- **NO_SERVICE:** retry the link lookup for ~1.5s (session 0 also failed after fresh loads).
- **Human fallback:** alarm sound plus vibration on calendar load and when a free slot is seen;
  leave the selection on screen if auto-book fails. Re-run auto-book on every calendar load (not
  once per snipe), and add a manual trigger.
- **Release-day reliability:** `FLAG_KEEP_SCREEN_ON` near release; at T−90s check the session (a
  redirect to `Home?ReturnUrl` or `iam.esteri.it` → alarm "log in now"); `onRenderProcessGone`
  handler; one OTP request per server session, process-wide; log whether `cf-turnstile-response`
  is present before FORWARD.

Diagnostics design:
- `STAGE <name> T+<ms>` marks relative to the NTP-corrected release.
- Always-on page-chain logging on prenotami pages (started/finished, title, first 300 characters of
  body), regardless of `sniping`.
- For 3 min after reaching the booking page: full HTML snapshots (scripts stripped) to
  `evidence/html/<ts>_<tag>.html` at every load, alert, modal and before/after FORWARD and BOOK.
  This replaces the useless 4 KB head dump.
- Document-start script (`WebViewCompat.addDocumentStartJavaScript`, prenotami origin only)
  wrapping XHR/fetch to log URL, status, timing and the first 4 KB of the response, plus a
  `MutationObserver` that logs newly shown modals. This reveals the calendar availability endpoint.
- Logger: synchronized, `DateTimeFormatter`, filter console spam, rotate at 5 MB, encode PNGs off
  the UI thread, lifecycle logs with an instance id.

Unknowns only a live run can answer: the BOOK button markup and what follows it; the cause of the
FORWARD → `/Services` bounce; the calendar AJAX endpoint and payload; the full sold-out text (EN/IT);
whether clicking a `.fascia` changes its class; whether Turnstile gates FORWARD/BOOK; the activity
recreation trigger.
