# Handoff: CricRelay Logo + Opening Splash Animation

## Overview
CricRelay is a one-stop app for UK cricket clubs (live streaming + team collaboration). This package contains the two brand deliverables to implement:
1. **Primary logo (option "1e")** — pitch mark: green rounded square, cream 22-yard pitch with two crease lines
2. **Opening splash animation (final: "1e v2")** — a ~2s cinematic shot played on cold start, ending on the logo lockup (the landing visual)

A secondary logo (option "2a", lime seam ball) and its splash variant are included for reference.

## About the Design Files
These files are **design references created in HTML** — prototypes showing intended look and behavior, not production code to ship. Recreate them in the target codebase:
- **Android**: Kotlin + Jetpack Compose — `Canvas` composable driven by `withFrameNanos`/`rememberInfiniteTransition`, launched from the Android 12+ `SplashScreen` API, then navigate to home.
- **iOS**: SwiftUI `TimelineView` + `Canvas` (or SpriteKit).
- The renderer in `Splash Animation 1e v2.dc.html` is a **single self-contained JS `draw(canvas)` function with a 3D→2D perspective projection**; every frame is a pure function of elapsed time `t`. The math ports 1:1. Treat that source as the spec.
- Production behavior: play once per cold start, **skip on tap**, then transition to home. The lockup frame doubles as the transition frame.

## Fidelity
**High-fidelity.** Colors, timing, easing and composition are final. Recreate faithfully.

## Primary Logo (1e — pitch mark)
- App icon: square, corner radius ≈ 23% of size, background green `#2E5E32`
- Pitch: vertical rounded rect, cream `#D8C9A3`, ≈ 31% wide × 65% tall of icon, corner radius ≈ 27% of its width, centered
- Crease lines: two horizontal green bars (`#2E5E32`) inset near top and bottom of the pitch, ≈ 60% of pitch width, height ≈ 4% of pitch height
- Wordmark: `cricrelay`, lowercase, **Archivo** 800/900, letter-spacing −1px, green `#2E5E32` on light / cream `#E0D3AE` on dark
- Tagline: "your club's home ground" — **DM Sans** 500
- Light surface: `#F5F3EC` / gradient `#F8F6EF → #EDEAE0`

## Splash Animation — Final Timeline (~2.0s wall clock)
Internally keyed on a 5.2s timeline played at 160/63 ≈ 2.54× speed. Times below are the internal keys (multiply by 0.394 for wall clock).
- **0–0.4** Fade in from black on an extreme close-up of the leather ball, slow readable rotation
- **0.5–1.7** Continuous camera **pull-back** to umpire's view; ball glides into a release point **left of the stumps** (right-arm over). Camera easing: ease-in-out quartic
- **1.45** Release (overlaps pull-back — deliberately no dead beat)
- **1.45–2.8** Delivery with broadcast **speed ramp** (`pMap`: 55% of time → 75% of distance; slow-mo 55–90%; final 10% at full speed). In-swing: `x = −0.38·(1−sin(qπ/2))`, pitches at 66% of the length (dust puff, not a ring), slight jag, spin ramps 3.6→16 rad/s
- Camera is a **tracking shot** chasing the ball, settling ~3/4 down the pitch at impact; subtle handheld drift throughout; shake amplitude 0.10 decaying 0.45s on impact
- **2.8** Impact: middle stump rotates ~1.1 rad about its base; two bails fly ballistically (g = 9.8, spin ~9 rad/s); 90ms white exposure kick (16% alpha)
- **2.9–3.5** **Light-bloom match cut**: the warm impact glow swells (ease-in-out) until it becomes the cream lockup screen — no overlay fade
- **3.5–4.3** Lockup zooms **in** from 72% → 100% with a refined overshoot settle (easeOutBack, c = 1.20158): 1e icon (84px), wordmark Archivo 800 42px green, tagline DM Sans 500 16px at 60% ink
- **5.2** End state = landing visual; "tap to replay"

## Scene / Rendering Spec
- World space: x lateral (m), y up, z along pitch; stumps at z = 20.1, height 0.71–0.72, spacing 0.14, bails at y = 0.745
- Perspective projection: camera `{x, y, z, tilt}`, focal `f = 0.95 × viewportHeight` — see `project()`
- **Grade (teal & tungsten)**: sky `#08111A → #0E141A → #191A11` (warm sodium horizon); field `#191A11 → #12150D → #080B07`; warm light pools on the outfield `rgba(255,235,195,0.05)`; atmospheric haze band at the horizon `rgba(185,175,150,0.08)`; teal-leaning vignette `rgba(4,9,14,0.55)`; 8%-height letterbox bars (retract at lockup); animated film grain (128px noise tile, ~4% effective alpha)
- **Pitch**: strip x ∈ [−1.55, 1.55], amber gradient `#2A2415 → #463C24 → #3C3420`, white outline 8%, mowing stripes `rgba(255,255,255,0.028)` every 2.55m, crease lines `rgba(238,238,228,·)` at z = 1.2 / 18.9 / 20.1
- **Ball**: radial-gradient leather sphere `#F0855A → #B93A1E → #450E06` (lit top-left), rotating stitched seam band (ellipse rx = 0.30r, cream `rgba(242,228,200,0.9)`, cross-stitches when r > 10px), rim light arc, elliptical contact shadow tracking on the deck, faint motion smear `rgba(226,110,80,0.10)`
- **Stumps**: pale willow `#E0D3AE` with dark edge stroke (roundness) + specular sliver, soft contact shadow at the base
- **Floodlights**: 4 heads at (±9, 6.5, 26) / (±11, 5.5, 4), warm tungsten cores `rgba(255,241,214,0.34)`

## Design Tokens
- Greens: `#2E5E32` (brand), icon surface; dark scene greens `#12150D`, `#080B07`
- Cream: `#D8C9A3` (pitch), `#E0D3AE` (willow/stumps), surfaces `#F5F3EC`–`#F8F6EF`
- Leather: `#B93A1E` (mid), `#F0855A` (highlight), `#450E06` (shadow)
- Ink on light: `rgba(47,42,36,·)`
- Fonts: **Archivo** 800/900 (wordmark), **DM Sans** 400/500 (tagline, hints) — Google Fonts

## State Management
- `t` (elapsed seconds) is the only state; every frame is a pure deterministic function of `t` — scrubbable and testable
- The HTML persists `t` in `localStorage` (`cricrelay_splash1e_v2_t`) so the preview resumes; in production play once per cold start and allow tap-to-skip
- On completion navigate to home (lockup = transition frame)

## Assets
No external assets; everything is drawn in code. Fonts from Google Fonts.

## Files
- `Splash Animation 1e v2.dc.html` — **FINAL splash.** `class Component` contains the full renderer: `camera(t)`, `ballAt(p)`, `pMap(u)`, `easeIO`, `easeOutBack`, `project()`, `draw(canvas)`. This is the implementation spec.
- `Logo Explorations.dc.html` — all logo directions. **1e is the primary logo**; 2a (lime seam ball) is the approved secondary.
- `Splash Animation 2a.dc.html` — earlier splash themed for the 2a lime identity (reference only; slower, pre-final timing).
