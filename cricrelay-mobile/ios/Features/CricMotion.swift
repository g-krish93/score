import SwiftUI

/// Shared motion tokens — strong ease-out for enters, faster exits, 160ms press feedback.
enum CricMotion {
    static let pressDuration = 0.16
    static let enterDuration = 0.24
    static let exitDuration = 0.16
    static let sheetEnterDuration = 0.26
    static let sheetExitDuration = 0.18

    static let pressScale: CGFloat = 0.97
    static let enterScale: CGFloat = 0.95
    static let exitScale: CGFloat = 0.96

    static var press: Animation { .easeOut(duration: pressDuration) }
    static func enter(_ duration: Double = enterDuration) -> Animation { .easeOut(duration: duration) }
    static func exit(_ duration: Double = exitDuration) -> Animation { .easeOut(duration: duration) }

    static var reveal: AnyTransition {
        .scale(scale: enterScale).combined(with: .opacity)
    }

    static var hide: AnyTransition {
        .scale(scale: exitScale).combined(with: .opacity)
    }

    static var asymmetricReveal: AnyTransition {
        .asymmetric(insertion: reveal, removal: hide)
    }
}

// MARK: - Button styles

struct PressableScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? CricMotion.pressScale : 1)
            .animation(CricMotion.press, value: configuration.isPressed)
    }
}

struct PrimaryCtaStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(CricTheme.onPrimary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(CricTheme.ctaGradient, in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: CricTheme.primary.opacity(0.4), radius: 12, y: 4)
            .scaleEffect(configuration.isPressed ? CricMotion.pressScale : 1)
            .animation(CricMotion.press, value: configuration.isPressed)
    }
}

// MARK: - Decorative motion (respects Reduce Motion)

struct PulseOpacity: ViewModifier {
    let active: Bool
    var minOpacity: Double = 0.45

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var high = false

    func body(content: Content) -> some View {
        content
            .opacity(active ? (reduceMotion ? 1 : (high ? 1 : minOpacity)) : 1)
            .onAppear { syncPulse() }
            .onChange(of: reduceMotion) { _ in syncPulse() }
            .onChange(of: active) { _ in syncPulse() }
    }

    private func syncPulse() {
        guard active, !reduceMotion else {
            high = false
            return
        }
        withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
            high = true
        }
    }
}

struct BrandBreathe: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false

    func body(content: Content) -> some View {
        content
            .scaleEffect((!reduceMotion && expanded) ? 1.04 : 1)
            .onAppear { syncBreathe() }
            .onChange(of: reduceMotion) { _ in syncBreathe() }
    }

    private func syncBreathe() {
        guard !reduceMotion else {
            expanded = false
            return
        }
        withAnimation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true)) {
            expanded = true
        }
    }
}

extension View {
    func pulseOpacity(active: Bool, min minOpacity: Double = 0.45) -> some View {
        modifier(PulseOpacity(active: active, minOpacity: minOpacity))
    }

    func brandBreathe() -> some View {
        modifier(BrandBreathe())
    }

    func cricEnterAnimation<V: Equatable>(value: V, duration: Double = CricMotion.enterDuration) -> some View {
        animation(CricMotion.enter(duration), value: value)
    }

    func cricExitAnimation<V: Equatable>(value: V, duration: Double = CricMotion.exitDuration) -> some View {
        animation(CricMotion.exit(duration), value: value)
    }
}
