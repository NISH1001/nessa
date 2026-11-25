# Insights Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Interactive insight cards on the Home tab (Health watch, Weight, Visits per day, Time in the box, Usual times, Robot) built from a history kept on the phone.

**Architecture:** A Room database holds every Whisker activity and a robot reading per check. A `HistorySync` fills it incrementally from Whisker. Pure-Kotlin functions in `insights/` turn stored rows into card models (medians, baselines, flags) and are unit-tested on the JVM. Compose `Canvas` charts draw those models with tap-to-inspect.

**Tech Stack:** Kotlin 2.0.21, Room 2.6.1 with KSP 2.0.21-1.0.28, Jetpack Compose, WorkManager, JUnit4, MockWebServer.

**Spec:** `docs/DESIGN.md`, section "Insights (designed 2026-11-24, approved card set)".

## Global Constraints

- Medians, never means. All day boundaries and hours in the robot's own timezone.
- "Usual" = median over the 30 days before today, days with data only. It never depends on the selected range.
- Outlier weight: more than 15% from usual weight. Usual weight anchors on readings from visits longer than 60 s.
- Busy day: at least 2× usual visits. Return trip: a visit within 15 min of the previous one.
- Health watch stays silent until 14 days with data.
- Days without data are left out of charts.
- Short labels; status colors from the app (`Tone.color()`); amber = look at this. Tap targets ≥ 24 dp.
- No personal data in the repo; test data is made up (see `AGENTS.md` scan).

## Review Focus

1. **Empty history** (first run, or a robot with no visits): every card shows an empty or "Collecting data" state, no crash, no division by zero. → Task 3 test `all cards survive empty history`.
2. **Activities arriving out of order or twice across syncs**: stored once, charts sorted by time. → Task 2 test `resync stores nothing twice`.
3. **A day crossing midnight in the robot's timezone vs the phone's**: visits counted on the robot's local day. → Task 3 test `days follow the robot's timezone`.
4. **Only outliers in range** (all weights way off): weight card shows readings, no median line, no weight flag. → Task 3 test `no usual weight when every reading is an outlier`.
5. **Whisker unreachable during sync**: stored history still renders; sync retries later. → Task 2 test `failed sync keeps stored rows`.

---

### Task 1: History database

**Files:**
- Modify: `build.gradle.kts`, `app/build.gradle.kts` (KSP plugin; `androidx.room:room-runtime`, `room-ktx`, `ksp(room-compiler)` 2.6.1)
- Modify: `app/src/main/java/dev/nish/nessa/whisker/Models.kt` (`Activity` gains `eventId: String? = null`, `messageId: String? = null`; `Robot` gains `nextFilterReplacementDate: String? = null`)
- Create: `app/src/main/java/dev/nish/nessa/history/HistoryStore.kt`, `history/RoomHistoryStore.kt`
- Test: `app/src/test/java/dev/nish/nessa/history/FakeHistoryStore.kt`, `app/src/androidTest/java/dev/nish/nessa/history/RoomHistoryStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class StoredActivity(val id: String, val type: String, val at: Instant, val durationSec: Int?, val weightLb: Double?)`
  - `data class RobotReading(val at: Instant, val drawerPercent: Int?, val litterPercent: Double?, val litterLabel: String?, val sleeping: Boolean?, val powerOn: Boolean?, val cycles: Int?)`
  - `fun Activity.toStored(): StoredActivity?` — id = `eventId ?: messageId ?: "$type@$timestamp"`; null when timestamp unparseable; weight = `petWeight / 100`.
  - `interface HistoryStore { suspend fun addActivities(rows: List<StoredActivity>): Int /* newly added */; suspend fun hasActivity(id: String): Boolean; suspend fun activitiesSince(from: Instant): List<StoredActivity> /* sorted by at */; fun activitiesFlow(): Flow<List<StoredActivity>>; suspend fun addReading(r: RobotReading); suspend fun readingsSince(from: Instant): List<RobotReading>; fun readingsFlow(): Flow<List<RobotReading>> }`
  - `class RoomHistoryStore(context: Context) : HistoryStore` — database file `nessa_history.db`, insert with `OnConflictStrategy.IGNORE`.

- [ ] Step 1: Failing JVM test `toStored uses eventId then messageId then type and time` and `toStored converts weight to pounds` in `app/src/test/java/dev/nish/nessa/history/StoredActivityTest.kt`.
- [ ] Step 2: Run `./gradlew testDebugUnitTest --tests '*StoredActivityTest'` → FAIL.
- [ ] Step 3: Implement models, `toStored`, the interface, Room entities/DAO/database, and `FakeHistoryStore` (in-memory, same dedupe).
- [ ] Step 4: Run → PASS. Instrumented `RoomHistoryStoreTest`: `inserting the same id twice keeps one row`, `activitiesSince is sorted and filtered`; run `./gradlew connectedDebugAndroidTest` → PASS.
- [ ] Step 5: Commit.

### Task 2: History sync

**Files:**
- Create: `app/src/main/java/dev/nish/nessa/history/HistorySync.kt`
- Test: `app/src/test/java/dev/nish/nessa/history/HistorySyncTest.kt`

**Interfaces:**
- Consumes: `WhiskerApi.robots()`, `WhiskerApi.activities(serial, limit, offset)`, `pickRobot`, `RobotChoiceStore`, `HistoryStore`.
- Produces: `class HistorySync(api: WhiskerApi, choice: RobotChoiceStore, store: HistoryStore, now: () -> Instant)` with `suspend fun sync(): String?` (null = ok, else plain problem text). Pages of 100 until a page is short, a page contains only already-stored ids, or 2,000 rows; stores a `RobotReading` from the robot fetched in the same call. Also exposes `fun Robot.toReading(at: Instant): RobotReading`.

- [ ] Step 1: Failing tests: `first sync stores every page`, `later sync stops at the first page it already has`, `resync stores nothing twice`, `stores a robot reading each sync`, `failed sync keeps stored rows and returns the problem`, `stops at 2000 rows`.
- [ ] Step 2: Run → FAIL. Step 3: Implement. Step 4: Run → PASS. Step 5: Commit.

### Task 3: Insight calculations

**Files:**
- Create: `app/src/main/java/dev/nish/nessa/insights/Insights.kt` (pure Kotlin)
- Test: `app/src/test/java/dev/nish/nessa/insights/InsightsTest.kt`

**Interfaces:**
- Consumes: `StoredActivity`, `RobotReading`.
- Produces (all in `Insights.kt`):
  - `enum class Range(val days: Int?) { Week(7), Month(30), Quarter(90), All(null) }`
  - `data class WeightPoint(val at: Instant, val lb: Double, val outlier: Boolean)`; `data class WeightCard(val points: List<WeightPoint>, val usualLb: Double?)`
  - `data class DayCount(val day: LocalDate, val count: Int, val busy: Boolean)`; `data class VisitsCard(val days: List<DayCount>, val usual: Double?, val today: Int, val returnTrips: Int, val returnAfterCleaning: Int)`
  - `data class VisitDot(val at: Instant, val seconds: Int)`; `data class TimeCard(val dots: List<VisitDot>, val typicalSec: Int?, val longestTodaySec: Int?)`
  - `data class HoursCard(val counts: IntArray /*24*/, val busiest: String?)` — `busiest` like "7–9 AM and 4–7 PM": up to two runs of consecutive hours whose count ≥ half the max.
  - `data class RobotCard(val cleaningsToday: Int, val usualCleanings: Double?, val drawerPercent: Int?, val drawerFullInDays: Int?, val litterLabel: String?, val interruptedWeek: Int, val safetyStopsWeek: Int, val filterDue: LocalDate?)` — drawer forecast from the fill rate of readings since the last `WASTE_DRAWER_REMOVED`, needs ≥ 2 days of readings, else null.
  - `enum class Check { Weight, Visits, NoVisits, Robot }`; `data class HealthItem(val check: Check, val ok: Boolean, val text: String)`; `data class HealthWatch(val learning: Boolean, val items: List<HealthItem>)`
  - `data class InsightsModel(val health: HealthWatch, val weight: WeightCard, val visits: VisitsCard, val time: TimeCard, val hours: HoursCard, val robot: RobotCard)`
  - `fun buildInsights(activities: List<StoredActivity>, readings: List<RobotReading>, range: Range, zone: ZoneId, now: Instant, filterDue: LocalDate?): InsightsModel`
- Health watch texts (exact): ok → "Weight steady · {x.x} lb", "Visits normal", "Visited in the last 24 h", "Robot running smoothly"; flags → "Weight down {n}% in 2 weeks", "Visits above usual, box was clean" / "Visits above usual, box hadn't cleaned yet", "No visits for {h} h", "{n} safety stops this week" / "{n} cleanings interrupted this week". Learning → `learning = true`, items empty.

- [ ] Step 1: Failing tests (made-up data): `weight outliers are more than 15 percent from usual`, `usual weight anchors on visits longer than 60 seconds`, `no usual weight when every reading is an outlier`, `usual visits is the median of the 30 days before today`, `range changes the bars but not usual or busy`, `busy day is twice usual`, `days without visits are left out`, `return trips split by a cleaning in between`, `typical time is the median`, `hours strip and busiest text`, `drawer forecast from readings since the last emptying`, `drawer forecast null with under two days of readings`, `weekly interruptions and safety stops`, `health watch learns for 14 days`, `weight flag at 5 percent drop`, `visits flag says whether the box was clean`, `no visits flag ignores time the robot was asleep or off`, `days follow the robot's timezone`, `all cards survive empty history`.
- [ ] Step 2: Run → FAIL. Step 3: Implement. Step 4: Run → PASS. Step 5: Commit.

### Task 4: Wiring and background sync

**Files:**
- Modify: `AppContainer.kt` (`history: HistoryStore`, `historySync: HistorySync`), `NessaApp.kt` (enqueue insights work), `schedule/Scheduling.kt` (add `InsightsSyncWorker`, unique periodic work `nessa-history` every 15 min, network-constrained, independent of sleep management), `data/LitterRepository.kt` (`refresh()` also stores a robot reading), `ui/NessaRoot.kt` refresh button also runs `historySync.sync()`.
- Modify: `data/ActionLog.kt` unchanged (spec's "move to Room" deferred; see Ruling note in ledger).

- [ ] Step 1: Build, install on the phone, confirm in `adb logcat` that the worker runs and the database fills (row count via `run-as … sqlite3` or a debug log line with counts only, no values).
- [ ] Step 2: Commit.

### Task 5: Charts

**Files:**
- Create: `app/src/main/java/dev/nish/nessa/ui/insights/Charts.kt`

**Interfaces:**
- Consumes: Task 3 models.
- Produces Composables: `DotChart(points: List<ChartDot>, guide: Float?, guideLabel: String?, yMin: Float, yMax: Float, yLabel: (Float) -> String, xStart: Instant, xEnd: Instant, zone: ZoneId)`, `BarChart(bars: List<ChartBar>, guide: Float?, guideLabel: String?, ...)`, `HourStrip(counts: IntArray)`. `data class ChartDot(val at: Instant, val y: Float, val hollow: Boolean, val label: String)`, `data class ChartBar(val day: LocalDate, val value: Float, val highlight: Boolean, val label: String)`. Tap or drag shows the nearest mark's `label` in a bubble; dashed guide line; points outside y range pinned to the edge.

- [ ] Step 1: Implement; build; screenshot on device via Task 6.
- [ ] Step 2: Commit with Task 6.

### Task 6: Home tab insights

**Files:**
- Create: `ui/insights/InsightsSection.kt`, `ui/insights/InsightsViewModel.kt`
- Modify: `ui/home/HomeScreen.kt` (append the section after the existing cards)

**Interfaces:**
- `class InsightsViewModel(store: HistoryStore, litter: LitterRepository)`: `val range: StateFlow<Range>`, `fun setRange(r: Range)`, `val model: StateFlow<InsightsModel?>` (combines `activitiesFlow`, `readingsFlow`, range, robot zone and filter date; recomputes on `Dispatchers.Default`).
- Cards in order: range chips (7 days · 30 days · 90 days · All, default 30 days), Health watch (tapping a flag scrolls to its card), Weight, Visits per day, Time in the box, Usual times, Robot. Copy is short labels as in the mockup.

- [ ] Step 1: Implement; install release build on the phone; screenshot each card; check light and dark.
- [ ] Step 2: Run full suite `./gradlew testDebugUnitTest assembleDebug` → PASS. Commit.
