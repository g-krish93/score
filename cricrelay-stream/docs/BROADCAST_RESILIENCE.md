# Broadcast resilience (Android)

What CricRelay Stream tries to guarantee while you are **live**, and what remains device-dependent.

## Guaranteed (when Go Live succeeds)

| Behavior | How |
|----------|-----|
| CPU stays awake when screen off | `PARTIAL_WAKE_LOCK` in `StreamCaptureService` |
| Foreground service while live | `camera` + `microphone` FGS type (Android 14+) |
| Stream continues if you leave the app briefly | PiP mini-player + return notification action |
| Orientation locked after Go Live | `SystemChrome` + Activity `requestedOrientation` |
| Encoder prepared once at preview | RootEncoder golden path — no re-prepare on Go Live |

## User-controlled

| Setting | Effect |
|---------|--------|
| **Keep screen on while live** (overlay settings) | When on, display stays on; when off, screen can turn off while RTMP continues |
| **Steady stream (EIS)** | Enables RootEncoder video stabilization when the device supports it |
| **Pause broadcast** | Black frame + muted audio while RTMP stays connected (rain delays, innings break) |

## Safe to change while live

These update the stream **without** re-preparing the encoder:

| Control | Notes |
|---------|--------|
| **Pause / Resume** | Keeps RTMP connected; viewers see black + silence while paused |
| **Scoring mode** | Server-side only |
| **Overlay layout** (when unlocked) | Native `updateOverlay` only — no `prepareVideo` |
| **Zoom** | Safe on native camera |
| **Keep screen on / EIS toggles** | EIS cannot be toggled mid-stream (ignored by native layer) |

## Locked while live

| Control | Why |
|---------|-----|
| **Stream quality** | Re-preparing video crashes some devices (Pixel 9) |
| **Destination / stream key** | Would require stopping RTMP |
| **Orientation** | Encoder dimensions are fixed at Go Live |

## Best-effort (not guaranteed on all phones)

| Scenario | Notes |
|----------|--------|
| Incoming phone call | Audio focus is requested with `AUDIOFOCUS_GAIN`; mic may still drop on some OEMs. Stream is **not** auto-stopped. |
| Switching apps for a long time | PiP helps; some devices may still throttle the camera. Tap the notification to return. |
| Very low battery / thermal | Android may kill the process despite FGS. |

## Volunteer checklist

1. Hold the phone in the orientation you want **before** Go Live (it locks after).
2. Drag the scoreboard on preview, then **lock overlay**.
3. Allow notifications (Android 13+) for the live alert.
4. For windy days, leave **Steady stream (EIS)** enabled in overlay settings.
