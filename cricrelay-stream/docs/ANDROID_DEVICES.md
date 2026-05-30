# Android devices — smooth streaming on any phone

CricRelay Stream adapts encoder load, overlay cost, and UI work to the phone it runs on. **minSdk 24** (Android 7.0) through **targetSdk 35**.

## Device tiers

| Tier | Detection | Default quality | Overlay refresh | EIS default |
|------|-----------|-----------------|-----------------|-------------|
| **Low** | `isLowRamDevice` or &lt; 3 GB RAM | Low (640×360) | ~1.2 s | Off |
| **Mid** | 3–6 GB RAM | Medium (854×480) | ~0.8 s | On |
| **High** | ≥ 6 GB RAM | High (720p) | ~0.5 s | On |

Volunteers can still pick quality manually in **Stream quality** — tier only sets the first-run default.

## Encoder fallback ladder

If `prepareVideo` fails at the requested resolution, native code steps down automatically:

1. Requested (from quality + orientation)
2. 1280×720 @ 2.5 Mbps
3. 854×480 @ 1.5 Mbps
4. 640×360 @ 24 fps, 0.8 Mbps

The app emits `preview_ready` when preview succeeds so Flutter does not poll blindly.

## Under memory or battery pressure

- **Low memory** (`onTrimMemory`): overlay WebView capture pauses; stream continues.
- **Power save / thermal**: overlay refresh interval increases (up to ~2.5 s).
- **Surface lost** (rotation, PiP): waits for valid GL surface before re-prepare — never re-prepares while live.

## Flutter performance

- Overlay drag uses **local state** — parent screen does not rebuild every pixel.
- Preview stack is behind a **RepaintBoundary**.
- Orientation re-prepare is **debounced** (450 ms).
- Zoom UI updates are **throttled**; native zoom applies immediately.
- Live notification updates **once per minute**, not every second.

## PiP

Picture-in-picture aspect matches stream orientation (9:16 portrait, 16:9 landscape).

## Still device-dependent

Incoming calls, aggressive OEM battery savers, and thermal shutdown can affect any app. See [BROADCAST_RESILIENCE.md](BROADCAST_RESILIENCE.md).

## Verify on a new phone

1. Open broadcast screen — preview within a few seconds (`preview_ready`).
2. Drag overlay — smooth, no stutter.
3. Go Live → Pause → Resume → Stop.
4. Rotate before Go Live — preview recovers without crash.

Run full gate before release:

```bash
bash cricrelay-stream/scripts/ci_validate.sh
```
