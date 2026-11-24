# Nessa Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable Nessa app on the Pixel 9 that connects to Whisker (under Settings → Connections) and shows the Litter-Robot EVO's live status on the Litter and Home tabs, with a working "Clean now".

**Architecture:** Single-module Android app. All Whisker traffic lives in `whisker/` (pure Kotlin, no Android imports, mirrors `pylitterbot`) so it is unit-testable on the JVM with a fake server. A `WhiskerSession` owns tokens and silent re-login; a `LitterRepository` exposes robot state as a `StateFlow` to Compose screens. Manual dependency wiring in an `AppContainer` (no DI framework).

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.1, Gradle 8.11.1, Jetpack Compose (BOM 2024.12.01) + Material 3 with dynamic color, Navigation Compose, OkHttp 4.12, kotlinx.serialization 1.7.3, kotlinx.coroutines 1.9, DataStore Preferences 1.1.1, Android Keystore. Tests: JUnit4, OkHttp MockWebServer, kotlinx-coroutines-test.

**Spec:** `docs/DESIGN.md` (mockups: `docs/mockups/nessa-layout.html`)

**This is plan 1 of 4.** Later plans: `01-02` schedule + rule engine + sleep control with checkpoints, `01-03` presence service (Wi-Fi, home area, away mode, long-absence power-off), `01-04` log, robot settings, first-run setup, visual polish.

## Global Constraints

- Package and applicationId `dev.nish.nessa`. `minSdk = 31`, `targetSdk = 36`, `compileSdk = 36`. Java/Kotlin target 17.
- Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@21` (the only JDK on this Mac). Android SDK at `~/Library/Android/sdk` (write `local.properties`, which is git-ignored).
- No Google Play Services, no analytics, no ads. MIT license.
- Never commit, log, or print the owner's personal information: Whisker email, password, tokens, robot serial, account or pet ids, home Wi-Fi name, IP addresses, location or timezone, file paths with the username. Before every commit run the leak scan in `AGENTS.md`.
- Never commit, log, or print the Whisker email, password, or any token. Test fixtures use serial `LRE-TEST-0001`, userId `0`, pet id `PET-TEST`.
- UI copy is plain language for a cat owner, not a developer ("Nessa's box is ready", not "state=StRobotIdle"). Technical values only behind a "Details" expander.
- Tabs, in order: **Home · Litter · Log**. Settings opens from a gear icon in the top bar (own screen with back arrow, bottom bar hidden). Whisker sign-in is under Settings → Connections, never on the first screen.
- Whisker constants (public, from `pylitterbot/session.py` and `robot/litterrobot5.py`): Cognito endpoint `https://cognito-idp.us-east-1.amazonaws.com/`, client id `4552ujeu3aic90nf8qn53levmn`, API base `https://ub.prod.iothings.site/`, header `authorization: Bearer <idToken>`, header `User-Agent: nessa/<versionName>`.
- Device for manual checks: `adb -s <phone-ip:port>` (Pixel 9, Android 17). The port changes when wireless debugging restarts; re-read it with `adb mdns services`.
- Commit after each task, on `main`, message ending with the session's Co-Authored-By line.

## Review Focus

1. **No network / Whisker unreachable** while opening the Litter tab: show the last known status with "Couldn't reach Whisker · Retry", never a crash or blank screen. (Test in Task 6.)
2. **Wrong password at sign-in:** a clear message under the password field, nothing stored. (Tests in Tasks 2 and 4.)
3. **Token expires while the app is open** (tokens last 3600 s): the next request refreshes silently; if the refresh token is rejected, sign in again with the stored password; only if that fails show "Reconnect Whisker". (Tests in Task 4.)
4. **Robot offline or missing fields:** `state` fields absent or `null`, `isOnline=false`, or unknown new JSON keys must parse and show "Offline · last seen …". (Tests in Task 3 and 6.)
5. **Account with no robots, or more than one:** empty list shows "No Litter-Robot found on this account"; with several, use the first whose `type` is `LRE`, else the first. (Test in Task 6.)

---

### Task 1: Project skeleton that builds, installs, and shows four tabs

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (copy the wrapper from `the aa-rescue project`, Gradle 8.11.1), `local.properties` (ignored)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/dev/nish/nessa/NessaApp.kt`, `MainActivity.kt`, `AppContainer.kt`
- Create: `app/src/main/java/dev/nish/nessa/ui/NessaRoot.kt`, `ui/theme/Theme.kt`
- Create: `app/src/main/java/dev/nish/nessa/ui/home/HomeScreen.kt`, `ui/litter/LitterScreen.kt`, `ui/log/LogScreen.kt`, `ui/settings/SettingsScreen.kt` (placeholders)
- Create: `README.md` (what Nessa is, build + install commands)

**Interfaces:**
- Produces: `class AppContainer(context: Context)` (empty for now; later tasks add properties), `NessaApp.container: AppContainer`, `@Composable fun NessaRoot()`, `@Composable fun NessaTheme(content: @Composable () -> Unit)`, routes `"home"`, `"litter"`, `"log"`, `"settings"`, `"settings/connections"`.

- [ ] **Step 1:** Write the Gradle files. Plugins: `com.android.application` 8.9.1, `org.jetbrains.kotlin.android` 2.0.21, `org.jetbrains.kotlin.plugin.compose` 2.0.21, `org.jetbrains.kotlin.plugin.serialization` 2.0.21. `versionCode = 1`, `versionName = "0.1.0"`. Release build type signs with the debug key and minifies (same as aa-rescue). Manifest: `INTERNET` permission, `android:name=".NessaApp"`, single `MainActivity`, label "Nessa".
- [ ] **Step 2:** `NessaTheme`: Material 3, `dynamicLightColorScheme`/`dynamicDarkColorScheme` following the system theme. `NessaRoot`: `Scaffold` with a `NavigationBar` of the four tabs (icons: Home, Pets, History/list, Settings) and a `NavHost`. Each placeholder screen shows its title and one friendly sentence.
- [ ] **Step 3:** Build. Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` · Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 4:** Install and launch. Run: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk && adb -s <serial> shell am start -n dev.nish.nessa/.MainActivity`, then `adb -s <serial> exec-out screencap -p > /tmp-scratch/home.png` and look at it · Expected: Nessa opens on Home with four tabs; tapping (`adb shell input tap`) each tab switches screen.
- [ ] **Step 5:** Commit (`LICENSE`, `.gitignore`, `docs/`, and the skeleton).

### Task 2: Whisker sign-in client (`CognitoAuth`)

**Files:**
- Create: `app/src/main/java/dev/nish/nessa/whisker/CognitoAuth.kt`, `whisker/WhiskerErrors.kt`
- Test: `app/src/test/java/dev/nish/nessa/whisker/CognitoAuthTest.kt`

**Interfaces:**
- Produces:
  - `data class Tokens(val idToken: String, val refreshToken: String?, val expiresAtEpochSec: Long)`
  - `class CognitoAuth(http: OkHttpClient, baseUrl: String = COGNITO_URL, clientId: String = CLIENT_ID, nowEpochSec: () -> Long)`
  - `suspend fun signIn(email: String, password: String): Tokens`
  - `suspend fun refresh(refreshToken: String): Tokens` (returned `refreshToken` is `null`; caller keeps the old one)
  - `sealed class WhiskerException : Exception` with `BadCredentials`, `Network(cause)`, `Server(code: Int, type: String?)`, `NeedsReconnect`

Request shape (both calls): `POST {baseUrl}` with headers `X-Amz-Target: AWSCognitoIdentityProviderService.InitiateAuth` and `Content-Type: application/x-amz-json-1.1`; body `{"AuthFlow":"USER_PASSWORD_AUTH","ClientId":…,"AuthParameters":{"USERNAME":…,"PASSWORD":…}}` or `{"AuthFlow":"REFRESH_TOKEN_AUTH","ClientId":…,"AuthParameters":{"REFRESH_TOKEN":…}}`. Success: `{"AuthenticationResult":{"IdToken","AccessToken","RefreshToken"?,"ExpiresIn":3600}}`. Failure: HTTP 400 with `{"__type":"NotAuthorizedException"|"UserNotFoundException"|…,"message":…}`.

- [ ] **Step 1: Write failing tests** (MockWebServer):
  - `signIn sends USER_PASSWORD_AUTH with target header and returns tokens`: asserts the recorded request's `X-Amz-Target`, `Content-Type`, body fields, and `Tokens("id1","r1", now+3600)`.
  - `signIn with NotAuthorizedException throws BadCredentials`; same for `UserNotFoundException`.
  - `refresh sends REFRESH_TOKEN_AUTH and returns tokens with null refreshToken`.
  - `refresh with NotAuthorizedException throws BadCredentials`.
  - `connection failure throws Network` (server shut down).
  - `HTTP 500 throws Server(500, …)`.
  - `exception messages and toString never contain the password or tokens`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*CognitoAuthTest'` · Expected: FAIL (class missing).
- [ ] **Step 3:** Implement `CognitoAuth` with OkHttp + kotlinx.serialization, calls on `Dispatchers.IO`.
- [ ] **Step 4:** Run the tests · Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 3: Robot models and `WhiskerApi`

**Files:**
- Create: `whisker/Models.kt`, `whisker/WhiskerApi.kt`
- Create fixtures: `app/src/test/resources/robot.json`, `robots.json` (array of one), `robot_offline_minimal.json`, `activities.json`
- Test: `app/src/test/java/dev/nish/nessa/whisker/WhiskerApiTest.kt`, `ModelsTest.kt`

**Interfaces:**
- Consumes: `WhiskerException` (Task 2).
- Produces (all `@Serializable`, every field nullable or defaulted, `Json { ignoreUnknownKeys = true; explicitNulls = false }`):
  - `data class Robot(serial: String, type: String?, name: String?, timezone: String?, state: RobotState?, sleepSchedules: List<SleepEntry> = emptyList(), nightLightSettings: NightLightSettings?, panelSettings: PanelSettings?, litterRobotSettings: LitterRobotSettings?)`
  - `data class RobotState(powerStatus: String?, isOnline: Boolean?, isSleeping: Boolean?, state: String?, statusIndicator: StatusIndicator?, dfiLevelPercent: Int?, litterLevelPercent: Double?, globeLitterLevelIndicator: String?, wasteLabel: String?, weightSensor: Double?, isDrawerFull: Boolean?, isDrawerRemoved: Boolean?, isBonnetRemoved: Boolean?, lastSeen: String?, wifiRssi: Int?, odometerCleanCycles: Int?)`
  - `data class StatusIndicator(title: String?, type: String?)`
  - `data class SleepEntry(dayOfWeek: Int, isEnabled: Boolean, sleepTime: Int, wakeTime: Int)` (0 = Sunday; minutes from midnight, robot's timezone)
  - `data class NightLightSettings(mode: String?, brightness: Int?, color: String?)`, `data class PanelSettings(displayIntensity: String?, isKeypadLocked: Boolean?)`, `data class LitterRobotSettings(cycleDelay: Int?, isSmartWeightEnabled: Boolean?)`
  - `data class Activity(type: String, timestamp: String, petWeight: Double?, duration: Int?, petIds: List<String> = emptyList(), subtype: String?)` (`petWeight` is pounds × 100)
  - `class WhiskerApi(http: OkHttpClient, baseUrl: String = API_URL, userAgent: String, idToken: suspend () -> String)`
  - `suspend fun robots(): List<Robot>` → `GET robots`
  - `suspend fun robot(serial: String): Robot` → `GET robots/{serial}`
  - `suspend fun patchRobot(serial: String, body: JsonObject)` → `PATCH robots/{serial}`
  - `suspend fun command(serial: String, type: RobotCommand)` → `POST robots/{serial}/commands` body `{"type":"CLEAN_CYCLE"}`; `enum class RobotCommand { CLEAN_CYCLE, POWER_ON, POWER_OFF }`
  - `suspend fun activities(serial: String, limit: Int = 50, offset: Int = 0): List<Activity>` → `GET robots/{serial}/activities?limit=&offset=`
  - HTTP 401/403 → `WhiskerException.NeedsReconnect`; other non-2xx → `Server(code, null)`; IO failure → `Network`.

Fixtures: take the shapes recorded in `docs/DESIGN.md` and the session's read-only sample (state with `statusIndicator {title:"Ready", type:"READY"}`, `dfiLevelPercent: 30`, `litterLevelPercent: 100.0`, `weightSensor: 930.0`, `isSleeping: false`, 7 `sleepSchedules` entries, and deliberately several extra unknown keys). `robot_offline_minimal.json` is `{"serial":"LRE-TEST-0001","state":{"isOnline":false}}`. `activities.json` has one `CYCLE_COMPLETED` and one `PET_VISIT` with `petWeight: 930.0`, `duration: 431`.

- [ ] **Step 1: Write failing tests:**
  - `robot fixture parses`: `state.statusIndicator.type == "READY"`, `dfiLevelPercent == 30`, `sleepSchedules.size == 7`, `sleepSchedules[0] == SleepEntry(0, true, 1260, 450)`.
  - `minimal offline robot parses with nulls`.
  - `activities fixture parses`: second item `type == "PET_VISIT"`, `petWeight == 930.0`.
  - `robots() sends GET /robots with Bearer token and User-Agent`.
  - `patchRobot sends PATCH with exact JSON body`; `command sends {"type":"CLEAN_CYCLE"}`; `activities sends limit and offset`.
  - `401 throws NeedsReconnect`, `500 throws Server`, `dropped connection throws Network`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*WhiskerApiTest' --tests '*ModelsTest'` · Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run · Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 4: Encrypted login storage and `WhiskerSession`

**Files:**
- Create: `app/src/main/java/dev/nish/nessa/data/SecretStore.kt` (interface + `KeystoreSecretStore`), `whisker/WhiskerSession.kt`
- Test: `app/src/test/java/dev/nish/nessa/whisker/WhiskerSessionTest.kt` (with `FakeSecretStore`), `app/src/androidTest/java/dev/nish/nessa/data/KeystoreSecretStoreTest.kt`
- Modify: `AppContainer.kt` (add `secretStore`, `auth`, `session`, `api`, one shared `OkHttpClient`)

**Interfaces:**
- Consumes: `CognitoAuth`, `Tokens`, `WhiskerException` (Task 2); `WhiskerApi` (Task 3).
- Produces:
  - `data class Secrets(val email: String, val password: String, val refreshToken: String)`
  - `interface SecretStore { suspend fun load(): Secrets?; suspend fun save(s: Secrets); suspend fun clear() }`
  - `class KeystoreSecretStore(context: Context) : SecretStore`: AES-256-GCM key in Android Keystore (alias `nessa.whisker`), ciphertext + IV in DataStore Preferences file `whisker_secrets`.
  - `enum class ConnectionStatus { Disconnected, Connected, NeedsReconnect }`
  - `class WhiskerSession(auth: CognitoAuth, store: SecretStore, nowEpochSec: () -> Long)`
    - `val status: StateFlow<ConnectionStatus>`
    - `suspend fun restore()` (call at app start: `Connected` if secrets exist)
    - `suspend fun signIn(email: String, password: String)` (saves secrets only on success)
    - `suspend fun signOut()` (clears secrets and tokens)
    - `suspend fun idToken(): String`: returns the cached token if it has more than 60 s left; else `refresh`; if refresh throws `BadCredentials`, `signIn` with the stored password; if that throws `BadCredentials`, set `NeedsReconnect` and throw `WhiskerException.NeedsReconnect`. `Network` errors pass through and do not change status. Calls are serialized with a `Mutex`.

- [ ] **Step 1: Write failing tests** (`WhiskerSessionTest`, MockWebServer behind a real `CognitoAuth`, fake clock):
  - `signIn stores email, password and refresh token and sets Connected`.
  - `signIn with bad password stores nothing and stays Disconnected`.
  - `idToken reuses cached token within lifetime` (one request total).
  - `idToken refreshes when under 60 seconds remain` (asserts a `REFRESH_TOKEN_AUTH` request).
  - `rejected refresh falls back to password sign-in and updates stored refresh token`.
  - `rejected refresh and rejected password sets NeedsReconnect and throws`.
  - `network error during refresh keeps status Connected and rethrows Network`.
  - `two concurrent idToken calls make one refresh`.
  - `restore with stored secrets sets Connected without any network call`.
  - `signOut clears the store and sets Disconnected`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*WhiskerSessionTest'` · Expected: FAIL.
- [ ] **Step 3:** Implement `WhiskerSession`, then `KeystoreSecretStore`.
- [ ] **Step 4:** Run unit tests · Expected: PASS.
- [ ] **Step 5:** Instrumented test `save then load returns equal Secrets; clear then load returns null; stored bytes do not contain the password in plain text`. Run: `./gradlew connectedDebugAndroidTest` (Pixel connected) · Expected: PASS.
- [ ] **Step 6:** Wire into `AppContainer`; `WhiskerApi`'s `idToken` lambda is `session::idToken`. Build · Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 7:** Commit.

### Task 5: Settings → Connections (sign in to Whisker)

**Files:**
- Create: `ui/settings/ConnectionsScreen.kt`, `ui/settings/ConnectionsViewModel.kt`
- Modify: `ui/settings/SettingsScreen.kt` (list with a "Connections" row showing status), `ui/NessaRoot.kt` (route `settings/connections`)
- Test: `app/src/test/java/dev/nish/nessa/ui/settings/ConnectionsViewModelTest.kt`

**Interfaces:**
- Consumes: `WhiskerSession`, `WhiskerApi`, `ConnectionStatus`, `WhiskerException`.
- Produces: `class ConnectionsViewModel(session: WhiskerSession, api: WhiskerApi)` with `val ui: StateFlow<ConnectionsUi>`; `data class ConnectionsUi(status: ConnectionStatus, robotName: String?, busy: Boolean, error: String?)`; `fun signIn(email: String, password: String)`, `fun test()`, `fun signOut()`.

Copy (exact): card title "Whisker (Litter-Robot)"; statuses "Connected", "Not connected", "Needs you to sign in again"; sign-in sheet title "Connect your Whisker account", fields "Email" and "Password", helper "Stored encrypted on this phone only.", button "Connect"; errors "That email or password didn't work.", "Couldn't reach Whisker. Check your internet and try again.", "Whisker had a problem (code {n}). Try again in a bit."; after success "Found {robot name}"; buttons "Test connection", "Sign out" (confirm dialog "Sign out of Whisker? Nessa will stop managing the litter box.").

- [ ] **Step 1: Write failing ViewModel tests:** `signIn success shows Connected and robot name`; `BadCredentials shows the wrong-password message and stays Disconnected`; `Network shows the couldn't-reach message`; `Server(503) shows the code message`; `busy is true while signing in`; `signOut returns to Not connected`; `error text never contains the password`.
- [ ] **Step 2:** Run · Expected: FAIL.
- [ ] **Step 3:** Implement ViewModel, then the screen (Material 3 cards, modal bottom sheet for sign-in, password field with show/hide).
- [ ] **Step 4:** Run tests · Expected: PASS. Build, install.
- [ ] **Step 5: Manual check on the Pixel** (the owner types their own Whisker login on the phone; do not enter it via adb): Settings → Connections → Connect → shows "Connected" and "Found Litter-Robot EVO". Force-stop and reopen the app: still "Connected". Screenshot both.
- [ ] **Step 6:** Commit.

### Task 6: Live status on Litter and Home, with "Clean now"

**Files:**
- Create: `data/LitterRepository.kt`, `ui/litter/LitterViewModel.kt`, `ui/litter/LitterStatus.kt` (pure mapping)
- Modify: `ui/litter/LitterScreen.kt`, `ui/home/HomeScreen.kt`, `AppContainer.kt`
- Test: `app/src/test/java/dev/nish/nessa/ui/litter/LitterStatusTest.kt`, `data/LitterRepositoryTest.kt`

**Interfaces:**
- Consumes: `WhiskerApi`, `Robot`, `RobotState`, `Activity`, `WhiskerSession.status`.
- Produces:
  - `class LitterRepository(api: WhiskerApi, session: WhiskerSession)`: `val state: StateFlow<LitterState>`; `suspend fun refresh()`; `suspend fun cleanNow()`
  - `sealed interface LitterState { NotConnected; Loading; NoRobot; data class Unavailable(problem: String); data class Ready(robot: Robot, todayVisits: Int, lastVisit: Activity?, fetchedAtEpochSec: Long, stale: Boolean, problem: String?) }`
  - `data class LitterStatusUi(headline: String, detail: String?, tone: Tone, drawerPercent: Int?, litterLabel: String?, weightLb: Double?)`, `enum class Tone { Good, Resting, Attention, Offline }`
  - `fun litterStatusUi(robot: Robot): LitterStatusUi` (pure)

Mapping (exact headlines): `isOnline == false` → "Offline", detail "Last seen {relative time}", `Offline`. `powerStatus != "On"` → "Powered off", `Resting`. `isSleeping == true` → "Resting", detail "Not cleaning right now", `Resting`. `isDrawerFull == true` → "Drawer is full", `Attention`. `isDrawerRemoved`/`isBonnetRemoved` → "Drawer is out" / "Top cover is off", `Attention`. `statusIndicator.type == "READY"` → "Ready", `Good`. Anything else → `statusIndicator.title` or "Working", `Good`. `drawerPercent = dfiLevelPercent`; `litterLabel = globeLitterLevelIndicator`; `weightLb = weightSensor / 100`. Checked in that order.

Robot choice: first with `type == "LRE"`, else first; empty → `NoRobot`. `refresh()` failure with a previous `Ready` keeps it with `stale = true` and `problem = "Couldn't reach Whisker"`. `todayVisits` counts `PET_VISIT` activities whose timestamp falls on today's date in the robot's `timezone`.

Screens: **Litter** shows a status card (headline, detail, tone color), "Clean now" button (confirm dialog "Start a cleaning cycle now?"; disabled when Offline/Powered off), drawer and litter levels, "Details" expander (serial masked to last 4, firmware not needed, raw state name, last updated). Pull to refresh; auto-refresh every 30 s while the tab is visible. **Home** shows "Litter: {headline}" card, "Today · Nessa" card (visits, last visit time, weight). Not connected → a card "Connect your Whisker account" linking to Settings → Connections.

- [ ] **Step 1: Write failing tests:**
  - `LitterStatusTest`: one test per mapping row above, plus `sleeping and drawer full shows Resting` (order), `minimal offline fixture maps to Offline without crashing`, `weight 930 maps to 9.3 lb`.
  - `LitterRepositoryTest` (MockWebServer): `refresh publishes Ready with todayVisits from activities`; `empty robot list publishes NoRobot`; `two robots picks the LRE one`; `refresh failure after success keeps data and marks stale with problem text`; `refresh failure with no earlier data publishes Unavailable("Couldn't reach Whisker")`; `NeedsReconnect publishes NotConnected`; `cleanNow posts CLEAN_CYCLE then refreshes`.
- [ ] **Step 2:** Run · Expected: FAIL.
- [ ] **Step 3:** Implement mapping, repository, ViewModel, screens.
- [ ] **Step 4:** Run all unit tests: `./gradlew testDebugUnitTest` · Expected: PASS.
- [ ] **Step 5: Manual check on the Pixel:** Litter tab shows the same status, drawer level and litter level as the Whisker app. Turn on airplane mode, pull to refresh: old data stays with "Couldn't reach Whisker". Turn it off: recovers. With the owner's OK and watching the robot, tap "Clean now" once: a cycle starts. Screenshot Litter and Home.
- [ ] **Step 6:** Commit.

---

## After this plan
The owner can install Nessa, connect Whisker once, and see the robot's live state. Next plan (`01-02`) adds the per-day sleep windows, the rule engine, and schedule writes with checkpoints.
