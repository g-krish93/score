import SwiftUI

struct UmpireScorerView: View {
    @StateObject private var vm = UmpireScorerViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showNewOverAlert = false

    // Palette matches the cricket-green HTML mockup.
    private let runColor    = Color(red: 0.083, green: 0.282, blue: 0.753)
    private let extraColor  = Color(red: 0.749, green: 0.212, blue: 0.047)
    private let byeColor    = Color(red: 0.902, green: 0.318, blue: 0.0)
    private let wicketColor = Color(red: 0.718, green: 0.110, blue: 0.110)
    private let activeWide  = Color(red: 1.0, green: 0.427, blue: 0.0)
    private let activeBye   = Color(red: 1.0, green: 0.561, blue: 0.0)
    private let activeWicket = Color(red: 1.0, green: 0.090, blue: 0.267)

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                VStack(spacing: 0) {
                    scoreBanner
                    overChipsRow
                    modeBar
                    scoringArea(height: geo.size.height - 130 - 44 - 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(Color(red: 0.039, green: 0.055, blue: 0.082))
            }
            .navigationTitle("Umpire Scorer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(Color(white: 0.6))
                }
            }
        }
        .preferredColorScheme(.dark)
        .alert("End over?", isPresented: $showNewOverAlert) {
            Button("New Over") { vm.onEndOver() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Over \(vm.completedOvers + 1) complete. Start over \(vm.completedOvers + 2)?")
        }
        .onChange(of: vm.legalBallCount) { newVal in
            if newVal >= 6 { showNewOverAlert = true }
        }
    }

    // MARK: - Score banner

    private var scoreBanner: some View {
        VStack(spacing: 4) {
            Text("\(vm.totalRuns)/\(vm.totalWickets)")
                .font(.system(size: 52, weight: .bold))
                .foregroundStyle(.white)
                .monospacedDigit()
            Text("Overs: \(vm.completedOvers).\(vm.legalBallCount)")
                .font(.system(size: 16))
                .foregroundStyle(Color(red: 0.647, green: 0.839, blue: 0.655))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(red: 0.059, green: 0.169, blue: 0.067))
    }

    // MARK: - Over chips

    private var overChipsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(vm.currentOverBalls) { ball in
                    overChip(ball)
                }
                ForEach(0..<max(0, 6 - vm.legalBallCount), id: \.self) { _ in
                    emptyChip
                }
            }
            .padding(.horizontal, 8)
        }
        .frame(height: 44)
        .background(Color(red: 0.106, green: 0.392, blue: 0.125))
    }

    private func overChip(_ ball: UmpireBallEvent) -> some View {
        let color: Color = {
            if ball.isWicket { return wicketColor }
            if ball.label.hasPrefix("Wd") { return extraColor }
            if ball.label.hasPrefix("Nb") { return byeColor }
            if ball.label.hasPrefix("B") || ball.label.hasPrefix("Lb") { return Color(red: 0.965, green: 0.498, blue: 0.090) }
            if ball.totalRuns >= 4 { return Color(red: 0.051, green: 0.278, blue: 0.631) }
            if ball.totalRuns > 0 { return runColor }
            return Color(red: 0.200, green: 0.412, blue: 0.118)
        }()
        return Text(ball.label)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(color, in: RoundedRectangle(cornerRadius: 8))
    }

    private var emptyChip: some View {
        Text("○")
            .font(.system(size: 13))
            .foregroundStyle(Color(red: 0.506, green: 0.784, blue: 0.518))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color(red: 0.506, green: 0.784, blue: 0.518), lineWidth: 1)
            )
    }

    // MARK: - Mode bar

    private var modeBar: some View {
        Text(vm.modeIndicatorText)
            .font(.system(size: 13))
            .foregroundStyle(Color(red: 0.506, green: 0.784, blue: 0.518))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 5)
            .background(Color(red: 0.106, green: 0.392, blue: 0.125))
    }

    // MARK: - Scoring area

    private func scoringArea(height: CGFloat) -> some View {
        let rowH = height / 6.1
        return VStack(spacing: 4) {
            // Run rows
            HStack(spacing: 4) {
                ForEach([0, 1, 2], id: \.self) { r in
                    scoringButton("\(r)", color: runColor) { vm.onRunsPressed(r) }
                }
            }
            .frame(height: rowH)

            HStack(spacing: 4) {
                ForEach([3, 4, 6], id: \.self) { r in
                    scoringButton("\(r)", color: runColor) { vm.onRunsPressed(r) }
                }
            }
            .frame(height: rowH)

            // Extras row 1
            HStack(spacing: 4) {
                scoringButton("Wide",
                              color: vm.deliveryMode == .wide ? activeWide : extraColor,
                              fontSize: 16) { vm.onWidePressed() }
                scoringButton("No Ball",
                              color: vm.deliveryMode == .noBall ? activeWide : extraColor,
                              fontSize: 16) { vm.onNoBallPressed() }
            }
            .frame(height: rowH * 0.8)

            // Extras row 2
            HStack(spacing: 4) {
                scoringButton("Bye",
                              color: vm.deliveryMode == .bye ? activeBye : byeColor,
                              fontSize: 16) { vm.onByePressed() }
                scoringButton("Leg Bye",
                              color: vm.deliveryMode == .legBye ? activeBye : byeColor,
                              fontSize: 16) { vm.onLegByePressed() }
            }
            .frame(height: rowH * 0.8)

            // Wicket
            scoringButton(vm.pendingWicket ? "◆ WICKET" : "WICKET",
                          color: vm.pendingWicket ? activeWicket : wicketColor,
                          fontSize: 18, letterSpacing: 2) { vm.onWicketToggle() }
                .frame(height: rowH * 0.8)

            // Controls
            HStack(spacing: 4) {
                scoringButton("Undo",     color: Color(red: 0.329, green: 0.431, blue: 0.478), fontSize: 14) { vm.onUndo() }
                scoringButton("New Over", color: Color(red: 0.216, green: 0.278, blue: 0.310), fontSize: 14) { showNewOverAlert = true }
                    .frame(maxWidth: .infinity).layoutPriority(0.3)
                scoringButton("Reset",    color: Color(red: 0.216, green: 0.278, blue: 0.310), fontSize: 14) { vm.onReset() }
            }
            .frame(height: rowH * 0.7)
        }
        .padding(4)
        .frame(maxWidth: .infinity)
    }

    private func scoringButton(
        _ text: String,
        color: Color,
        fontSize: CGFloat = 22,
        letterSpacing: CGFloat = 0,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: fontSize, weight: .bold))
                .kerning(letterSpacing)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(color, in: RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(PressableScaleStyle())
    }
}
