# ADR-001: Scope of the KMP shared module — iOS adoption vs. Android-only

**Status:** Accepted (Option A — iOS adopts the shared data layer, scoped)
**Date:** 2026-07-03 (decided same day)
**Deciders:** Gopi

## Context

`cricrelay-mobile/shared` is a Kotlin Multiplatform module containing the data layer:
models (`Models.kt`, ~640 lines), the Ktor API client (`CricRelayApiClient.kt`, ~540
lines), repositories, and session/token storage with per-platform implementations
(EncryptedSharedPreferences on Android, Keychain on iOS). It declares `iosArm64` /
`iosX64` / `iosSimulatorArm64` targets and CI builds them.

**Nobody on iOS consumes it.** The July 2026 architecture audit confirmed the iOS app
imports no shared framework; `project.yml` lists only HaishinKit. Instead, iOS
hand-duplicates ~13 model structs (`Models.swift`) and ~30 API endpoints
(`CricRelayAPI.swift`), including slug encoding, error mapping, and prefs sanitization
logic.

The cost of this drift is not hypothetical — it has been paid at least three times:

1. The June 2026 iOS parity project re-implemented behavior the Kotlin client already had.
2. The July 2026 iOS pre-demo audit fixed the `overlay_` key-prefix mismatch — a bug that
   existed only because prefs (de)serialization is written twice.
3. The July 2026 iOS static review's fix batches (401 handling, pause-mic parity,
   stabilization sanitization) were re-fixes of semantics the shared module already encoded.

Meanwhile the project pays KMP's full complexity cost (Ktor multiplatform, expect/actual
session stores, iOS framework CI builds) and receives cross-platform value of zero.

## Decision

**Proposed: Option A (scoped).** Adopt the shared module on iOS for the data layer only —
models, API client, session store — via an XCFramework wired into the existing XcodeGen +
GitHub Actions macOS build. UI (SwiftUI) and the streaming engine (HaishinKit/AVFoundation)
stay native; they are platform-API-dominated and gain nothing from sharing.

## Options Considered

### Option A: iOS adopts `shared` for the data layer (scoped)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium — XCFramework build step + Obj-C interop ergonomics |
| Cost now | ~1–2 weeks incremental (models first, then API client, then session) |
| Cost over time | Drift eliminated for the layer where all three drift incidents happened |
| Team familiarity | Kotlin already primary; Swift call sites shrink |

**Pros:**
- One definition of every wire format, sanitizer, and endpoint; a server change lands once.
- The audit's duplication findings (~13 models, ~30 endpoints) disappear structurally.
- The shared module's tests (currently 10, growing under the P2 test build-out) start
  protecting both platforms.
- Migration can be incremental: `Models` → `CricRelayApiClient` → `SessionStore`, one PR
  each, deleting Swift counterparts as each lands.

**Cons:**
- KMP/Xcode build friction (framework linking, debug symbols, build time on CI macOS).
- Obj-C-flavored API surface in Swift (no default args across the boundary without
  annotations; sealed classes flatten).
- A KMP/Kotlin upgrade can now block an iOS release.

### Option B: Declare the module Android-only; keep the Swift duplicate

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — delete iOS targets, stop building them in CI |
| Cost now | Hours |
| Cost over time | Permanent double-maintenance; parity audits forever |
| Team familiarity | No change |

**Pros:** Simplest build; zero interop risk; iOS stays idiomatic Swift end-to-end.
**Cons:** The three drift incidents keep recurring by construction; every server change is
implemented and tested twice; the "multiplatform" module is a misnomer that misleads
future contributors.

### Option C: Contract-first (OpenAPI spec, generate both clients)

**Pros:** Platform-idiomatic clients; server becomes the single source of truth.
**Cons:** The Flask server has no OpenAPI spec today — writing and maintaining one is a
larger project than Option A; generated clients still don't share the client-side
sanitization/merge logic (e.g. `OverlayLayoutPrefs.mergeSponsorPatch`) where real bugs
lived. Rejected as more work for less sharing.

## Trade-off Analysis

The decisive fact is empirical: all recorded drift bugs were in the data layer, and none
were in UI or streaming. Option A shares exactly the layer with the proven failure mode
and nothing else. Option B is only preferable if iOS is expected to be retired or frozen —
current strategy (App Store presence, feature parity shipped June 2026) says the opposite.

## Consequences

- **Easier:** server-driven changes (new endpoints, model fields), parity maintenance,
  testing the data layer once.
- **Harder:** iOS build setup (one-time), Kotlin upgrades now on the iOS critical path.
- **Revisit if:** KMP tooling friction consumes more time than parity fixes did, or iOS
  is deprioritized.

## Action Items

1. [x] Spike: build `shared` as an XCFramework from the existing macOS CI job and link it
       into `CricRelayLive.xcodeproj` via XcodeGen (no code migration yet).
       *Done 2026-07-03:* static `Shared.xcframework` via `:shared:assembleSharedReleaseXCFramework`;
       linked (not embedded) in `project.yml` with a pre-build Gradle script for local Mac
       builds; CI assembles it in both the validate workflow (new macOS job, fails PRs) and
       the build workflow's iOS job before xcodegen. `SharedKitProbe.swift` exercises a
       model constructor + `UrlValidatorKt` call at DEBUG bootstrap. Apple-target linking
       is impossible on the Windows dev box (`compileKotlinIosArm64` SKIPPED) — the CI
       macOS run is the binding proof.
2. [x] Migrate `Models.swift` consumers to the shared models; delete duplicated structs.
       *Done 2026-07-03:* `FixtureItem`, `PlatformStatus`, `StreamMatch`, `BroadcastStatus`,
       `GoLiveResult`, `FixturesResponse`, `ScoringConfig`, `MatchDayStatus`, `Sponsor`,
       `PairRemoteResult`, `RemoteCommand`, `RemoteCompanionContext` migrated; Swift structs
       deleted. `OverlayLayoutPrefs` uses a **boundary-mapping pattern** instead of full
       replacement: the Kotlin model (now carrying `overlay_enabled` and the legacy
       unprefixed-key fallback) owns serialization, sanitization, and the sponsor-patch
       merge; the Swift struct remains as the SwiftUI-editable value type, crossing through
       `toShared()`/`init(shared:)` — Swift's definite-initialization rule makes a missed
       field a compile error. Trade-off: the field list exists in both languages, but every
       semantic that ever drifted (wire keys, clamps, merge key-list) exists once.
3. [x] Migrate `CricRelayAPI.swift` call sites to `CricRelayApiClient`; delete the Swift client.
       *Done 2026-07-03:* every endpoint delegates to the shared client; the URLSession
       request layer is deleted outright. The shared client gained `@Throws` on every public
       suspend function (without it Kotlin exceptions terminate the iOS process instead of
       arriving as NSError) and an `onSessionExpired` callback with sessionAuth-aware 401
       handling. **Android now consumes the same callback** (AuthRepository installs it on
       every client; SessionEvents → token clear + toast + nav-host bounce to login),
       closing the parity gap the other way for once. The per-slug prefs cache reads/writes
       through the Kotlin codec (`toJsonString`/`fromJsonString`), so old caches — including
       pre-prefix legacy keys — keep their saved arrangement. `SharedKitProbe.swift` retired:
       real call sites exercise the framework on every screen.
4. [x] Migrate token storage to the shared `SessionStore` (Keychain actual already exists).
       *Done 2026-07-03:* SessionViewModel bootstraps/persists via the shared `SessionStore`;
       `KeychainHelper.swift` deleted. The iOS actual uses the exact NSUserDefaults keys and
       Keychain service/account the Swift code always used (including the legacy
       UserDefaults→Keychain migration), so existing installs keep their session.
5. [ ] Add the shared-module test build-out (P2) so the newly shared layer is guarded.

### Interop notes from the spike (2026-07-03)

- **Default args are lost:** only the full-arg initializer crosses the Obj-C boundary.
  `PlatformStatus()` was restored with a Swift `convenience init` extension
  (`ios/Features/SharedModels+App.swift`) rather than editing every call site.
- **Codable is lost:** shared models can't be `JSONDecoder`-decoded, so migrated endpoints
  hand-map `[String: Any]` → shared constructor until `CricRelayApiClient` owns the
  endpoint (item 3). This is interim glue, not the end state.
- **Swift protocol conformances live in extensions:** `Identifiable` for `ForEach` was
  added retroactively; works cleanly.
- **Reference semantics:** shared models arrive as Obj-C classes, not structs. Fine for
  read-only DTOs; audit any struct that call sites mutate member-wise before migrating it.
- **Name shadowing during migration:** while a Swift duplicate exists, unqualified names
  resolve to the app's type — delete the Swift struct in the same change, or qualify
  `Shared.X`.
- **Collections bridge fine:** Kotlin `List<FixtureItem>` → Swift `[FixtureItem]` via
  lightweight generics.
- **Top-level functions** surface as statics on `<File>Kt` (e.g.
  `UrlValidatorKt.normalizeApiBaseUrl(raw:)`). Tolerable for the validator; consider
  `@ObjCName` if the shared API surface grows.
- **Sealed classes:** not exercised — neither migrated model uses them. Expect flattening
  when the API client's result types cross (item 3).

### Additional interop notes from the client/session migration (2026-07-03)

- **`@Throws` is mandatory on suspend functions consumed from Swift:** an undeclared Kotlin
  exception crossing the bridge terminates the process. Every public suspend function on
  `CricRelayApiClient` now carries `@Throws(Exception::class)`; the messages arrive as
  `NSError.localizedDescription`, which is exactly what the Swift error paths already show.
- **Suspend functions box primitive returns:** `suspend fun …): Boolean` reaches Swift as
  `KotlinBoolean` (unbox with `.boolValue`). Kotlin `Int` *properties* surface as `Int32`
  (`FixturesResponse.slotsUsed` needs `Int(...)` at the call site).
- **NSObject already conforms to Identifiable:** declaring `extension SharedModel:
  Identifiable` is a redundant-conformance compile error, and the inherited
  ObjectIdentifier id churns on every fetch. List sites use explicit keys instead
  (`ForEach(streams, id: \.slug)`).
- **Immutable reference models need Kotlin copy helpers:** Swift can't call data-class
  `copy` usefully (all-args, no defaults), so targeted helpers live in Kotlin
  (`StreamMatch.withLabel`) — one line each, reusable from Android.
- **Opaque types round-trip fine:** `youtubeStatus()` returns a kotlinx `JsonObject` Swift
  can't inspect, but passing it straight into `PlatformStatus.companion.fromYoutube(json:)`
  keeps the mapping (including the `live_streaming_enabled` fallback the Swift duplicate
  had silently dropped) in shared code.
- **Session wiring:** the Kotlin client owns base/token (constructed per login, mirroring
  the shared `AuthRepository`); its `onSessionExpired` fires on main-token 401s and the
  Swift facade translates that into the existing Keychain-clear + notification flow.
- **The macOS validate job caught a latent bug on its first run:** `KeychainTokenStore.kt`
  (iosMain) passed Kotlin `Map`s where the Security framework takes `CFDictionaryRef`, and
  used the wrong out-pointer type for `SecItemCopyMatching` — it had never compiled,
  because nothing built Apple targets before this ADR's CI wiring. Rewritten with
  CoreFoundation dictionaries and explicit `CFBridgingRetain`/`Release` ownership.
