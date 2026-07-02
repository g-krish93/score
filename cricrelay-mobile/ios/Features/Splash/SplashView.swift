import SwiftUI
import UIKit

/// Cold-start cinematic splash — a port of the design handoff renderer
/// (docs/design_handoff_cricrelay_splash/"Splash Animation 1e v2.dc.html"), plus
/// three production upgrades on top of the spec (the "v3" pass):
///  - lights-down handover: after the lockup settles, the cream surface dims into
///    the app background so the splash→home transition is seamless, not a pop
///  - a "tap to skip" hint over the bottom letterbox bar during the shot
///  - a haptic tick at stump impact
///
/// Every frame is a pure function of the internal timeline t (0..5.8s), played at
/// 160/63 ≈ 2.54× (~2.3s wall clock): fade in on the leather ball, pull back to the
/// umpire's view, in-swinging delivery with a broadcast speed ramp, middle stump goes
/// back, the impact glow blooms into the 1e logo lockup, then the lights come down.
struct SplashView: View {
    let onFinished: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?
    @State private var skipped = false

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in
                SplashScene.draw(&context, size: size, t: currentT(timeline.date))
            }
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture {
            // Skip: jump to the settled lockup and let the lights-down play out.
            guard !skipped else { return }
            skipped = true
            start = Date().addingTimeInterval(-SplashScene.tDark / SplashScene.speed)
        }
        .onAppear {
            start = Date()
            // Reduced motion: no camera fly-through — hold the dark lockup, then hand over.
            if reduceMotion {
                skipped = true
                start = Date().addingTimeInterval(-SplashScene.dur / SplashScene.speed)
            }
        }
        .task(id: skipped) {
            if skipped {
                let remaining = (SplashScene.dur - SplashScene.tDark) / SplashScene.speed + 0.35
                try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            } else {
                // Haptic tick when the ball takes the stumps.
                try? await Task.sleep(nanoseconds: UInt64(SplashScene.hitWall * 1_000_000_000))
                if Task.isCancelled { return }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                let rest = SplashScene.dur / SplashScene.speed - SplashScene.hitWall + 0.35
                try? await Task.sleep(nanoseconds: UInt64(rest * 1_000_000_000))
            }
            if !Task.isCancelled { onFinished() }
        }
    }

    private func currentT(_ now: Date) -> Double {
        guard let start else { return 0 }
        return min(SplashScene.dur, now.timeIntervalSince(start) * SplashScene.speed)
    }
}

private struct Cam { var x = 0.0, y = 0.0, z = 0.0, tilt = 0.0 }
private struct P3 { var x = 0.0, y = 0.0, z = 0.0 }
private struct Proj { var x = 0.0, y = 0.0, d = 0.0 }

enum SplashScene {
    // Internal timeline keys from the handoff spec (the shot itself ends at 5.2;
    // 5.2..5.8 is the added lights-down handover into the app surface).
    static let dur = 5.8
    static let speed = 160.0 / 63.0 // ≈2.54× → ~2.3s wall clock
    static let tDark = 5.2 // lockup dims from cream to the app background
    private static let tRel = 1.45 // ball release — overlaps the pull-back, no dead beat
    private static let tHit = 2.8 // stump impact
    private static let tLogo = 3.5

    /// Wall-clock seconds from launch to stump impact (for the haptic tick).
    static var hitWall: Double { tHit / speed }

    /// 128px film-grain tile (~7% alpha per pixel, drawn at ~55% layer alpha).
    private static let noise: Image? = {
        let n = 128
        var data = [UInt8](repeating: 0, count: n * n * 4)
        for i in stride(from: 0, to: data.count, by: 4) {
            let v = UInt8.random(in: 0...255)
            let a: UInt8 = 18
            let pv = UInt8((UInt16(v) * UInt16(a)) / 255) // premultiplied
            data[i] = pv
            data[i + 1] = pv
            data[i + 2] = pv
            data[i + 3] = a
        }
        let cg: CGImage? = data.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(
                data: buf.baseAddress,
                width: n,
                height: n,
                bitsPerComponent: 8,
                bytesPerRow: n * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            return ctx.makeImage()
        }
        guard let cg else { return nil }
        return Image(decorative: cg, scale: 1)
    }()

    // ---- pure timeline helpers (port 1:1 from the spec) ----

    private static func ease(_ x: Double) -> Double {
        x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x)
    }

    /// Premium camera easing — long elegant settle (ease-in-out quartic).
    private static func easeIO(_ x: Double) -> Double {
        if x <= 0 { return 0 }
        if x >= 1 { return 1 }
        return x < 0.5 ? 8 * x * x * x * x : 1 - pow(-2 * x + 2, 4) / 2
    }

    private static func easeOutBack(_ x: Double) -> Double {
        if x <= 0 { return 0 }
        if x >= 1 { return 1 }
        let c = 1.20158
        return 1 + (c + 1) * pow(x - 1, 3) + c * pow(x - 1, 2)
    }

    /// Broadcast speed ramp: quick off the hand, slow-mo through the swing, snap at the stumps.
    private static func pMap(_ u: Double) -> Double {
        if u < 0.55 { return u * (0.75 / 0.55) }
        if u < 0.9 { return 0.75 + (u - 0.55) * (0.15 / 0.35) }
        return 0.9 + (u - 0.9)
    }

    /// World position across the delivery, p 0..1. In-swing, pitching at 66% of the length.
    private static func ballAt(_ p: Double) -> P3 {
        let pitchP = 0.66
        let z = 0.6 + p * 19.5
        if p < pitchP {
            let q = p / pitchP
            return P3(
                x: -0.38 * (1 - sin(q * .pi / 2)), // smooth inswing curve
                y: 0.05 + 1.95 * (1 - q * q),
                z: z
            )
        }
        let q = (p - pitchP) / (1 - pitchP)
        return P3(
            x: 0.0 - 0.04 * q, // slight jag after pitching
            y: 0.05 + 1.35 * q * (1.25 - q), // bounce up toward stump height
            z: z
        )
    }

    private static func camera(_ t: Double) -> Cam {
        // Extreme close-up on the ball -> pull back to the umpire's view.
        let k = easeIO((t - 0.6) / 1.1)
        func lerp(_ a: Double, _ b: Double) -> Double { a + (b - a) * k }
        var cam = Cam(x: lerp(0, -0.30), y: lerp(0.55, 1.9), z: lerp(0.6, -2.6), tilt: lerp(0, 0.10))
        // Tracking shot: the camera chases the ball, settling ~3/4 down the pitch at impact.
        if t > tRel {
            let kd = easeIO(min(1, (t - tRel) / (tHit - tRel)))
            let p = min(1, (t - tRel) / (tHit - tRel))
            // Blend from wherever the pull-back currently is — no hitch between the two moves.
            cam.z += (14.6 - cam.z) * kd
            cam.y += (1.35 - cam.y) * kd
            cam.tilt += (0.13 - cam.tilt) * kd
            cam.x += (ballAt(pMap(p)).x * 0.5 - cam.x) * kd
        }
        // Handheld drift — subtle organic float on top of everything.
        cam.x += sin(t * 1.7) * 0.014 + sin(t * 3.9 + 1.2) * 0.007
        cam.y += sin(t * 2.3 + 0.5) * 0.010
        // Impact shake, decaying over 0.45s.
        let imp = t - tHit
        if imp > 0, imp < 0.45 {
            let s = (0.45 - imp) * 0.10
            cam.x += sin(t * 90) * s
            cam.y += cos(t * 77) * s
        }
        return cam
    }

    private static func project(
        _ p: P3, _ cam: Cam, _ w: Double, _ h: Double, _ f: Double
    ) -> Proj? {
        let dx = p.x - cam.x
        let dy = p.y - cam.y
        let dz = p.z - cam.z
        let c = cos(cam.tilt)
        let s = sin(cam.tilt)
        let zv = -dy * s + dz * c
        let yv = dy * c + dz * s
        if zv < 0.12 { return nil }
        return Proj(x: w / 2 + f * dx / zv, y: h / 2 - f * yv / zv, d: zv)
    }

    // ---- small drawing helpers ----

    private static func rgba(_ r: Int, _ g: Int, _ b: Int, _ a: Double) -> Color {
        Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255, opacity: a)
    }

    /// Lerp between two rgba colors (for the lights-down handover).
    private static func mix(
        _ r1: Int, _ g1: Int, _ b1: Int, _ a1: Double,
        _ r2: Int, _ g2: Int, _ b2: Int, _ a2: Double,
        _ k: Double
    ) -> Color {
        let kk = min(1, max(0, k))
        func ch(_ x: Int, _ y: Int) -> Double { (Double(x) + (Double(y) - Double(x)) * kk) / 255 }
        return Color(red: ch(r1, r2), green: ch(g1, g2), blue: ch(b1, b2), opacity: a1 + (a2 - a1) * kk)
    }

    private static func gradient(_ stops: [(Double, Color)]) -> Gradient {
        Gradient(stops: stops.map { Gradient.Stop(color: $0.1, location: CGFloat($0.0)) })
    }

    private static func linePath(_ a: CGPoint, _ b: CGPoint) -> Path {
        var p = Path()
        p.move(to: a)
        p.addLine(to: b)
        return p
    }

    private static func ellipsePath(cx: Double, cy: Double, rx: Double, ry: Double) -> Path {
        Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
    }

    /// HTML-canvas-style arc (angles from +x axis, increasing downward on screen).
    private static func arcPath(cx: Double, cy: Double, r: Double, a0: Double, a1: Double) -> Path {
        var p = Path()
        let steps = 24
        for i in 0...steps {
            let a = a0 + (a1 - a0) * Double(i) / Double(steps)
            let pt = CGPoint(x: cx + cos(a) * r, y: cy + sin(a) * r)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        return p
    }

    // ---- the frame ----

    static func draw(_ ctx: inout GraphicsContext, size: CGSize, t: Double) {
        let w = Double(size.width)
        let h = Double(size.height)
        let f = h * 0.95
        let cam = camera(t)
        func prj(_ x: Double, _ y: Double, _ z: Double) -> Proj? {
            project(P3(x: x, y: y, z: z), cam, w, h, f)
        }

        // ---- sky + ground: graded night atmosphere (teal & tungsten) ----
        let horP = prj(0, 0, 120)
        let horY = horP != nil ? min(h * 0.62, max(h * 0.10, horP!.y)) : h * 0.30
        ctx.fill(
            Path(CGRect(x: 0, y: 0, width: w, height: horY + 1)),
            with: .linearGradient(
                gradient([
                    (0, rgba(0x08, 0x11, 0x1A, 1)), // deep teal night
                    (0.7, rgba(0x0E, 0x14, 0x1A, 1)),
                    (1, rgba(0x19, 0x1A, 0x11, 1)), // warm sodium horizon
                ]),
                startPoint: CGPoint(x: 0, y: 0),
                endPoint: CGPoint(x: 0, y: horY)
            )
        )
        ctx.fill(
            Path(CGRect(x: 0, y: horY, width: w, height: h - horY)),
            with: .linearGradient(
                gradient([
                    (0, rgba(0x19, 0x1A, 0x11, 1)),
                    (0.5, rgba(0x12, 0x15, 0x0D, 1)),
                    (1, rgba(0x08, 0x0B, 0x07, 1)),
                ]),
                startPoint: CGPoint(x: 0, y: horY),
                endPoint: CGPoint(x: 0, y: h)
            )
        )
        // Warm light pools spilling onto the outfield.
        for pool in [P3(x: -5.5, y: 0, z: 9), P3(x: 5.5, y: 0, z: 9)] {
            guard let lp = project(pool, cam, w, h, f) else { continue }
            let pr = max(40, f * 5.5 / lp.d)
            ctx.fill(
                ellipsePath(cx: lp.x, cy: lp.y, rx: pr, ry: pr * 0.4),
                with: .radialGradient(
                    gradient([(0, rgba(255, 235, 195, 0.05)), (1, rgba(255, 235, 195, 0))]),
                    center: CGPoint(x: lp.x, y: lp.y),
                    startRadius: 0,
                    endRadius: pr
                )
            )
        }

        // ---- night-stadium floodlights ----
        for fp in [P3(x: -9, y: 6.5, z: 26), P3(x: 9, y: 6.5, z: 26), P3(x: -11, y: 5.5, z: 4), P3(x: 11, y: 5.5, z: 4)] {
            guard let lp = project(fp, cam, w, h, f) else { continue }
            let lr = max(14, f * 2.3 / lp.d)
            ctx.fill(
                ellipsePath(cx: lp.x, cy: lp.y, rx: lr, ry: lr),
                with: .radialGradient(
                    gradient([
                        (0, rgba(255, 241, 214, 0.34)), // warm tungsten core
                        (0.3, rgba(235, 215, 180, 0.05)),
                        (1, rgba(235, 215, 180, 0)),
                    ]),
                    center: CGPoint(x: lp.x, y: lp.y),
                    startRadius: 0,
                    endRadius: lr
                )
            )
            let bw = max(4, f * 0.5 / lp.d)
            ctx.fill(
                Path(CGRect(x: lp.x - bw / 2, y: lp.y - bw / 8, width: bw, height: bw / 4)),
                with: .color(rgba(238, 242, 250, 0.85))
            )
        }

        // ---- pitch strip ----
        let corners = [prj(-1.55, 0, -1), prj(1.55, 0, -1), prj(1.55, 0, 21.2), prj(-1.55, 0, 21.2)]
        if corners.allSatisfy({ $0 != nil }) {
            let c = corners.map { $0! }
            var strip = Path()
            strip.move(to: CGPoint(x: c[0].x, y: c[0].y))
            for i in 1..<4 { strip.addLine(to: CGPoint(x: c[i].x, y: c[i].y)) }
            strip.closeSubpath()
            ctx.fill(
                strip,
                with: .linearGradient(
                    gradient([
                        (0, rgba(0x2A, 0x24, 0x15, 1)), // far end sinks into haze
                        (0.55, rgba(0x46, 0x3C, 0x24, 1)), // warm amber under the lights
                        (1, rgba(0x3C, 0x34, 0x20, 1)),
                    ]),
                    startPoint: CGPoint(x: 0, y: c[2].y),
                    endPoint: CGPoint(x: 0, y: c[0].y)
                )
            )
            ctx.stroke(strip, with: .color(rgba(255, 255, 255, 0.08)), lineWidth: 1)
            // Mowing stripes.
            var z0 = 0.0
            while z0 < 20.4 {
                defer { z0 += 2.55 }
                if Int((z0 / 2.55).rounded()) % 2 != 0 { continue }
                let q = [
                    prj(-1.55, 0, z0), prj(1.55, 0, z0),
                    prj(1.55, 0, min(21.2, z0 + 2.55)), prj(-1.55, 0, min(21.2, z0 + 2.55)),
                ]
                guard q.allSatisfy({ $0 != nil }) else { continue }
                let qq = q.map { $0! }
                var stripe = Path()
                stripe.move(to: CGPoint(x: qq[0].x, y: qq[0].y))
                for i in 1..<4 { stripe.addLine(to: CGPoint(x: qq[i].x, y: qq[i].y)) }
                stripe.closeSubpath()
                ctx.fill(stripe, with: .color(rgba(255, 255, 255, 0.028)))
            }
        }
        // Crease lines (both ends).
        func crease(_ ax: Double, _ az: Double, _ bx: Double, _ bz: Double, _ alpha: Double, _ lw: Double) {
            guard let p1 = prj(ax, 0, az), let p2 = prj(bx, 0, bz) else { return }
            ctx.stroke(
                linePath(CGPoint(x: p1.x, y: p1.y), CGPoint(x: p2.x, y: p2.y)),
                with: .color(rgba(238, 238, 228, alpha * 0.85)),
                lineWidth: lw
            )
        }
        crease(-1.3, 18.9, 1.3, 18.9, 0.55, 2) // popping crease (batting end)
        crease(-1.3, 20.1, 1.3, 20.1, 0.35, 1.5) // bowling crease far
        crease(-1.3, 1.2, 1.3, 1.2, 0.55, 2) // near crease
        // Atmospheric haze hanging over the far end.
        ctx.fill(
            Path(CGRect(x: 0, y: horY - h * 0.05, width: w, height: h * 0.17)),
            with: .linearGradient(
                gradient([
                    (0, rgba(185, 175, 150, 0)),
                    (0.45, rgba(185, 175, 150, 0.08)),
                    (1, rgba(185, 175, 150, 0)),
                ]),
                startPoint: CGPoint(x: 0, y: horY - h * 0.05),
                endPoint: CGPoint(x: 0, y: horY + h * 0.12)
            )
        )

        // ---- stumps at the far end ----
        let hitP = min(1, max(0, (t - tHit) * 2.6))
        if let sb = prj(0, 0, 20.1) {
            // Soft contact shadow at the base of the set.
            ctx.fill(
                ellipsePath(cx: sb.x, cy: sb.y + 1, rx: max(6, f * 0.26 / sb.d), ry: max(2, f * 0.05 / sb.d)),
                with: .color(rgba(0, 0, 0, 0.30))
            )
        }
        for i in -1...1 {
            let sx = Double(i) * 0.14
            var topY = 0.72
            var topZ = 20.1
            if i == 0, hitP > 0 { // middle stump knocked back
                let ang = hitP * 1.1
                topY = 0.72 * cos(ang)
                topZ = 20.1 + 0.72 * sin(ang)
            }
            guard let b = prj(sx, 0, 20.1), let tp = prj(sx, topY, topZ) else { continue }
            let lw = max(2, f * 0.045 / b.d)
            let shaft = linePath(CGPoint(x: b.x, y: b.y), CGPoint(x: tp.x, y: tp.y))
            ctx.stroke(shaft, with: .color(rgba(0xE0, 0xD3, 0xAE, 1)), style: StrokeStyle(lineWidth: lw, lineCap: .round)) // pale willow
            // Shaded edge for roundness.
            ctx.stroke(
                linePath(CGPoint(x: b.x + lw * 0.28, y: b.y), CGPoint(x: tp.x + lw * 0.28, y: tp.y)),
                with: .color(rgba(80, 66, 42, 0.55)),
                style: StrokeStyle(lineWidth: lw * 0.32, lineCap: .round)
            )
            // Specular sliver from the floodlights.
            ctx.stroke(
                linePath(CGPoint(x: b.x - lw * 0.24, y: b.y), CGPoint(x: tp.x - lw * 0.24, y: tp.y)),
                with: .color(rgba(255, 252, 240, 0.5)),
                style: StrokeStyle(lineWidth: lw * 0.18, lineCap: .round)
            )
        }
        // Bails.
        if hitP <= 0 {
            if let b1 = prj(-0.07, 0.745, 20.1), let b2 = prj(0.07, 0.745, 20.1) {
                let lw = max(1.5, f * 0.03 / b1.d)
                ctx.stroke(linePath(CGPoint(x: b1.x - 4, y: b1.y), CGPoint(x: b1.x + 4, y: b1.y)), with: .color(rgba(0xE0, 0xD3, 0xAE, 1)), lineWidth: lw)
                ctx.stroke(linePath(CGPoint(x: b2.x - 4, y: b2.y), CGPoint(x: b2.x + 4, y: b2.y)), with: .color(rgba(0xE0, 0xD3, 0xAE, 1)), lineWidth: lw)
            }
        } else {
            // Flying bails — ballistic, spinning ~9 rad/s.
            let bt = t - tHit
            for b in [(-0.07, -0.9, 2.4, 1.6), (0.07, 0.7, 3.1, 2.0)] {
                let bx = b.0 + b.1 * bt * 0.4
                var by = 0.745 + b.2 * bt - 4.9 * bt * bt
                let bz = 20.1 + b.3 * bt * 0.5
                if by < 0.02 { by = 0.02 }
                guard let pp = prj(bx, by, bz) else { continue }
                var bailCtx = ctx
                bailCtx.translateBy(x: CGFloat(pp.x), y: CGFloat(pp.y))
                bailCtx.rotate(by: .radians(bt * 9 * (b.0 < 0 ? -1 : 1)))
                bailCtx.stroke(
                    linePath(CGPoint(x: -5, y: 0), CGPoint(x: 5, y: 0)),
                    with: .color(rgba(0xE0, 0xD3, 0xAE, 1)),
                    lineWidth: 2
                )
            }
        }

        // ---- ball ----
        var ball: P3?
        let spin = t < 1.3 ? 3.6 * t : 3.6 * 1.3 + 16 * (t - 1.3)
        if t < tRel {
            // One continuous ball: the close-up position glides into the release point
            // (left of the stumps, right-arm over) as the camera pulls back.
            let k2 = ease((t - 0.5) / 0.95)
            ball = P3(
                x: -0.38 * k2,
                y: 0.55 + sin(t * 2.2) * 0.012 * (1 - k2) + 1.45 * k2,
                z: 1.0 - 0.4 * k2
            )
        } else if t < tHit {
            let prog = pMap((t - tRel) / (tHit - tRel))
            ball = ballAt(prog)
            // Faint motion smear, not a comet.
            for k in 1...7 {
                let tp2 = prog - Double(k) * 0.035
                if tp2 <= 0 { break }
                let wp = ballAt(tp2)
                guard let sp = project(wp, cam, w, h, f) else { continue }
                let r = max(1, f * 0.075 / sp.d) * (1 - Double(k) * 0.11)
                ctx.fill(
                    ellipsePath(cx: sp.x, cy: sp.y, rx: r, ry: r),
                    with: .color(rgba(226, 110, 80, 0.10 * (1 - Double(k) / 8)))
                )
            }
            // Dust kicked off the deck where it pitches (66% of the length).
            let pitchT = tRel + 0.66 * (tHit - tRel)
            let bf = t - pitchT
            if bf > 0, bf < 0.35 {
                let wp = ballAt(0.66)
                if let sp = prj(wp.x, 0, wp.z) {
                    let da = 1 - bf / 0.35
                    for di in 0..<3 {
                        let dr = (8 + Double(di) * 9) * (bf / 0.35 + 0.3) * max(0.5, f * 0.02 / sp.d) * 8
                        ctx.fill(
                            ellipsePath(cx: sp.x - Double(di) * dr * 0.15, cy: sp.y - dr * 0.12, rx: dr, ry: dr * 0.38),
                            with: .color(rgba(190, 172, 135, 0.10 * da / Double(di + 1)))
                        )
                    }
                }
            }
        }
        if let ball {
            // Grounded contact shadow under the ball.
            let gs = prj(ball.x, 0, ball.z)
            let sp = project(ball, cam, w, h, f)
            if let gs, let sp {
                let r = max(3, f * 0.075 / sp.d)
                let shA = max(0, 0.32 - ball.y * 0.12)
                ctx.fill(
                    ellipsePath(cx: gs.x, cy: gs.y, rx: r * (1.15 - ball.y * 0.25), ry: r * 0.32),
                    with: .color(rgba(0, 0, 0, shA))
                )
            }
            if let sp {
                let r = max(3, f * 0.075 / sp.d)
                // Leather sphere lit top-left by the floodlights. The HTML uses an
                // off-centre two-point radial; a radial centred on the highlight
                // reads identically at this size.
                ctx.fill(
                    ellipsePath(cx: sp.x, cy: sp.y, rx: r, ry: r),
                    with: .radialGradient(
                        gradient([
                            (0, rgba(0xF0, 0x85, 0x5A, 1)), // tungsten highlight
                            (0.42, rgba(0xB9, 0x3A, 0x1E, 1)), // rich leather
                            (1, rgba(0x45, 0x0E, 0x06, 1)), // deep core shadow
                        ]),
                        center: CGPoint(x: sp.x - r * 0.38, y: sp.y - r * 0.42),
                        startRadius: 0,
                        endRadius: r * 1.65
                    )
                )
                // Stitched seam band, rotating with the ball.
                var seamCtx = ctx
                seamCtx.clip(to: ellipsePath(cx: sp.x, cy: sp.y, rx: r, ry: r))
                seamCtx.translateBy(x: CGFloat(sp.x), y: CGFloat(sp.y))
                seamCtx.rotate(by: .radians((spin * 0.6).truncatingRemainder(dividingBy: 2 * .pi)))
                seamCtx.stroke(
                    ellipsePath(cx: 0, cy: 0, rx: r * 0.30, ry: r * 0.97),
                    with: .color(rgba(242, 228, 200, 0.9)),
                    lineWidth: max(1, r * 0.055)
                )
                if r > 10 {
                    // Cross-stitches across the band.
                    var a = -0.75
                    while a <= 0.75 {
                        let yy = a * r * 0.92
                        seamCtx.stroke(
                            linePath(CGPoint(x: -r * 0.10, y: yy), CGPoint(x: r * 0.10, y: yy)),
                            with: .color(rgba(242, 228, 200, 0.75)),
                            lineWidth: max(1, r * 0.035)
                        )
                        a += 0.25
                    }
                }
                // Rim light.
                ctx.stroke(
                    arcPath(cx: sp.x, cy: sp.y, r: r * 0.93, a0: .pi * 0.95, a1: .pi * 1.55),
                    with: .color(rgba(255, 240, 220, 0.35)),
                    lineWidth: max(1, r * 0.06)
                )
            }
        }

        // ---- impact flash: brief exposure kick, not a cartoon flash ----
        let fl = t - tHit
        if fl > 0, fl < 0.09 {
            ctx.fill(
                Path(CGRect(x: 0, y: 0, width: w, height: h)),
                with: .color(rgba(255, 255, 255, 0.16 * (1 - fl / 0.09)))
            )
        }
        if fl > 0, fl < 0.5, let sp = prj(0, 0.45, 20.0) {
            let a = 1 - fl / 0.5
            ctx.fill(
                ellipsePath(cx: sp.x, cy: sp.y, rx: 64, ry: 64),
                with: .radialGradient(
                    gradient([(0, rgba(255, 230, 190, 0.30 * a)), (1, rgba(255, 230, 190, 0))]),
                    center: CGPoint(x: sp.x, y: sp.y),
                    startRadius: 0,
                    endRadius: 64
                )
            )
        }
        // Light-bloom match cut: the warm impact glow swells until it becomes the lockup screen.
        let laB = ease((t - tLogo) / 0.8)
        if fl > 0.10, laB < 1, let bp = prj(0, 0.45, 20.0) {
            let bloomK = easeIO((fl - 0.10) / (tLogo - tHit + 0.15))
            let bigR = 30 + bloomK * hypot(w, h) * 1.05
            ctx.fill(
                ellipsePath(cx: bp.x, cy: bp.y, rx: bigR, ry: bigR),
                with: .radialGradient(
                    gradient([
                        (0, rgba(247, 245, 238, min(1, bloomK * 1.6))),
                        (0.75, rgba(247, 245, 238, min(1, bloomK * 1.25) * 0.85)),
                        (1, rgba(247, 245, 238, 0)),
                    ]),
                    center: CGPoint(x: bp.x, y: bp.y),
                    startRadius: 0,
                    endRadius: bigR
                )
            )
        }

        // ---- cinematic grade: vignette + letterbox + grain + fade-in ----
        let laPre = ease((t - tLogo) / 0.8)
        ctx.fill(
            Path(CGRect(x: 0, y: 0, width: w, height: h)),
            with: .radialGradient(
                gradient([(0, rgba(4, 9, 14, 0)), (1, rgba(4, 9, 14, 0.55))]), // teal-leaning corners
                center: CGPoint(x: w / 2, y: h / 2),
                startRadius: h * 0.25,
                endRadius: h * 0.72
            )
        )
        let barH = h * 0.08 * (1 - laPre) // letterbox retracts at the lockup
        if barH > 0.5 {
            ctx.fill(Path(CGRect(x: 0, y: 0, width: w, height: barH)), with: .color(.black))
            ctx.fill(Path(CGRect(x: 0, y: h - barH, width: w, height: barH)), with: .color(.black))
        }
        // Animated film grain.
        if laPre < 1, let noise {
            var grainCtx = ctx
            grainCtx.opacity = 0.55 * (1 - laPre)
            let ox = (t * 6.1).truncatingRemainder(dividingBy: 1) * 128
            let oy = (t * 4.7).truncatingRemainder(dividingBy: 1) * 128
            var gx = -ox
            while gx < w {
                var gy = -oy
                while gy < h {
                    grainCtx.draw(noise, in: CGRect(x: gx, y: gy, width: 128, height: 128))
                    gy += 128
                }
                gx += 128
            }
        }
        if t < 0.4 {
            ctx.fill(
                Path(CGRect(x: 0, y: 0, width: w, height: h)),
                with: .color(rgba(0, 0, 0, 1 - t / 0.4))
            )
        }
        // "tap to skip" hint, sitting just above the bottom letterbox bar.
        let hintA = ease((t - 0.8) / 0.4) * (1 - laPre)
        if hintA > 0.01 {
            let hint = ctx.resolve(
                Text("tap to skip")
                    .font(.custom("DMSans-Medium", size: 13))
                    .foregroundColor(rgba(238, 238, 228, 0.4 * hintA))
            )
            ctx.draw(hint, at: CGPoint(x: w / 2, y: h - h * 0.08 - 20), anchor: .center)
        }

        // ---- logo lockup (the landing / transition frame) ----
        let la = ease((t - tLogo) / 0.8)
        if la > 0 {
            // Lights-down: past tDark the cream surface eases into the app
            // background (and the type goes cream) so the handover to home is
            // invisible rather than a bright-to-dark pop.
            let dk = ease((t - tDark) / 0.6)
            var lock = ctx
            lock.opacity = la
            lock.fill(
                Path(CGRect(x: 0, y: 0, width: w, height: h)),
                with: .linearGradient(
                    gradient([
                        (0, mix(0xF8, 0xF6, 0xEF, 1, 0x0A, 0x0E, 0x15, 1, dk)),
                        (1, mix(0xED, 0xEA, 0xE0, 1, 0x0A, 0x0E, 0x15, 1, dk)),
                    ]),
                    startPoint: CGPoint(x: 0, y: 0),
                    endPoint: CGPoint(x: 0, y: h)
                )
            )
            // Whisper of green at the frame edges (fades with the lights).
            lock.fill(
                Path(CGRect(x: 0, y: 0, width: w, height: h)),
                with: .radialGradient(
                    gradient([(0, rgba(46, 94, 50, 0)), (1, rgba(46, 94, 50, 0.08 * (1 - dk)))]),
                    center: CGPoint(x: w / 2, y: h * 0.46),
                    startRadius: h * 0.2,
                    endRadius: h * 0.8
                )
            )
            // Zoom in from 72% with a refined overshoot settle.
            let zs = 0.72 + 0.28 * easeOutBack(la)
            lock.translateBy(x: CGFloat(w / 2), y: CGFloat(h / 2))
            lock.scaleBy(x: CGFloat(zs), y: CGFloat(zs))
            lock.translateBy(x: CGFloat(-w / 2), y: CGFloat(-h / 2))
            let cy = h * 0.44
            // 1e pitch-mark: green rounded square, cream pitch, crease ticks.
            let s = 84.0
            let lx = w / 2 - s / 2
            let ly = cy - 120
            let green = rgba(0x2E, 0x5E, 0x32, 1)
            lock.fill(
                Path(roundedRect: CGRect(x: lx, y: ly, width: s, height: s), cornerRadius: 19),
                with: .color(green)
            )
            lock.fill(
                Path(roundedRect: CGRect(x: w / 2 - 13, y: ly + 15, width: 26, height: 54), cornerRadius: 7),
                with: .color(rgba(0xD8, 0xC9, 0xA3, 1))
            )
            lock.fill(Path(CGRect(x: w / 2 - 8, y: ly + 22, width: 16, height: 2.6)), with: .color(green))
            lock.fill(Path(CGRect(x: w / 2 - 8, y: ly + 59.4, width: 16, height: 2.6)), with: .color(green))
            // Wordmark + tagline — green-on-cream by day, cream-on-ink after the
            // lights come down (per the handoff's dark lockup tokens). fillText
            // draws from the alphabetic baseline, so compensate with the descent.
            let wordmark = lock.resolve(
                Text("cricrelay")
                    .font(.custom("Archivo-ExtraBold", size: 42))
                    .foregroundColor(mix(0x2E, 0x5E, 0x32, 1, 0xE0, 0xD3, 0xAE, 1, dk))
            )
            let wmDescent = Double(UIFont(name: "Archivo-ExtraBold", size: 42)?.descender ?? -10).magnitude
            lock.draw(wordmark, at: CGPoint(x: w / 2, y: cy + 32 + wmDescent), anchor: .bottom)
            let tagline = lock.resolve(
                Text("your club's home ground")
                    .font(.custom("DMSans-Medium", size: 16))
                    .foregroundColor(mix(47, 42, 36, 0.6, 255, 255, 255, 0.55, dk))
            )
            let tgDescent = Double(UIFont(name: "DMSans-Medium", size: 16)?.descender ?? -4).magnitude
            lock.draw(tagline, at: CGPoint(x: w / 2, y: cy + 64 + tgDescent), anchor: .bottom)
        }
    }
}
