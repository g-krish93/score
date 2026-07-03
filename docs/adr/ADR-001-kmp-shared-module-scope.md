# ADR-001: Scope of the KMP shared module — iOS adoption vs. Android-only

**Status:** Proposed
**Date:** 2026-07-03
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

1. [ ] Spike: build `shared` as an XCFramework from the existing macOS CI job and link it
       into `CricRelayLive.xcodeproj` via XcodeGen (no code migration yet).
2. [ ] Migrate `Models.swift` consumers to the shared models; delete duplicated structs.
3. [ ] Migrate `CricRelayAPI.swift` call sites to `CricRelayApiClient`; delete the Swift client.
4. [ ] Migrate token storage to the shared `SessionStore` (Keychain actual already exists).
5. [ ] Add the shared-module test build-out (P2) so the newly shared layer is guarded.
