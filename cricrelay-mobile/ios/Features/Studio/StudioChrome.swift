import SwiftUI

// MARK: - Floodlight studio chrome (mirrors Android StudioChrome.kt)
//
// Glass pills + dock surfaces over live video, the segmented Go Live ring, the
// checklist panel, the live broadcast bug, and the live transport strip.
// Geometry follows the 1b mock: ring 98pt (3 × 112° arcs, 8° gaps, from −90°),
// checklist rows 52pt r13 inside a r18 dock, transport pills 48pt r14.

// MARK: - Surfaces

extension View {
    /// Glass pill over live video: rgba(9,13,20,0.78) + 1px rgba(255,255,255,0.20).
    func glassPillSurface(cornerRadius: CGFloat = 16) -> some View {
        background(CricTheme.glassPillBg, in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(CricTheme.glassBorder, lineWidth: 1)
            )
    }

    /// Dock/panel over live video: rgba(7,10,16,0.85) + 1px rgba(255,255,255,0.14).
    func dockSurface(cornerRadius: CGFloat = 24) -> some View {
        background(CricTheme.dockBg, in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(CricTheme.dockBorder, lineWidth: 1)
            )
    }
}

/// Shared visual state for control pills: default glass, gold "active", error "muted".
enum StudioPillState {
    case idle
    case gold
    case error

    var tint: Color {
        switch self {
        case .idle: return .white
        case .gold: return CricTheme.primary
        case .error: return CricTheme.danger
        }
    }
}

// MARK: - Segmented Go Live ring

/// 98pt readiness ring: three trimmed 112° arcs with 8° gaps starting at −90°.
/// Done segments gold, pending segments ringTrack. Inner disc (inset 7) is blocked
/// (near-black + dim GO LIVE + coral fix label) until every check passes, then flips
/// to the CTA gradient with ink GO LIVE. Always tappable — blocked taps are guidance.
struct SegmentedGoLiveRing: View {
    let completedCount: Int
    let ready: Bool
    let busy: Bool
    let fixLabel: String?
    let action: () -> Void

    private static let segmentSweep = 112.0
    private static let segmentPitch = 120.0  // 112° arc + 8° gap

    var body: some View {
        Button(action: action) {
            ZStack {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .trim(
                            from: Double(index) * Self.segmentPitch / 360.0,
                            to: (Double(index) * Self.segmentPitch + Self.segmentSweep) / 360.0
                        )
                        .stroke(
                            index < completedCount ? CricTheme.primary : CricTheme.ringTrack,
                            style: StrokeStyle(lineWidth: 4, lineCap: .butt)
                        )
                        .rotationEffect(.degrees(-90))
                        .padding(2)
                }
                innerDisc
                    .padding(7)
            }
            .frame(width: 98, height: 98)
        }
        .buttonStyle(PressableScaleStyle())
        .animation(CricMotion.enter(), value: completedCount)
        .animation(CricMotion.enter(), value: ready)
        .accessibilityLabel(ready ? "Go live" : "Go live — \(fixLabel ?? "checks pending")")
    }

    private var innerDisc: some View {
        ZStack {
            if ready {
                Circle().fill(CricTheme.ctaGradient)
            } else {
                Circle().fill(Color(red: 0.027, green: 0.039, blue: 0.063).opacity(0.92))
                Circle().stroke(Color.white.opacity(0.1), lineWidth: 1)
            }

            if busy {
                ProgressView()
                    .tint(ready ? CricTheme.onPrimary : .white)
            } else {
                VStack(spacing: 2) {
                    Text("GO LIVE")
                        .font(CricFont.archivo(13))
                        .tracking(0.8)
                        .foregroundStyle(ready ? CricTheme.onPrimary : Color.white.opacity(0.45))
                    if !ready, let fixLabel {
                        Text(fixLabel)
                            .font(CricFont.dmSans(9, weight: .bold))
                            .foregroundStyle(CricTheme.warning)
                    }
                }
            }
        }
    }
}

// MARK: - Checklist panel

/// The dock of three readiness rows — each row is the entry point to its setup sheet.
struct ChecklistPanel: View {
    let checks: [StudioCheck]
    let onTap: (CheckKind) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(checks.enumerated()), id: \.element.id) { index, check in
                ChecklistRow(check: check) { onTap(check.kind) }
                if index < checks.count - 1 {
                    Rectangle()
                        .fill(Color.white.opacity(0.07))
                        .frame(height: 1)
                        .padding(.horizontal, 12)
                }
            }
        }
        .padding(6)
        .dockSurface(cornerRadius: 18)
    }
}

struct ChecklistRow: View {
    let check: StudioCheck
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                statusDisc
                VStack(alignment: .leading, spacing: 1) {
                    Text(check.title)
                        .font(CricFont.dmSans(13, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(check.sublabel)
                        .font(CricFont.dmSans(10.5, weight: .medium))
                        .foregroundStyle(check.warning ? CricTheme.warning : CricTheme.textDim)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if check.complete {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(CricTheme.textDim)
                } else {
                    chooseChip
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 52)
            .background(
                check.complete ? Color.clear : CricTheme.warning.opacity(0.08),
                in: RoundedRectangle(cornerRadius: 13)
            )
        }
        .buttonStyle(PressableScaleStyle())
    }

    private var statusDisc: some View {
        ZStack {
            Circle()
                .fill((check.complete ? CricTheme.accent : CricTheme.warning).opacity(0.15))
            Circle()
                .stroke(check.complete ? CricTheme.accent : CricTheme.warning, lineWidth: 1.5)
            if check.complete {
                Image(systemName: "checkmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(CricTheme.accent)
            } else {
                Text("!")
                    .font(CricFont.dmSans(13, weight: .bold))
                    .foregroundStyle(CricTheme.warning)
            }
        }
        .frame(width: 24, height: 24)
    }

    private var chooseChip: some View {
        Text("Choose")
            .font(CricFont.dmSans(11, weight: .bold))
            .foregroundStyle(CricTheme.accent)
            .padding(.horizontal, 12)
            .frame(height: 30)
            .background(CricTheme.accent.opacity(0.15), in: Capsule())
            .overlay(Capsule().stroke(CricTheme.accent.opacity(0.5), lineWidth: 1))
    }
}

// MARK: - Broadcast bug (live top-left status)

/// Three fused segments: ON AIR (gold gradient, ink pulsing dot) | timer | health.
/// PAUSED variant swaps the first segment to coral. Health shows the configured
/// quality only — the iOS engine publishes no live bitrate stats.
/// TODO: surface measured bitrate when the engine publishes stats.
struct BroadcastBug: View {
    let paused: Bool
    let elapsedText: String
    let qualityText: String
    let healthDot: Color

    var body: some View {
        HStack(spacing: 0) {
            statusSegment
            timerSegment
            healthSegment
        }
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(CricTheme.dockBorder, lineWidth: 1))
    }

    private var statusSegment: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(CricTheme.onPrimary)
                .frame(width: 9, height: 9)
                .pulseOpacity(active: !paused, min: 0.35)
            Text(paused ? "PAUSED" : "ON AIR")
                .font(CricFont.archivo(12.5))
                .tracking(1)
                .foregroundStyle(CricTheme.onPrimary)
        }
        .padding(.horizontal, 13)
        .frame(height: 40)
        .background(
            paused
                ? AnyShapeStyle(CricTheme.warning)
                : AnyShapeStyle(
                    LinearGradient(
                        colors: [CricTheme.primaryBright, CricTheme.primaryDeep],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
        )
    }

    private var timerSegment: some View {
        Text(elapsedText)
            .font(CricFont.archivo(16).monospacedDigit())
            .tracking(0.5)
            .foregroundStyle(.white)
            .padding(.horizontal, 13)
            .frame(height: 40)
            .background(CricTheme.dockBg)
    }

    private var healthSegment: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(healthDot)
                .frame(width: 8, height: 8)
                .pulseOpacity(active: true, min: 0.4)
            Text(qualityText)
                .font(CricFont.dmSans(11, weight: .bold))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 13)
        .frame(height: 40)
        .background(CricTheme.dockBg)
        .overlay(alignment: .leading) {
            Rectangle().fill(Color.white.opacity(0.1)).frame(width: 1)
        }
    }
}

// MARK: - Transport / glance pills

/// Horizontal control pill for the live transport strip (h48, r14).
struct TransportPill: View {
    let label: String
    let systemImage: String
    var state: StudioPillState = .idle
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            TransportPillLabel(label: label, systemImage: systemImage, state: state)
        }
        .buttonStyle(PressableScaleStyle())
    }
}

/// The pill visual on its own, so ShareLink can reuse it as a label.
struct TransportPillLabel: View {
    let label: String
    let systemImage: String
    var state: StudioPillState = .idle

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .semibold))
            Text(label)
                .font(CricFont.dmSans(10.5, weight: .bold))
                .tracking(0.5)
        }
        .foregroundStyle(state.tint)
        .padding(.horizontal, 14)
        .frame(height: 48)
        .background(pillBackground, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pillBorder, lineWidth: 1))
    }

    private var pillBackground: Color {
        switch state {
        case .idle: return Color.white.opacity(0.08)
        case .gold: return CricTheme.primary.opacity(0.14)
        case .error: return CricTheme.danger.opacity(0.16)
        }
    }

    private var pillBorder: Color {
        switch state {
        case .idle: return Color.white.opacity(0.2)
        case .gold: return CricTheme.primary
        case .error: return CricTheme.danger
        }
    }
}

/// 64pt-wide vertical glance pill (idle right rail): icon over a micro label.
struct GlancePill: View {
    let label: String
    let systemImage: String
    var state: StudioPillState = .idle
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(state == .idle ? .white : state.tint)
                Text(label)
                    .font(CricFont.dmSans(9, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(state == .idle ? CricTheme.textMuted : state.tint)
            }
            .frame(width: 64)
            .padding(.top, 10)
            .padding(.bottom, 8)
            .background(glanceBackground, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(glanceBorder, lineWidth: 1))
        }
        .buttonStyle(PressableScaleStyle())
    }

    private var glanceBackground: Color {
        switch state {
        case .idle: return CricTheme.glassPillBg
        case .gold: return CricTheme.primary.opacity(0.14)
        case .error: return CricTheme.danger.opacity(0.16)
        }
    }

    private var glanceBorder: Color {
        switch state {
        case .idle: return CricTheme.glassBorder
        case .gold: return CricTheme.primary
        case .error: return CricTheme.danger
        }
    }
}

// MARK: - Live transport strip

/// Single bottom dock while live: BOARD · AF LOCK · MIC | PAUSE | SHARE · STOP.
/// SHARE hides when there is no watch URL to share. `twoRow` splits the strip for
/// narrow (portrait) layouts — the full row only fits a landscape frame.
struct LiveTransportStrip: View {
    let focusLocked: Bool
    let micMuted: Bool
    let paused: Bool
    let watchUrl: String
    var twoRow: Bool = false
    let onBoard: () -> Void
    let onFocusLock: () -> Void
    let onMic: () -> Void
    let onPause: () -> Void
    let onStop: () -> Void

    var body: some View {
        Group {
            if twoRow {
                VStack(spacing: 8) {
                    HStack(spacing: 10) {
                        togglePills
                        Spacer(minLength: 4)
                        pauseButton
                    }
                    HStack(spacing: 10) {
                        sharePill
                        Spacer(minLength: 4)
                        stopButton
                    }
                }
            } else {
                HStack(spacing: 10) {
                    togglePills
                    Spacer(minLength: 4)
                    pauseButton
                    Spacer(minLength: 4)
                    sharePill
                    stopButton
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .dockSurface(cornerRadius: 20)
    }

    @ViewBuilder
    private var togglePills: some View {
        TransportPill(label: "BOARD", systemImage: "rectangle.on.rectangle", action: onBoard)
        TransportPill(
            label: focusLocked ? "AF LOCK" : "AF",
            systemImage: focusLocked ? "lock.fill" : "lock.open",
            state: focusLocked ? .gold : .idle,
            action: onFocusLock
        )
        TransportPill(
            label: micMuted ? "MUTED" : "MIC",
            systemImage: micMuted ? "mic.slash.fill" : "mic.fill",
            state: micMuted ? .error : .idle,
            action: onMic
        )
    }

    @ViewBuilder
    private var sharePill: some View {
        if !watchUrl.isEmpty {
            ShareLink(item: watchUrl) {
                TransportPillLabel(label: "SHARE", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(PressableScaleStyle())
        }
    }

    private var pauseButton: some View {
        Button(action: onPause) {
            Image(systemName: paused ? "play.fill" : "pause.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(paused ? CricTheme.warning : .white)
                .frame(width: 52, height: 48)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(paused ? CricTheme.warning : Color.white.opacity(0.25), lineWidth: 1)
                )
        }
        .buttonStyle(PressableScaleStyle())
        .accessibilityLabel(paused ? "Resume broadcast" : "Pause broadcast")
    }

    private var stopButton: some View {
        Button(action: onStop) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 3.5)
                    .fill(CricTheme.danger)
                    .frame(width: 13, height: 13)
                Text("STOP")
                    .font(CricFont.dmSans(11, weight: .bold))
                    .tracking(0.8)
                    .foregroundStyle(CricTheme.danger)
            }
            .padding(.horizontal, 16)
            .frame(height: 48)
            .background(CricTheme.danger.opacity(0.14), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(CricTheme.danger, lineWidth: 1.5))
        }
        .buttonStyle(PressableScaleStyle())
        .accessibilityLabel("Stop broadcast")
    }
}

// MARK: - Focus reticle + AE·AF lock tag

/// 62pt circular focus reticle: white ring free, gold ring + AE·AF LOCK tag when locked.
struct FocusReticle: View {
    let locked: Bool

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .stroke(locked ? CricTheme.primary : Color.white.opacity(0.92), lineWidth: 2)
                Circle()
                    .fill(locked ? CricTheme.primary : Color.white)
                    .frame(width: 4, height: 4)
            }
            .frame(width: 62, height: 62)
            if locked {
                AeAfLockTag()
            }
        }
    }
}

struct AeAfLockTag: View {
    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "lock.fill")
                .font(.system(size: 9, weight: .bold))
            Text("AE·AF LOCK")
                .font(CricFont.dmSans(9, weight: .bold))
                .tracking(0.5)
        }
        .foregroundStyle(CricTheme.primary)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Color(red: 0.035, green: 0.051, blue: 0.078).opacity(0.8), in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(CricTheme.primary.opacity(0.5), lineWidth: 1))
    }
}

// MARK: - Zoom pill + board chip

/// Current zoom readout (caller shows it only above ~1.1×).
struct ZoomPill: View {
    let zoom: Float

    var body: some View {
        Text(String(format: "%.1f×", zoom))
            .font(CricFont.dmSans(12, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .glassPillSurface(cornerRadius: 14)
    }
}

/// Collapsed scoreboard entry point — opens the overlay (board) sheet.
struct BoardChip: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: "rectangle.on.rectangle")
                    .font(.system(size: 13, weight: .semibold))
                Text("BOARD")
                    .font(CricFont.dmSans(10.5, weight: .bold))
                    .tracking(0.5)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .frame(height: 40)
            .glassPillSurface(cornerRadius: 14)
        }
        .buttonStyle(PressableScaleStyle())
    }
}
