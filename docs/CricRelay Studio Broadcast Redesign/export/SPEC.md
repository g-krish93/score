# CricRelay Studio — Handoff: Direction 1 + Scoreboard Overlay

Files:
- `Studio Direction 1.html` — self-contained design reference: 1a Console / 1b Checklist gate / 1c Ribbon, each as idle portrait (393x852) + LIVE landscape (852x393).
- `Scoreboard Overlay.html` — self-contained board spec: anatomy, batting+bowling island pair, presets P1-P5, data states D1-D4, slider extremes, motion.
- `Arrange Mode Prototype.html` — WORKING interaction reference: drag board/sponsor (pointer events, % coordinates), pinch or corner-handle resize (scale 0.6-1.8), snap to centre lines + 16px safe margins (7-8px threshold), live % readout, Done persists / Cancel reverts. Sponsor drag = position only; its size stays a sheet slider.
- `src/` — editable source files (Design Component format).
Gen-Z skin (turn 2) and heritage themes (turn 3) stay in the working project for future use; not part of this export.

## Tokens (Floodlight, dark-only)
| Token | Value |
|---|---|
| Background / Canvas / Surface / Elevated / Sunken | #0A0E15 / #0D1219 / #141A26 / #1C2433 / #070A10 |
| Primary gold (base / bright / deep) | #FFC233 / #FFD15C / #E8A912 — ALWAYS ink text #1A1305 on gold |
| Accent sky / blue | #57C7FF / #4DA3FF — ready, info, success (health "green" = sky) |
| Warning coral | #FF9466 — caution, paused, thermal |
| Error | #FF5C7A — danger only (STOP, muted mic) |
| Text / muted / dim | #FFFFFF / #C7CDD9 / #98A1B3 |
| Border / subtle / glass | #323B4D / #222A3A / rgba(255,255,255,0.20) |
| Platform | YouTube #FF0033, Twitch #9146FF |
CTA gradient: 180deg #FFD15C -> #E8A912. Brand: 1e mark green #2E5E32 / cream #D8C9A3.

## Type
- Archivo 800/900: wordmark, ON AIR, timers, GO LIVE, scoreboard team+score.
- DM Sans 400/500/700: all other UI. Body 15sp; micro-labels >= 9px only on high-contrast pills.

## Surfaces over live video (contrast floors: 7:1 body, 4.5:1 interactive, ~10:1 over video)
- Glass pill: rgba(9,13,20,0.78) + 1px rgba(255,255,255,0.20), radius 14-18.
- Dock/panel: rgba(7,10,16,0.85) + 1px rgba(255,255,255,0.14), radius 24.

## Layout
Spacing 4/8/16/24/32. Radii 10/14/18/24. Touch >= 48dp (glance pills 64w, transport 56). Landscape is immersive (OS bars hidden); portrait keeps status bar + home indicator.

## Component states
- GO LIVE: h64 gold gradient, ink Archivo 800. Blocked = guidance, never dead: 1b ring shows N/3 segments + inline "fix" row; caption names the missing check.
- Setup chips (destination/board/scoring): readiness sublabel in sky; platform dot on destination.
- Control pills: default glass+white; active AF-lock = gold border + rgba(255,194,51,0.14) bg; muted mic = error border + rgba(255,92,122,0.16) bg + slash.
- ON AIR badge: gold gradient, ink pulsing dot (1.6s). PAUSED variant: coral. Health dot: sky/coral/error, 2.4s pulse, next to "1080p30 · 4.2 Mb/s".
- Focus reticle: 62px white ring free; gold ring + "AE·AF LOCK" tag locked.
- Zoom pill: hidden <= 1.1x.
- STOP: error border + tint, 16px square glyph.

## Hierarchy rules (the redesign)
- Setup tools (destination, board, scoring) live in the pre-live dock; when live the dock collapses to a single BOARD chip.
- ONE destination affordance (chip in dock). Stabilization / orientation / keep-screen-on -> Camera settings sheet, pre-live only.
- Arrange mode is the ONLY placement tool: board drag + pinch resize, sponsor drag (sponsor is never fixed). Sheets keep style-only controls.

## Motion
Enter 240ms ease-out from 95% scale; exit 160ms; sheets 260/180ms; press <= 160ms at 97%. Respect reduced-motion (kill pulses).

## Scoreboard overlay (composited into stream)
Structure: row1 = TEAM (Archivo 800) + SCORE (accent, Archivo 800) + OVERS (muted); row2 strip = batsmen + context (CRR / target / need), auto-hides at min board height. Radius 10, shadow 0 4px 16px rgba(0,0,0,0.4).

Presets (row1 bg / row2 bg / text / accent):
- P1 Floodlight: rgba(10,14,21,0.88) / rgba(7,10,16,0.82) / #FFF / #FFC233
- P2 Chalk: rgba(242,237,226,0.95) / rgba(230,224,209,0.95) / #1A1305 / #2E5E32
- P3 Club Green: rgba(30,61,34,0.92) / rgba(21,45,24,0.92) / #FFF+#D8C9A3 / #FFC233
- P4 Broadcast Blue: rgba(14,42,74,0.92) / rgba(10,32,58,0.92) / #FFF / #57C7FF
- P5 Mono: rgba(0,0,0,0.92) / rgba(0,0,0,0.85) / #FFF / #FFF

Bowling island (separate box, drags with board, toggles Bowler/Off): takes the preset CONTRAST surface —
- P1: header gold gradient + ink text; THIS OVER strip stays dark (W coral #FF9466, boundary gold #FFC233).
- P2: club-green rgba(46,94,50,0.95) + white/gold.
Gap between islands: 8-10px, bottom-aligned.

Data states: D1 first innings (CRR), D2 chase (Target + need N (balls)), D3 wicket (score goes coral + W chip, auto-clears ~4s), D4 manual scorer (single row).
Sliders: width 25-98%, height 10-28% (strip auto-hides at min), font 60-200%, opacity 20-100% (warn below 60% for sunlight).
Motion: score digit tick 160ms ease-out; wicket chip 240ms in from 95% / hold ~4s / 160ms out; style edits apply live ~80ms debounce with NO transition; position never animates.
