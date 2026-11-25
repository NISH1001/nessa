# Nessa: design (v1)

Status: **approved by owner 2026-11-23** (tab layout A and presence mockup approved)

## Why
Nessa is a personal, FOSS Android app named after the cat. It's the app the official Whisker app should
have been. v1 controls the Litter-Robot EVO. The app is a home for other cat things later (food, vet,
meds), so the litter robot is one feature inside it, not the whole app.

The core need: **the robot should only run automatically when the owner is home.** When nobody is home
it should not cycle, but cat visits and weights should still be recorded. On top of that, the owner
wants several sleep windows per day, set per day, which Whisker's app can't do.

## Goals
- Robot runs only when the owner is **home** and **outside every sleep window**.
- **Several sleep windows per day**, set separately for each day of the week, plus one-off date overrides.
- **Presence** from home Wi-Fi and/or a home area (location), reacting within about a minute.
- **One place for everything:** status, schedule, presence, robot settings, history.
- **Looks good and is intuitive:** a polished consumer app, *not* a developer tool (unlike aa-rescue).
- FOSS (MIT), no ads, no analytics, no Google Play Services. Sideloaded onto a Pixel 9.

## Non-goals (v1)
- Other cat features (food, vet…): the structure leaves room, but nothing is built.
- Light scenes (lights change automatically with the robot's state): later.
- Camera, firmware updates, other Whisker robots, multiple robots, iOS, Play Store.

## Anti-goals
- **No personal information in the repo, ever:** no email, credentials, tokens, robot serial, account or
  pet ids, Wi-Fi names, IP addresses, location, or timezone. Test data is made up. All of that lives
  only on the phone, encrypted where it is secret.
- Never sends anything to Whisker's servers that the owner didn't trigger directly or through a rule.
- Never leaves the robot in an unknown state silently. Every failure is visible (notification + log).
- No feature should feel like a debug screen. Technical detail goes behind "details".

## Approaches considered
| Approach | Verdict |
|---|---|
| Termux + MacroDroid/Tasker running `pylitterbot` | Rejected. FOSS-only means polling every 15–30 minutes, which is too slow. It's 3 apps, and MacroDroid shows ads. |
| Android app embedding Python via Chaquopy + `pylitterbot` | Rejected. Heavy (boto3, native crypto and aiohttp builds), large APK, slow cold start, for about 6 calls. |
| **Native Kotlin app, porting the few calls from `pylitterbot`** | **Chosen.** Small, fast, fully under the owner's control, and extendable. Cost: when Whisker changes something (about 1–2×/year historically, mostly sign-in), copy the fix from `pylitterbot` by hand. |

## Core rule (revised with the owner 2026-11-23)
Presence has priority; the schedule is the fallback and also carries "quiet" windows.

| Situation | Robot |
|---|---|
| Presence says **away** | Rests |
| Presence says **home**, inside a window marked "rest even when I'm home" (Bedtime) | Rests |
| Presence says **home**, inside any other window (Work, Gym) | Runs: presence wins |
| Presence says **home**, outside every window | Runs |
| Presence **off or unavailable** (turned off, permission missing) | Schedule alone decides: rests inside any window |

A manual override (Auto / Home / Away) replaces presence while active.

- **Rest** is carried out as **Sleep** (default: no cleaning, visits and weights still recorded) or
  **Power off** (nothing runs, nothing recorded). One setting on the Litter tab covers windows and away.
- The robot's *native* schedule always holds the "rest even when I'm home" windows, so nights still
  work if the phone is dead. The phone handles everything else.

## Schedule (lives on the Litter tab)
- Day strip Mon–Sun; pick a day to see and edit its windows. Each window: **name**, start, end,
  and the switch **"Rest even when I'm home"**. A window may cross midnight. Overlaps merge.
- "Copy to other days". One-date overrides come later.
- A day-at-a-glance bar shows resting time shaded.
- Defaults on first run (all editable): **Bedtime** 10:00 PM → 7:00 AM every day (sleep even when
  home) and **Work** 9:00 AM → 5:00 PM Mon–Fri. The owner's real week is entered on the phone and
  stays there.

## Presence
- Signals (each can be turned on or off): **Home Wi-Fi** (by name, or by network id) and **Home area**
  (a pin set with "use my current location", radius 100/200/500 m, using Android's `LocationManager`).
- **Home** if *any* enabled signal says home. **Away** only when *all* say away for the "Away after"
  wait, set with a slider that snaps to 1 / 2 / 5 / 10 / 15 min (default 5).
- **Manual override:** Auto / Home / Away, optionally for a set time ("Home for 3 days" for a cat-sitter).
- Runs as a **foreground service** (permanent, quiet notification: "Nessa · Home · Litter awake").
  Needs location set to *Allow all the time* (for the Wi-Fi name and the home area), notifications, and
  battery set to Unrestricted. Setup walks through each.

## Putting the robot to sleep / waking it
The robot stores one window per day (`sleepSchedules`: 7 entries `{dayOfWeek, isEnabled, sleepTime,
wakeTime}`, minutes from midnight in the robot's own timezone, which the API reports).
- **Sleep now:** PATCH the schedule so today's entry covers now until the next time Nessa wants it
  awake (or end of day; later days are rewritten as needed).
- **Wake:** PATCH back the *baseline* (the night window on every day).
- ✅ **Verified 2026-11-23 on the EVO:** the robot obeys a schedule change right away. After a PATCH
  putting "now" inside today's window, `state.isSleeping` became `true` and
  `state.statusIndicator.type` became `SLEEP_MODE`; the Whisker app showed sleep mode on. After the
  original schedule was PATCHed back, the robot reported awake (`READY`) within about 10 seconds.
- ✅ **How the robot reads one day's entry (verified 2026-11-23, two tests):** when `sleepTime` is
  later than `wakeTime`, the entry covers *its own day* twice: from midnight until `wakeTime`, and from
  `sleepTime` until midnight. It does not run into the next day. (pylitterbot's `SleepSchedule.get_window`
  assumes otherwise for this model.) When `sleepTime` is earlier than `wakeTime` it is one stretch
  inside the day. So one native entry can express either one stretch, or "early morning plus evening".
  Bedtime 9 PM → 7 AM every day is simply `{sleepTime: 1260, wakeTime: 420}` on all seven days.
- **Days with more stretches than one entry can hold** (for example a day with: until 8:00, 9:00–5:00,
  from 9 PM): the phone rewrites today's entry at each change so the robot's state is right now and
  stays right until the phone's next change.
- ✅ **Whole-day rest (verified 2026-11-23):** an enabled entry whose `sleepTime` equals its `wakeTime`
  rests the whole day (tried 600/600, 1400/1400 and 0/0). "Away" writes `{isEnabled: true, 0, 0}` on all
  seven days, so the robot stays resting until the phone says otherwise.
- **Read-after-write lags:** a GET immediately after a PATCH can still return the old schedule for a
  few seconds. Confirm a write by polling, not by a single immediate read.
- **Lesson from the test:** the test process was killed mid-run and its backup was then overwritten by
  the already-modified schedule. In the app, a checkpoint must be saved durably *before* the write, and
  the "original" snapshot must never be overwritten.
- ⚠️ **Unverified:** sleep must not stop visit and weight recording. Check the activity log after the first
  real sleep window.

## Whisker API (taken from `pylitterbot` 2025.9.0, `robot/litterrobot5.py`, `session.py`)
- **Sign-in:** AWS Cognito `InitiateAuth`, plain HTTPS JSON to `cognito-idp.us-east-1.amazonaws.com`.
  `AuthFlow=USER_PASSWORD_AUTH` → **verified working 2026-11-23** (no SRP needed).
  `REFRESH_TOKEN_AUTH` → verified. Tokens last 3600 s. User pool and client id: base64 constants in
  `pylitterbot/session.py`.
- Requests send `authorization: Bearer <idToken>`.
- `GET  https://ub.prod.iothings.site/robots` → robot list (EVO reports `type: "LRE"`).
- `GET  /robots/{serial}` → refresh. Has `state`, `sleepSchedules`, `nightLightSettings`,
  `panelSettings`, `litterRobotSettings`, `timezone`.
- `PATCH /robots/{serial}` with a settings key (`sleepSchedules`, `nightLightSettings`,
  `panelSettings`, `litterRobotSettings`…).
- `POST /robots/{serial}/commands` `{"type": "CLEAN_CYCLE" | "POWER_ON" | "POWER_OFF" | …}`.
- Activities: `get_activities` in `litterrobot5.py` (REST, includes `PET_VISIT`). Pets and weight history:
  GraphQL in `pylitterbot/pet.py`.
- All Whisker code lives in **one module** (`whisker/`) that mirrors `pylitterbot`, so fixes are
  easy to copy over.

## Login storage
- Email, password, and refresh token encrypted with an Android Keystore AES key, stored in DataStore.
- Use the refresh token. If it's rejected, quietly sign in again with the saved password. If that
  fails, notify "Reconnect Whisker" and leave the robot as it is.

## Screens (see mockups in `docs/mockups/`, layout only, not visual style)
Tabs: **Home · Litter · Log**, plus a **gear icon** at the top right of every tab that opens Settings
(owner's choice 2026-11-23; replaces the four-tab layout in the mockups)
- **Home:** photo of Nessa, "you" card (home/away + why), litter status in plain words, today's visits
  and weight, override.
- **Litter:** status and reason, Wake now / Clean now, weekly timeline, robot levels → Robot settings
  (night light mode/brightness/color*, panel brightness, keypad lock, wait time).
  *Color unverified on the EVO.
- **Log:** one timeline mixing Nessa's actions ("6:02pm left home → litter resting") and the robot's
  activity (visits with weight, cycles, drawer). Tap for technical detail.
- **Settings:** Connections (Whisker), Presence (Wi-Fi, home area, wait), Schedule baseline, About.
- **First run:** Welcome → connect Whisker → pick robot → home Wi-Fi → home area (optional) →
  permissions → done.

## Visual direction (owner wants it to look good)
Jetpack Compose + Material 3 with dynamic color (Material You on Pixel), warm and friendly, plain
language, a large photo of Nessa, generous spacing, motion that's subtle. A separate visual-style pass
(colors, type, Home feel) happens before the screens are built in detail.

## Tech
Kotlin, Jetpack Compose, Material 3, minSdk 31 / target 36 (the Pixel 9 runs Android 17, SDK 37), Ktor or OkHttp + kotlinx.serialization,
DataStore, Room (log), WorkManager + AlarmManager exact alarms, foreground service
(`location` type). Package `dev.nish.nessa`. Debug-signed APK, sideloaded with adb. MIT license.

## Testing
- Unit tests for the core rule (presence × windows × override × midnight edges) and schedule rewriting.
- Whisker module checked against the live API through a debug screen (hidden) and a Mac-side Python
  script using `pylitterbot` for comparison.
- On the Pixel 9: walk-out/walk-in test, phone idle overnight, reboot.

## Insights (designed 2026-11-24, approved card set)

### Why
Two things the owner wants to watch: **the cat's weight** over time, and **how often she visits**. Many
visits can mean the box isn't clean enough (she goes back to cover the smell) or a health problem
(urinary trouble, kidneys, diabetes). The charts separate those two where the data allows.

### Goals
- Charts on the **Home tab**, below the "You" and "Litter box" cards, in this order:
  1. **Health watch**: a short checklist, ✓ when normal and an amber flag when not. Tapping a flag
     scrolls to its chart. Stays silent ("Learning her usual") until there are 14 days with data.
  2. **Weight**: one dot per reading; a dashed horizontal **median** line; readings more than 15% from
     her usual weight drawn as hollow grey dots and left out of the median.
  3. **Visits per day**: one bar per day; a dashed "usual" (median) line; a bar is amber when the
     day has at least twice the usual. Above the chart: today's **return trips** (a visit within
     15 min of the previous one), split into "before a cleaning" and "after a cleaning".
  4. **Time in the box**: every visit as a dot (duration), a dashed "typical" (median) line.
  5. **Usual times**: a 24-hour strip, one cell per hour, darker for more visits, plus a sentence
     naming the busiest stretches.
  6. **Robot**: cleanings today vs usual; drawer fullness and "drawer full in"; litter level and
     trend; cleanings interrupted and safety stops this week (amber when above 0); filter due date.
- One **range** control above the insights for every chart: 7 days, 30 days, 90 days, All.
- **Interactive and reactive:** tap or drag on a chart shows the exact value; charts update by
  themselves when new data arrives.
- **Days without data are left out** (no bar, no gap filler), since those are days the robot was off.

### Health watch checks (first version)
| Check | Flag when |
|---|---|
| Weight | the 14-day median is 5% or more below the median of the 30 days before that |
| Visits | today, or 2 of the last 3 days, at least twice the usual; the text says whether the box had cleaned before the return trips ("box was clean") or not ("box hadn't cleaned yet") |
| No visits | no visit for 24 hours while the robot was on and awake for most of that time |
| Robot | any safety stop, or more than 2 interrupted cleanings, in the last 7 days |
Wording is observational ("Visits above usual"), never a diagnosis. Thresholds are constants in one
place so they can be tuned.

**"Usual" is a fixed baseline, not the selected range** (designer's call): the median over the 30 days
before today, counting only days with data (for visits: days with at least one recorded visit, since
Whisker misses visits and a cleaning-only day isn't a real zero). Health watch, the amber bars and the dashed "usual" lines
all use it, so switching the range never turns a flag or an amber bar on or off. The range only
changes how much history the charts show.

### Non-goals
- Notifications for insights (Home tab only for now).
- A header with the cat's photo.
- Pee vs poop, waste weight: Whisker leaves these empty on the EVO.
- Google Drive mirroring: later, following the owner's ecosystem protocol (one-writer JSONL files
  under `My Drive/nishparadox/nessa/`, granted with the system folder picker, no Google APIs). Records
  are stored with stable ids and a flat shape so this is a straight export later.

### Data
- **History is kept on the phone in a Room database** (Android's standard on-device SQLite), in the
  app's private storage. Not backed up, removed on uninstall.
- Tables (all keyed by a stable id so re-syncing never duplicates):
  - `activity`: every Whisker activity: type, time, duration, weight, pet ids. Id: Whisker's
    `eventId` (present on EVO activities, seen in real data), else `messageId`, else type + timestamp.
    The `Activity` model must start reading these fields.
  - `robot_reading`: one row per check (about every 15 min): time, drawer %, litter level %, litter
    indicator, sleeping, power, cycle counter. Whisker keeps no history of these.
- **Filter due date** comes from the robot's top-level `nextFilterReplacementDate` field (present on
  the EVO). The `Robot` model must start reading it.
- **Return trips, before or after a cleaning:** decided from Whisker's own history, by whether a
  `CYCLE_COMPLETED` falls between the two visits. Works on history already in Whisker.
  - Nessa's own action log moves here from DataStore (presence changes, robot updates).
- **Sync runs whether or not "Manage sleep" is on** (designer's call): insights get their own
  15-minute background job, separate from the schedule checks, so history and robot readings have no
  gaps when sleep management is off.
- **Sync:** at app start, on pull-to-refresh, and at each 15-minute check, fetch Whisker activities
  page by page until reaching ones already stored (first run: everything, up to the existing 2,000
  cap). Store a robot reading on each status fetch.
- Derived numbers (medians, return trips, flags) are computed from the database in plain Kotlin,
  never stored.

### Approaches considered
| Approach | Verdict |
|---|---|
| **Charts drawn natively in Compose (Canvas), history in Room** | **Chosen.** Full control over the specific marks (hollow outliers, dashed median, amber days, gaps); no new chart dependency; Room is the standard for months of rows and date-range queries. |
| A chart library (Vico) | Rejected: the custom touches fight its defaults; one more dependency to keep working. |
| A web page inside the app for charts | Rejected by the owner. |
| No local history, download from Whisker each time | Rejected: the drawer and litter trends need readings Whisker doesn't keep; slower; blind offline. |

### Constraints
- Weight filter: "usual weight" = median of readings in the selected range that sit within 15% of
  the median of all readings longer than 60 seconds (long visits are the reliable ones). Outliers
  are still shown, hollow.
- All times in the robot's own timezone.
- Medians, never means.
- Same short wording and status colors as the rest of the app; amber for "look at this".
- Charts must be readable in light and dark theme; every mark has a tap target of at least 24 dp.
- No personal data in the repo: tests and screenshots use made-up history.

### Left to the builder
Chart drawing details (axis ticks, spacing), the exact Room schema and migrations, how the range
control looks, the empty and "collecting data" states.

## Open items
1. ~~Task 0: verify "sleep now" via schedule rewrite~~ Done, works (see above).
2. Night-light color support on the EVO (write + restore test).
3. Meaning of `isSmartWeightEnabled` (weights are recorded while it's false). Leave it alone.
4. Visual style pass.
5. ~~Which day a native entry that crosses midnight belongs to~~ Done: it covers its own day's early
   morning and evening (see above).
7. ~~Whole-day rest in one native entry~~ Done (equal times).
8. Power-off as the rest mode: not built yet. Needs a supervised test that `POWER_ON` works remotely
   after `POWER_OFF`. Until then rest always means sleep.
6. Why some visits are not recorded; try turning smart weight on and compare.
9. Insights: drawer-full forecast and litter trend need several days of Nessa's own readings.

## Behavior details (designer's calls unless marked)
- **Which window lives on the robot:** for each weekday, the window that starts latest in the day (the
  night window) is written to the robot as that day's native entry. That set of 7 is the **baseline**
  that "wake" restores. All other windows and all date overrides are enforced by the phone only.
  "Settings → Schedule baseline" just shows this; it's not a separate thing to edit.
- **Wake now** (during a window or while away): keeps the robot awake until the next window edge or
  presence change, then the rule takes over again. **Clean now** sends one clean cycle and does not
  change sleep state.
- **Changes made outside Nessa** (Whisker app, robot buttons): Nessa owns the schedule. On the next
  check it writes the expected schedule back and notes it in the log ("schedule was changed elsewhere,
  restored").
- **One cat:** the Whisker account has one pet (Nessa), so all visits count as hers. No per-pet filter.
- **Schedule lives on the phone, with checkpoints:** Nessa's schedule (all windows, overrides) is stored
  in the app's local database and is the source of truth. Before *every* write to the robot, Nessa saves
  a snapshot of the schedule the robot had. The first snapshot (the schedule found at first connect) is
  kept forever as "original". Settings → Schedule → History lists snapshots with a "Restore" button.
- **"When I'm away" setting (Settings → Presence):** choose **Sleep** (default: no cleaning, visits and
  weights still recorded) or **Power off** (nothing runs, nothing is recorded). Sleep windows at home
  always use sleep. The long-absence power-off below works on top of either choice.
- **Long-absence power-off (optional, off by default):** "If I'm away longer than X hours, power the
  robot off" (X snaps to 6 / 12 / 24 / 48). Sent by the phone, so it needs the phone alive and online.
  Powered back on when the owner is home. Trade-off shown in the UI: no visits or weights are recorded
  while it's off. Unverified: that `POWER_ON` works remotely after `POWER_OFF` (test with owner present).
- **Phone dead while away** (OPEN, owner to confirm): proposal is that "away" sleep is written to
  cover every day, so the robot stays asleep until the phone comes back and says "home". It never
  resumes cycling on its own while the owner is away.

## Calls made by the designer (check these)
- Away wait default 5 min. Area radius default 200 m.
- minSdk 31 (Android 12). Only a Pixel 9 needs supporting.
- Log keeps 90 days.
- Single robot only in v1.
