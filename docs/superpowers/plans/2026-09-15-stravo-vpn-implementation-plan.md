# STRAVO VPN Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a clean-room universal Android application and APK named STRAVO VPN for phones and Android TV, with a professional pencil-on-paper interface, ordinary VPN on both form factors, and phone-only white-list mode.

**Architecture:** Use one native Kotlin Android project with Jetpack Compose, shared domain state, and separate phone/TV shells. Keep Android `VpnService` as the only system tunnel owner and hide the concrete VPN engine behind a `VpnEngine` interface. Enforce form-factor capabilities in the domain layer, navigation, deep-link handling, and connection command so Android TV cannot activate white lists through an alternate path.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose, Android `VpnService`, Android lifecycle/ViewModel APIs, DataStore for preferences, Android Keystore-backed secret storage, Kotlin serialization or an equivalent pinned parser, and an Xray-compatible engine adapter selected from the actual target profile and official engine documentation.

## Global Constraints

- The product is one universal Android project and one APK for Android phones and Android TV.
- Incy is a functional and visual reference; no closed Incy APK code or proprietary assets are copied.
- The visual system is a professional paper canvas with graphite engraving, topographic lines, mountains, forest, compass, and restrained colored-pencil accents.
- All screens use the same canvas/graphite visual system.
- Ordinary VPN is available on Android phone and Android TV.
- White-list mode is available only on Android phone.
- Android TV has no white-list button, menu item, deep-link activation path, or hidden fallback.
- TV interaction must work with a remote: predictable focus order, visible focus outline, Back navigation, and large readable targets.
- Profile import success, build success, and real tunnel connectivity are separate states and must never be reported as interchangeable.
- Do not change production servers, payments, OTA, customer data, or existing client data from this project without a separate explicit authorization.
- Do not add or run automated tests unless the owner explicitly authorizes them; use compilation, static checks, and narrowly scoped manual observation when authorized.
- Never log or commit subscription URLs, UUIDs, keys, tokens, credentials, customer identifiers, or complete VPN configurations.
- Use current compatible dependency and engine versions from primary official documentation at implementation time; do not invent a version from an unrelated client.

---

## File Map

The implementation uses focused files with one responsibility:

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`: pinned Android build configuration.
- `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`: application module, permissions, services, and release-safe packaging.
- `app/src/main/java/com/stravo/vpn/domain/model/*`: form factor, modes, profiles, subscriptions, and connection state.
- `app/src/main/java/com/stravo/vpn/domain/policy/CapabilityPolicy.kt`: single source of truth for phone/TV mode access.
- `app/src/main/java/com/stravo/vpn/domain/importing/*`: URL, clipboard, QR, normalization, and profile validation.
- `app/src/main/java/com/stravo/vpn/domain/engine/VpnEngine.kt`: engine boundary consumed by the rest of the app.
- `app/src/main/java/com/stravo/vpn/data/*`: local profile, subscription, settings, and connection repositories.
- `app/src/main/java/com/stravo/vpn/service/StravoVpnService.kt`: sole Android `VpnService` owner.
- `app/src/main/java/com/stravo/vpn/ui/theme/*`: canvas palette, typography, spacing, shapes, and state colors.
- `app/src/main/java/com/stravo/vpn/ui/components/*`: paper surface, pencil strokes, compass, cards, buttons, and TV focus treatment.
- `app/src/main/java/com/stravo/vpn/ui/navigation/*`: routes, form-factor shells, and capability-filtered navigation.
- `app/src/main/java/com/stravo/vpn/ui/screens/*`: onboarding, home, servers, import, white lists, settings, help, and information screens.
- `docs/engine-compatibility.md`: exact target profile fields and selected engine/version evidence.
- `CONTEXT_HANDOFF.md`: current branch, implementation status, validation evidence, and next stop gate.

## Task 1: Bootstrap the isolated Android project

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/stravo/vpn/StravoApplication.kt`
- Create: `app/src/main/java/com/stravo/vpn/MainActivity.kt`
- Create: `AGENTS.md`
- Create: `CONTEXT_HANDOFF.md`

**Interfaces:**

- Produces the `com.stravo.vpn` application module, a launchable `MainActivity`, and a manifest-declared `StravoApplication`.
- The module must expose debug and release variants and declare only the permissions required by the implemented features.

- [ ] **Step 1: Record the build baseline.** Inspect the installed Android SDK, Java runtime, and available Gradle wrapper/tooling. Resolve compatible stable Android Gradle Plugin, Kotlin, Compose, and compile SDK values from official Android/Kotlin documentation and record them in `gradle/libs.versions.toml`.

- [ ] **Step 2: Create the settings and root build files.** Configure the project name `STRAVO`, the `:app` module, plugin repositories, dependency repositories, and a version catalog. Keep the root build file limited to plugin management and shared configuration.

- [ ] **Step 3: Create the application module.** Configure a single Android application with namespace `com.stravo.vpn`, an explicit min/target SDK pair, Compose enabled, resource shrinking only for release, and no analytics or advertising SDK.

- [ ] **Step 4: Add manifest entries.** Declare the launcher activity, the future `StravoVpnService` service entry only when its class exists, network access, and TV-capable metadata. Do not declare camera permission until the QR scanner task requires it.

- [ ] **Step 5: Add the minimal launch surface.** `MainActivity` should call `setContent { StravoApp() }`; `StravoApp` should render a stable empty state with the STRAVO wordmark and form-factor label.

- [ ] **Step 6: Write local project operating rules.** `AGENTS.md` must state the universal APK rule, phone-only white lists, TV-only ordinary VPN, no secret logging, no production mutations, and the owner’s no-automated-tests-without-explicit-authorization rule. `CONTEXT_HANDOFF.md` must record the initial commit, current state, and the next implementation task.

- [ ] **Step 7: Validate the bootstrap without test runs.** Compile the application variant with the project wrapper if available and inspect the manifest/package name. If the local toolchain cannot build, record the exact missing tool and stop at this task rather than repairing an unrelated system toolchain.

- [ ] **Step 8: Commit the bootstrap.**

```bash
git add settings.gradle.kts build.gradle.kts gradle app AGENTS.md CONTEXT_HANDOFF.md
git commit -m "build: bootstrap STRAVO Android project"
```

## Task 2: Define domain state and platform capabilities

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/domain/model/FormFactor.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/model/VpnMode.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/model/VpnConnectionState.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/model/VpnProfile.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/model/Subscription.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/model/ServerEndpoint.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/policy/CapabilityPolicy.kt`

**Interfaces:**

```kotlin
enum class FormFactor { Phone, Tv }

enum class VpnMode { Ordinary, WhiteList }

data class PlatformCapabilities(
    val formFactor: FormFactor,
    val modes: Set<VpnMode>,
    val supportsRemoteFocus: Boolean,
)

fun capabilitiesFor(formFactor: FormFactor): PlatformCapabilities
fun VpnMode.isAllowedOn(formFactor: FormFactor): Boolean
```

- [ ] **Step 1: Define stable enums and value objects.** Use explicit serialized names for modes and connection states so persisted data is not coupled to display text.

- [ ] **Step 2: Define profile and endpoint data.** Keep secrets separated from display metadata; a `VpnProfile` must expose a safe summary without returning raw credentials or full configuration text.

- [ ] **Step 3: Implement the capability policy.** `capabilitiesFor(FormFactor.Tv)` returns `setOf(VpnMode.Ordinary)` and `supportsRemoteFocus = true`. `capabilitiesFor(FormFactor.Phone)` returns both modes.

- [ ] **Step 4: Make the policy fail closed.** Add a single `requireAllowedMode(formFactor, mode)` function that returns a typed domain error for TV white-list requests. Navigation, deep links, import, and connection commands will call this function.

- [ ] **Step 5: Validate by inspection.** Search for every direct reference to `VpnMode.WhiteList` and confirm it is guarded by `CapabilityPolicy`; do not add or run automated tests under the owner constraint.

- [ ] **Step 6: Commit the domain boundary.**

```bash
git add app/src/main/java/com/stravo/vpn/domain/model app/src/main/java/com/stravo/vpn/domain/policy
git commit -m "feat: define STRAVO platform capabilities"
```

## Task 3: Build the professional pencil-on-canvas design system

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/ui/theme/StravoColors.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/theme/StravoTypography.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/theme/StravoShapes.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/theme/StravoTheme.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/PaperCanvas.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/PencilStroke.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/CompassMark.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/TopographicLines.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/StravoCard.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/PencilPowerButton.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/components/StravoFocusOutline.kt`

**Interfaces:**

```kotlin
@Composable
fun StravoTheme(content: @Composable () -> Unit)

@Composable
fun PaperCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
)

@Composable
fun PencilPowerButton(
    state: VpnConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
)
```

- [ ] **Step 1: Define the palette.** Use paper, graphite, mint/green, coral, yellow, and purple tokens. State colors must be explicit and must not be picked ad hoc inside screens.

- [ ] **Step 2: Define typography and shapes.** Use a legible primary family for status and settings, a restrained handwritten accent for hero labels, and large TV-safe sizes. Rounded cards should look like map panels, not generic Material containers.

- [ ] **Step 3: Draw the canvas surface.** `PaperCanvas` renders a low-contrast paper texture and deterministic topographic lines using Compose drawing primitives. The texture must be subtle enough that body text remains readable.

- [ ] **Step 4: Draw navigation motifs.** `CompassMark` and `TopographicLines` should be reusable decorative elements with no business state or hidden click targets.

- [ ] **Step 5: Implement stateful power control.** `PencilPowerButton` renders disconnected, preparing, connected, stopping, and error states with one prominent green action and a safe disabled state while the service is transitioning.

- [ ] **Step 6: Implement TV focus treatment.** `StravoFocusOutline` adds a high-contrast pencil-dashed outline and never relies on color alone.

- [ ] **Step 7: Validate visually.** Render the theme in the launch surface at phone and TV dimensions, inspect text contrast and clipping, and keep the result limited to visual/manual observation.

- [ ] **Step 8: Commit the design system.**

```bash
git add app/src/main/java/com/stravo/vpn/ui/theme app/src/main/java/com/stravo/vpn/ui/components
git commit -m "feat: add STRAVO pencil canvas design system"
```

## Task 4: Add form-factor detection and navigation shells

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/platform/FormFactorDetector.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/navigation/StravoRoute.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/navigation/StravoNavGraph.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/navigation/PhoneShell.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/navigation/TvShell.kt`
- Modify: `app/src/main/java/com/stravo/vpn/MainActivity.kt`

**Interfaces:**

```kotlin
sealed interface StravoRoute {
    data object Home : StravoRoute
    data object Servers : StravoRoute
    data object Import : StravoRoute
    data object WhiteList : StravoRoute
    data object Settings : StravoRoute
    data object Help : StravoRoute
}

@Composable
fun StravoNavGraph(formFactor: FormFactor)
```

- [ ] **Step 1: Detect the form factor.** Use Android UI mode/features and window size to classify phone versus TV, with TV metadata/features taking precedence over a wide phone window.

- [ ] **Step 2: Define routes.** Keep white-list navigation as an explicit route whose creation is conditional on `FormFactor.Phone`.

- [ ] **Step 3: Implement `PhoneShell`.** Use compact bottom navigation or a similarly touch-friendly structure for Home, Servers, Import, White lists, and Settings.

- [ ] **Step 4: Implement `TvShell`.** Use a left navigation rail with Home, Servers, Import, Settings, and Help. Do not include a white-list label, icon, route, or accessibility node.

- [ ] **Step 5: Wire Back behavior.** Back first returns within the current navigation stack and then exits the shell; it must not cause a mode change or reconnect.

- [ ] **Step 6: Validate route inventory.** Build a route list for phone and TV and inspect that `WhiteList` is absent from the TV list and from the TV accessibility tree.

- [ ] **Step 7: Commit the navigation boundary.**

```bash
git add app/src/main/java/com/stravo/vpn/platform app/src/main/java/com/stravo/vpn/ui/navigation app/src/main/java/com/stravo/vpn/MainActivity.kt
git commit -m "feat: add universal phone and TV navigation shells"
```

## Task 5: Add local persistence for profiles, subscriptions, and settings

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/data/profile/ProfileRepository.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/profile/LocalProfileStore.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/subscription/SubscriptionRepository.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/security/SecretStore.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/AppContainer.kt`

**Interfaces:**

```kotlin
interface ProfileRepository {
    suspend fun listProfiles(): List<VpnProfileSummary>
    suspend fun save(profile: VpnProfile): ProfileId
    suspend fun delete(id: ProfileId)
    suspend fun get(id: ProfileId): VpnProfile?
}

interface SettingsRepository {
    val settings: Flow<StravoSettings>
    suspend fun update(transform: (StravoSettings) -> StravoSettings)
}
```

- [ ] **Step 1: Choose the smallest storage shape.** Store user preferences in DataStore and profile metadata plus protected payloads in a versioned local store. Do not introduce a server database or migration system.

- [ ] **Step 2: Separate metadata from secrets.** Store display name, server label, mode, and timestamps separately from raw credential/configuration material. `VpnProfileSummary` must never contain the secret payload.

- [ ] **Step 3: Implement Keystore-backed protection.** `SecretStore` encrypts protected profile payloads with an Android Keystore key and returns a typed error when the key cannot be used.

- [ ] **Step 4: Implement repositories.** All writes use atomic replacement of the local document; failed writes leave the previous valid profile intact.

- [ ] **Step 5: Define settings.** Include first-run completion, selected profile, selected mode, auto-connect preference, and network-loss behavior. Do not add experimental flags to the customer connection path.

- [ ] **Step 6: Validate persistence manually.** Save a profile summary, restart the app, and confirm the summary remains while the raw payload is absent from logs and UI diagnostics.

- [ ] **Step 7: Commit local storage.**

```bash
git add app/src/main/java/com/stravo/vpn/data
git commit -m "feat: persist STRAVO profiles and settings locally"
```

## Task 6: Implement profile import and validation

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/domain/importing/ProfileImportSource.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/importing/ProfileImporter.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/importing/ProfileValidator.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/importing/ImportResult.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/importing/ImportScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/importing/ImportViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml` only if the selected QR scanner requires camera permission.

**Interfaces:**

```kotlin
sealed interface ProfileImportSource {
    data class Url(val value: String) : ProfileImportSource
    data class Clipboard(val value: String) : ProfileImportSource
    data class QrText(val value: String) : ProfileImportSource
}

interface ProfileImporter {
    suspend fun import(source: ProfileImportSource): ImportResult
}
```

- [ ] **Step 1: Define source normalization.** Trim whitespace, reject empty input, preserve URL encoding, and classify share links, subscription URLs, and full configuration payloads without printing them.

- [ ] **Step 2: Define supported profile boundary.** Accept only explicitly supported protocols/fields from the target profile contract; reject an unknown mode or incomplete configuration rather than silently substituting an ordinary profile.

- [ ] **Step 3: Implement phone QR import.** Use the selected maintained Android scanner only if its dependency and permission behavior are compatible with the project. QR text is passed to the same importer as URL and clipboard text.

- [ ] **Step 4: Implement TV import adaptation.** Provide URL entry through the TV keyboard and clipboard/share intents where the platform exposes them. Keep QR pairing behind an explicit `ProfileHandoff` interface so a future pairing endpoint cannot be faked by UI state.

- [ ] **Step 5: Persist only validated profiles.** Save the profile through `ProfileRepository` only after structural validation and `CapabilityPolicy` checks.

- [ ] **Step 6: Render errors in the canvas style.** Show separate messages for empty input, unsupported format, invalid profile, unavailable white-list mode on TV, and storage failure.

- [ ] **Step 7: Validate import manually.** Observe a valid URL, invalid text, QR text on phone, and a white-list profile on TV. Confirm only the valid phone profile is saved and the TV white-list path is rejected.

- [ ] **Step 8: Commit import.**

```bash
git add app/src/main/java/com/stravo/vpn/domain/importing app/src/main/java/com/stravo/vpn/ui/screens/importing app/src/main/AndroidManifest.xml
git commit -m "feat: import and validate STRAVO profiles"
```

## Task 7: Resolve and integrate the target VPN engine

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/domain/engine/VpnEngine.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/engine/EngineError.kt`
- Create: `app/src/main/java/com/stravo/vpn/domain/engine/EngineProfileAdapter.kt`
- Create: `docs/engine-compatibility.md`
- Modify: `app/build.gradle.kts` with the exact selected engine dependency or native artifact.

**Interfaces:**

```kotlin
interface VpnEngine {
    val state: StateFlow<VpnConnectionState>
    suspend fun validate(profile: VpnProfile): EngineValidation
    suspend fun connect(profile: VpnProfile, mode: VpnMode)
    suspend fun disconnect()
}
```

- [ ] **Step 1: Obtain the target profile contract.** Use a redacted sample subscription/profile supplied by the owner or an already authorized server contract. Record protocol, transport, TLS, routing, mode marker, and quota/expiry fields without storing secrets.

- [ ] **Step 2: Select the engine from primary sources.** Compare the target fields with the official engine documentation and Android integration guidance. Pin one engine/version and record the reason in `docs/engine-compatibility.md`.

- [ ] **Step 3: Implement the engine boundary.** Keep profile parsing, engine lifecycle, and UI state separate. A failed validation returns an explicit `EngineError.UnsupportedField` or `EngineError.InvalidProfile`.

- [ ] **Step 4: Implement the profile adapter.** Map only verified fields; preserve ordinary profiles; map white-list profiles only when `VpnMode.WhiteList` is allowed on the current form factor.

- [ ] **Step 5: Validate the adapter without live customer traffic.** Use a redacted local fixture or owner-authorized disposable profile and confirm the engine accepts the exact profile shape. Do not infer compatibility from a green compile.

- [ ] **Step 6: Commit the engine boundary.**

```bash
git add app/build.gradle.kts app/src/main/java/com/stravo/vpn/domain/engine docs/engine-compatibility.md
git commit -m "feat: add verified STRAVO VPN engine adapter"
```

## Task 8: Implement the Android `VpnService` lifecycle

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/service/StravoVpnService.kt`
- Create: `app/src/main/java/com/stravo/vpn/service/VpnServiceController.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/connection/ConnectionRepository.kt`
- Create: `app/src/main/java/com/stravo/vpn/data/connection/ConnectionStateStore.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**

```kotlin
interface VpnServiceController {
    suspend fun prepare(): PrepareResult
    suspend fun connect(profileId: ProfileId, mode: VpnMode): ConnectResult
    suspend fun disconnect(): DisconnectResult
}
```

- [ ] **Step 1: Add the service declaration.** Declare `StravoVpnService` with the Android VPN service permission and no second tunnel owner.

- [ ] **Step 2: Implement preparation.** Request `VpnService.prepare()` from the activity flow and represent cancellation as a user-visible state, not as a generic engine error.

- [ ] **Step 3: Implement serialized connect/disconnect.** Reject or queue duplicate transitions inside the controller; do not add automatic retries to non-idempotent profile writes.

- [ ] **Step 4: Bind the engine.** The service loads the selected profile, checks the form-factor capability, starts the engine, and publishes `preparing`, `connected`, `disconnecting`, or `error`.

- [ ] **Step 5: Handle lifecycle events.** Stop the engine and close resources on service destruction; restore a safe `idle`/`error` state after process death until the profile is revalidated.

- [ ] **Step 6: Handle network loss.** Observe network changes, expose a visible reconnecting state, and only reconnect after the engine/service state confirms that the previous tunnel is stopped.

- [ ] **Step 7: Validate the direct lifecycle.** On an authorized emulator or device, observe permission denial, one connect, one disconnect, process stop, and network-loss state transitions. Do not call this real connectivity proof until the tunnel has sustained traffic through the target profile.

- [ ] **Step 8: Commit service lifecycle.**

```bash
git add app/src/main/java/com/stravo/vpn/service app/src/main/java/com/stravo/vpn/data/connection app/src/main/AndroidManifest.xml
git commit -m "feat: own VPN lifecycle through StravoVpnService"
```

## Task 9: Build onboarding, Home, Servers, and phone-only White Lists screens

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/ui/screens/onboarding/OnboardingScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/onboarding/OnboardingViewModel.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/home/HomeScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/servers/ServersScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/servers/ServersViewModel.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/whitelist/WhiteListScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/whitelist/WhiteListViewModel.kt`

**Interfaces:**

```kotlin
data class HomeUiState(
    val formFactor: FormFactor,
    val connection: VpnConnectionState,
    val selectedServer: ServerEndpoint?,
    val selectedMode: VpnMode,
    val canUseSelectedMode: Boolean,
)
```

- [ ] **Step 1: Implement first-run onboarding.** Explain STRAVO in the paper-map visual language, request VPN permission only when the user chooses setup, and route to import after permission.

- [ ] **Step 2: Implement Home.** Show the current state, large pencil power control, selected server, selected mode, and a safe status line. Do not show a connected badge until the service reports `connected`.

- [ ] **Step 3: Implement Servers.** Render endpoint cards as map panels with server label, available latency/status, selection state, and a clear disabled state when no validated profile exists.

- [ ] **Step 4: Implement the phone White Lists screen.** Show the mode explanation, selected white-list profile, availability state, and connect action. Use the same `CapabilityPolicy` before every action.

- [ ] **Step 5: Keep TV ordinary-only.** `TvShell` must never instantiate `WhiteListScreen` or its ViewModel. If a TV deep link asks for white lists, show a normal error card and remain on Home.

- [ ] **Step 6: Validate visually and behaviorally.** Inspect phone screens in portrait and TV screens in landscape; verify disconnected, preparing, connected, stopping, and error states without running automated tests.

- [ ] **Step 7: Commit the main flows.**

```bash
git add app/src/main/java/com/stravo/vpn/ui/screens
git commit -m "feat: add STRAVO onboarding and connection screens"
```

## Task 10: Complete Import, Settings, Help, and Information screens

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/ui/screens/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/help/HelpScreen.kt`
- Create: `app/src/main/java/com/stravo/vpn/ui/screens/info/InfoScreen.kt`
- Modify: `app/src/main/java/com/stravo/vpn/ui/screens/importing/ImportScreen.kt`

- [ ] **Step 1: Finish Import and subscriptions.** Show local profiles, source type, last refresh time when available, refresh action, delete action with a clear confirmation, and import status. Do not render full raw configuration.

- [ ] **Step 2: Implement Settings.** Add auto-connect, network-loss behavior, and appearance/accessibility controls only when their behavior is implemented end-to-end. Persist through `SettingsRepository`.

- [ ] **Step 3: Implement Help.** Explain permission, import, connection states, server selection, and the fact that white lists are phone-only.

- [ ] **Step 4: Implement Information.** Show STRAVO version, exact engine version once selected, privacy statement, and a safe diagnostics copy action that redacts sensitive fields.

- [ ] **Step 5: Apply TV filtering.** The TV versions of Import, Settings, Help, and Information must preserve remote focus and must not expose white-list labels or commands.

- [ ] **Step 6: Validate the user-visible errors.** Observe empty state, invalid profile, permission denied, engine unavailable, storage failure, and TV white-list rejection as separate paper cards.

- [ ] **Step 7: Commit the remaining screens.**

```bash
git add app/src/main/java/com/stravo/vpn/ui/screens
git commit -m "feat: complete STRAVO support and settings screens"
```

## Task 11: Finish TV focus, accessibility, and responsive behavior

**Files:**

- Modify: `app/src/main/java/com/stravo/vpn/ui/navigation/TvShell.kt`
- Modify: `app/src/main/java/com/stravo/vpn/ui/components/StravoFocusOutline.kt`
- Modify: `app/src/main/java/com/stravo/vpn/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/stravo/vpn/ui/screens/servers/ServersScreen.kt`
- Modify: `app/src/main/java/com/stravo/vpn/ui/screens/importing/ImportScreen.kt`
- Create: `docs/android-tv-acceptance.md`

- [ ] **Step 1: Define focus order.** Set the initial focus to the Home power control, then selected server, then secondary actions. Back returns to the previous route without losing selection.

- [ ] **Step 2: Define focus visuals.** Keep the pencil-dashed outline visible on paper and ensure focused text/buttons remain readable in both selected and unselected states.

- [ ] **Step 3: Handle screen sizes.** Use width-aware Compose layouts for small phones, large phones, and 720p/1080p TV without hard-coded phone-only coordinates.

- [ ] **Step 4: Check accessibility labels.** Every power, server, import, Back, and settings action has a concise spoken label; decorative canvas art is not announced as an action.

- [ ] **Step 5: Verify TV capability exclusion.** Inspect the focusable node inventory and confirm no white-list node, route, or action exists on TV.

- [ ] **Step 6: Record manual acceptance.** `docs/android-tv-acceptance.md` records the exact device/emulator dimensions and observations, distinguishing UI behavior from real tunnel connectivity.

- [ ] **Step 7: Commit TV polish.**

```bash
git add app/src/main/java/com/stravo/vpn/ui app/src/main/java/com/stravo/vpn/ui/screens docs/android-tv-acceptance.md
git commit -m "feat: polish STRAVO Android TV focus behavior"
```

## Task 12: Integrate deep links and safe external entry points

**Files:**

- Create: `app/src/main/java/com/stravo/vpn/platform/StravoIntentRouter.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/stravo/vpn/MainActivity.kt`
- Modify: `app/src/main/java/com/stravo/vpn/domain/policy/CapabilityPolicy.kt`

- [ ] **Step 1: Define accepted intents.** Accept only the documented STRAVO import/share entry points and Android share text; reject unknown schemes and empty payloads.

- [ ] **Step 2: Route through the importer.** Every external profile enters `ProfileImporter`; no deep link directly starts `VpnService`.

- [ ] **Step 3: Enforce TV mode policy.** A TV intent that identifies white lists produces a safe rejection state and does not change selected mode or start the service.

- [ ] **Step 4: Redact diagnostics.** Intent errors show source type and reason, never the raw URL or profile payload.

- [ ] **Step 5: Validate manually.** Observe a valid share text, invalid scheme, empty payload, and TV white-list intent. Confirm only the valid source reaches the import screen.

- [ ] **Step 6: Commit entry-point handling.**

```bash
git add app/src/main/java/com/stravo/vpn/platform/StravoIntentRouter.kt app/src/main/AndroidManifest.xml app/src/main/java/com/stravo/vpn/MainActivity.kt app/src/main/java/com/stravo/vpn/domain/policy/CapabilityPolicy.kt
git commit -m "feat: route STRAVO imports through safe entry points"
```

## Task 13: Build the first owner-observable APK and update handoff

**Files:**

- Modify: `CONTEXT_HANDOFF.md`
- Modify: `docs/engine-compatibility.md` if the engine gate completed
- Create: `docs/first-apk-acceptance.md`

- [ ] **Step 1: Inspect the final Git scope.** Confirm only STRAVO project files changed, `.superpowers/` remains ignored, and no secret-like values appear in tracked content.

- [ ] **Step 2: Build a debug APK.** Use the project’s pinned wrapper and the exact debug assemble task. Do not sign or publish a release build without explicit authorization.

- [ ] **Step 3: Observe the launch result.** On an authorized Android phone and Android TV target, confirm package name, STRAVO branding, visual system, navigation, TV focus, and the absence of TV white lists.

- [ ] **Step 4: Separate connection evidence.** Record whether the result is only a UI/build artifact, profile-import success, permission success, or a sustained real tunnel connection. Never upgrade one evidence class into another.

- [ ] **Step 5: Record skipped checks.** `docs/first-apk-acceptance.md` explicitly lists any unavailable device, missing target profile, unrun automated tests, and unverified server state.

- [ ] **Step 6: Update `CONTEXT_HANDOFF.md`.** Include current commit, exact APK path/hash if generated, completed tasks, remaining engine/server gate, and the next smallest action.

- [ ] **Step 7: Commit the checkpoint.**

```bash
git add CONTEXT_HANDOFF.md docs/engine-compatibility.md docs/first-apk-acceptance.md
git commit -m "docs: record STRAVO first APK checkpoint"
```

## Stop gates and owner inputs

The implementation can proceed through project bootstrap, domain policy, design system, navigation, local storage, and UI without a production server. The actual VPN engine and sustained tunnel proof stop at Task 7 until the target subscription/profile contract is available and its exact fields are verified against the selected engine’s primary documentation.

Required owner input before engine/live-connection work:

- a redacted sample of the intended subscription or profile format;
- the ordinary VPN protocol/profile contract;
- the phone white-list profile contract, if it is a separate mode;
- confirmation of the allowed non-production device/emulator for manual observation.

The project is not considered a working VPN service merely because the Android project compiles or the UI imports a profile.
