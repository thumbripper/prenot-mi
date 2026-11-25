# Prenot@mi site notes (London consulate)

Facts about the live site learned from real runs. Update this file whenever a dump shows something
new. Last confirmed: November 2026 (v13 logs).

## Flow and URLs

1. `https://prenotami.esteri.it/`: homepage with the login button. Hitting `/Login` cold returns
   HTTP 500, so always start at the homepage.
2. Login happens on `https://iam.esteri.it/signin/...` (OAuth2, client `prenotami-fo`), then
   redirects to `/UserArea`. The session cookie persists in the WebView.
   - **The session expires when idle.** On 25 Nov a 16:13 login had expired by 16:59, and
     `/Services` redirected to `/Home?ReturnUrl=%2fServices`, which is the logged-out signal.
     v15 reloads `/Services` at T-10 and T-5 min to keep the session alive, and checks it at T-3 min.
3. `/Services`: a DataTables list of services. Our row: **CITIZENSHIP / Citizenship by descent /
   Citizenship by Birth**, BOOK link `href` contains **`/Services/Booking/422`** (the same in ITA
   and ENG).
   - Before release, or when sold out, clicking BOOK shows a modal on `/Services`:
     **"All appointments for this service are currently booked."** (English UI). It is a
     **jquery-confirm** popup (`/Content/jquery-confirm.css`, `.jconfirm-box`). The text also sits in a
     hidden input on every `/Services` page:
     `<input id="WlNotAvailable" type="hidden" value="All appointments for this service are currently booked.">`.
     Because it's an input `value`, it isn't in `innerText`, so matching body text is safe. The older
     strings `sold out` / `high demand` / `esaurit` were not seen in v13 runs.
   - **At release, BOOK can redirect to `/UserArea`** (25 Nov, click at T+18ms):
     `/Services/Booking/422` loaded, then `/UserArea` loaded twice (ITA, then ENG). There was no
     popup, so v14 waited the full 7s timeout. v15 treats any non-`/Services` page after the click as
     `REDIRECT` and retries after 200ms. The reason is unknown (perhaps the service wasn't open yet,
     at T+18ms).
4. `/Services/Booking/422`: the booking form. Body text in English:
   `Booking Type: Individual booking · Notes for the Embassy/Consulate · OTP · SEND NEW CODE ·
   Privacy Policy [checkbox] I have read and accept the Privacy Policy · BACK TO LIST · FORWARD`.
   - The OTP is **not** sent automatically: "SEND NEW CODE" must be clicked. Each click invalidates
     the previous code, and a wrong code gives the JS alert `Incorrect Code!`.
   - The OTP arrives by email from Prenot@mi; the body contains `OTP Code:NNNNNN` (6 digits).
     Gmail delivery is usually ~5s and once took ~2 min.
   - Cloudflare Turnstile runs on this page (`turnstileCallback` appears in the console ~0.1s
     after SEND NEW CODE).
5. FORWARD first shows a JS `confirm()`: *"Do you want to confirm this appointment? Individual
   booking. You are booking for 1 Appointment <name>"*. The app accepts it. The page then goes either
   to `/BookingCalendar?selectedService=Citizenship%20by%20Birth` or back to `/Services`.
   - **The bounce to `/Services` means sold out.** On 25 Nov (FORWARD at T+13.2s) `/Services`
     reloaded with the jquery-confirm popup **"All appointments for this service are currently
     booked."** (screenshot `bounce_1200`). So passing the OTP is not enough: the availability
     check happens again at FORWARD. Speed to FORWARD is what matters (seen 11, 23 and 25 Nov).

## Calendar DOM (`/BookingCalendar`)

- Built by `/Content/js/Calendar/ManageCalendar.js` with a bootstrap/moment datetimepicker.
  Availability arrives by AJAX (`success` handler → `CostruisciTimelineOrizzontale`), so content
  appears some time after `onPageFinished`. The first dump right at load was empty; ~1s later it
  was populated.
- Month header like `novembre 2026` (always Italian), with `<` / `>` arrows.
- Day cells are `<td>` elements with classes:
  - `day old` / `day new`: days from the neighbouring months (ignore)
  - `day disabled`: not configured for this service (grey)
  - `day weekend disabled`
  - `day disabled notAvailableDay`: configured but **full** (red, `bg rgb(255,0,0)`)
  - **`day active`** (no `disabled`, blue `rgb(51,122,183)`): **bookable, and auto-selected** by
    the site when the month renders. This is what to look for.
  - `day active today disabled`: today
- Legend: green = Available, red = Not available, blue = Day selected, grey = Days not configured.
- Time slots ("fasce") for the selected day, below the calendar inside
  `div.events-content > li.selected`:
  - `<div class="fascia notAvailableFascia">10:15 - 10:30 (0)</div>`: full
  - **`<div class="fascia ">10:45 - 11:00 (1)</div>`**: free, `(n)` = places left
  - Seen times: 10:15–10:30, 10:31–10:44, 10:45–11:00, 11:01–11:29, 11:30–11:45.
- Configured days seen: Tuesdays and Thursdays. On 16 Nov at T+17s, Mar/Apr 2027 were all red and
  **4 May 2027 was bookable with 3 free slots**.
- The BOOK button is a blue button below the legend. **Its exact selector is still unknown.** The
  site header also contains a "Book" nav link, so never match on "contains 'book'".

## Release pattern (which dates come out)

Only two calendar views exist so far, and they agree exactly:

| Release | Last configured day seen | Gap |
|---|---|---|
| Mon 5 Oct 2026 (photo `calendar_20261005.jpeg`) | Tue 23 Mar 2027 | 169 days = 24 weeks + 1 day |
| Mon 16 Nov 2026 (v13 dump) | Tue 4 May 2027: the only open day; 3 of 4 slots free at T+10s | 169 days = 24 weeks + 1 day |

- Appointment days are only **Tuesdays and Thursdays**. Every earlier configured day was full (red,
  all slots `(0)`): no cancellations showed up.
- Hypothesis: **each release opens one new day, 24 weeks + 1 day ahead**. A Monday release opens the
  Tuesday; a Wednesday release opens the Thursday (not yet seen). That day has ~4 slots of 1 place.
- Predicted: Wed 25 Nov -> Thu 13 May 2027; Mon 30 Nov -> Tue 18 May; Wed 2 Dec -> Thu 20 May.
- So the target is about **6 months ahead** (month +6 in the calendar). Scanning from the current month
  cost ~6s on 16 Nov.
- v16 jumps straight there and logs `STAGE CAL_PREDICTED <date> bookable=true|false`. **Check that
  line after each run** and correct `PREDICT_DAYS` in `MainActivity` if the rule is off. The
  Wednesday rule is still unconfirmed.

## Anti-bot / infrastructure

- Cloudflare Turnstile (`challenges.cloudflare.com/turnstile`), the "GOOGLE CAPTCHA ENTERPRISE"
  marker, and a bot-manager script `/<uuid>/stormcaster.js` with `__uzdbm_*` variables.
- Under load the site returns HTTP 502/503 for a few seconds (seen at 16:58 on 9 Nov).
- Matomo analytics; lots of harmless console noise (`Error: x`, Permissions-Policy, CSP `data:,`).
