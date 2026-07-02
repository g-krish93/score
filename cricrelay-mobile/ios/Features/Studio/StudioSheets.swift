import SwiftUI

// MARK: - Destination sheet

struct DestinationSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 14) {
                        destinationOption(
                            id: "youtube",
                            icon: "play.rectangle.fill",
                            iconColor: Color(red: 1, green: 0.1, blue: 0.1),
                            title: "YouTube",
                            subtitle: "Stream via YouTube Studio RTMP"
                        )
                        destinationOption(
                            id: "twitch",
                            icon: "gamecontroller.fill",
                            iconColor: Color(red: 0.576, green: 0.286, blue: 1.0),
                            title: "Twitch",
                            subtitle: "Stream via Twitch ingest"
                        )
                        destinationOption(
                            id: "custom",
                            icon: "network",
                            iconColor: CricTheme.accent,
                            title: "Custom RTMP",
                            subtitle: "Any RTMP-compatible service"
                        )

                        if viewModel.destination == "custom" {
                            customRtmpFields
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .padding(24)
                    .cricEnterAnimation(value: viewModel.destination, duration: CricMotion.sheetEnterDuration)
                }
            }
            .navigationTitle("Destination")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        viewModel.persistCustomRtmp()
                        dismiss()
                    }
                    .foregroundStyle(CricTheme.accent)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func destinationOption(id: String, icon: String, iconColor: Color, title: String, subtitle: String) -> some View {
        Button {
            viewModel.destination = id
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(iconColor)
                    .frame(width: 42, height: 42)
                    .background(iconColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(CricTheme.textMuted)
                }
                Spacer()
                Image(systemName: viewModel.destination == id ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(viewModel.destination == id ? CricTheme.primary : CricTheme.textDim)
                    .font(.system(size: 20))
            }
            .padding(14)
            .background(
                viewModel.destination == id ? CricTheme.primary.opacity(0.08) : CricTheme.surface,
                in: RoundedRectangle(cornerRadius: 14)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(
                        viewModel.destination == id ? CricTheme.primary.opacity(0.35) : Color.white.opacity(0.08),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(PressableScaleStyle())
    }

    private var customRtmpFields: some View {
        VStack(spacing: 12) {
            TextField("RTMP server URL", text: $viewModel.customRtmpUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .modifier(StudioFieldStyle())
            SecureField("Stream key", text: $viewModel.customStreamKey)
                .modifier(StudioFieldStyle())
            TextField("Watch URL (optional)", text: $viewModel.customWatchUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .modifier(StudioFieldStyle())
        }
        .padding(.top, 4)
    }
}

// MARK: - Overlay sheet

struct OverlaySheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var draft = OverlayLayoutPrefs()
    @State private var savedOnDismiss = false

    private let boardColors: [(name: String, bg: String, text: String, color: Color)] = [
        ("Default", "", "", Color(red: 0.09, green: 0.16, blue: 0.30)),
        ("Dark", "#0E1A24", "#FFFFFF", Color(red: 0.05, green: 0.10, blue: 0.14)),
        ("Black", "#000000", "#FFFFFF", Color.black),
        ("Light", "#FFFFFF", "#16294D", Color.white),
        ("Teal", "#0B3D3A", "#7CF6D6", Color(red: 0.04, green: 0.24, blue: 0.23)),
    ]

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 20) {
                        arrangeOnScreenButton
                        boardColorSelector
                        Divider().overlay(Color.white.opacity(0.1))
                        overlaySliders
                    }
                    .padding(24)
                }
            }
            .navigationTitle("Scoreboard overlay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(CricTheme.textMuted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        savedOnDismiss = true
                        Task { await viewModel.saveOverlay(draft) }
                        dismiss()
                    }
                    .foregroundStyle(CricTheme.accent)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            savedOnDismiss = false
            draft = viewModel.overlayPrefs
            draft.theme = "barlow"
            Task { await viewModel.loadSponsors() }
            if draft.activeSponsorIds.isEmpty, draft.activeSponsorId == nil,
               let first = viewModel.sponsors.first(where: { $0.isActive }) {
                draft.activeSponsorId = first.id
                draft.activeSponsorIds = [first.id]
            }
        }
        .onDisappear {
            if !savedOnDismiss {
                viewModel.revertOverlayPreview()
            }
        }
        .task(id: overlayPreviewToken) {
            try? await Task.sleep(for: .milliseconds(80))
            viewModel.previewOverlay(draft)
        }
    }

    /// Direct-manipulation entry point: closes the sheet and enters Arrange mode over the live
    /// preview (pinch to resize the board, drag to place board + sponsor).
    private var arrangeOnScreenButton: some View {
        Button {
            savedOnDismiss = true // keep the current preview; Arrange manages its own draft
            dismiss()
            viewModel.enterArrangeMode()
        } label: {
            HStack {
                Image(systemName: "arrow.up.and.down.and.arrow.left.and.right")
                Text("Arrange on screen")
                    .fontWeight(.semibold)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(CricTheme.textMuted)
            }
            .foregroundStyle(CricTheme.accent)
            .padding(14)
            .background(Color.white.opacity(0.06))
            .cornerRadius(10)
        }
    }

    /// Stable key so slider drags debounce into one preview push (~80 ms).
    private var overlayPreviewToken: String {
        [
            draft.theme,
            String(draft.widthFraction),
            String(draft.heightFraction),
            String(draft.fontScale),
            String(draft.opacity),
            String(draft.bottomMargin),
            String(draft.watermarkEnabled),
            draft.watermarkText,
            String(draft.sponsorEnabled),
            draft.activeSponsorIds.joined(separator: ","),
            draft.sponsorLayoutMode,
            String(draft.sponsorCarouselIntervalSec),
            draft.activeSponsorId ?? "",
            draft.sponsorDisplayMode,
            String(draft.sponsorPositionX),
            String(draft.sponsorPositionY),
            String(draft.sponsorSizeScale),
            String(draft.sponsorOpacity),
            String(draft.sponsorScrollSpeed),
        ].joined(separator: "|")
    }

    private var boardColorSelector: some View {
        VStack(alignment: .leading, spacing: 10) {
            sheetSectionLabel("Board colour")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(boardColors, id: \.name) { preset in
                        let selected = draft.bgColor == preset.bg && draft.textColor == preset.text
                        Button {
                            draft.bgColor = preset.bg
                            draft.textColor = preset.text
                            draft.theme = "barlow"
                        } label: {
                            VStack(spacing: 6) {
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(preset.color)
                                    .frame(width: 52, height: 52)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12)
                                            .stroke(selected ? CricTheme.accent : Color.clear, lineWidth: 2)
                                    )
                                Text(preset.name)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(selected ? CricTheme.accent : CricTheme.textDim)
                            }
                        }
                    }
                }
            }
        }
    }

    private var overlaySliders: some View {
        VStack(spacing: 16) {
            sliderRow(
                label: "Board width",
                value: $draft.widthFraction,
                range: 0.25...0.98,
                format: { "\(Int($0 * 100))%" }
            )
            sliderRow(
                label: "Board height",
                value: $draft.heightFraction,
                range: 0.10...0.28,
                format: { "\(Int($0 * 100))%" }
            )
            sliderRow(
                label: "Font scale",
                value: $draft.fontScale,
                range: 0.6...2.0,
                format: { String(format: "%.1f×", $0) }
            )
            sliderRow(
                label: "Opacity",
                value: $draft.opacity,
                range: 0.2...1.0,
                format: { "\(Int($0 * 100))%" }
            )
            sliderRow(
                label: "Position",
                value: $draft.bottomMargin,
                range: 0...48,
                format: { "\(Int($0))" }
            )

            Divider().overlay(Color.white.opacity(0.1))

            VStack(alignment: .leading, spacing: 8) {
                Text("Video stabilisation")
                    .font(.subheadline)
                    .foregroundStyle(.white)
                // Binding goes through withStabilizationLevel so the wire-compat boolean stays in sync.
                Picker("Video stabilisation", selection: Binding(
                    get: { draft.stabilizationLevel },
                    set: { draft = draft.withStabilizationLevel($0) }
                )) {
                    Text("Off").tag(StabilizationLevel.off.rawValue)
                    Text("Standard").tag(StabilizationLevel.standard.rawValue)
                    Text("Cinematic").tag(StabilizationLevel.cinematic.rawValue)
                }
                .pickerStyle(.segmented)
            }
            if draft.stabilizationLevel == StabilizationLevel.cinematic.rawValue {
                Text("Strong stabilization slightly narrows the camera's field of view.")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
            }
            Toggle("Keep screen on", isOn: $draft.keepScreenOn)
                .tint(CricTheme.primary)
                .font(.subheadline)
                .foregroundStyle(.white)

            Divider().overlay(Color.white.opacity(0.1))

            // Watermark (admin): burned into the encoded stream, top-right.
            VStack(alignment: .leading, spacing: 10) {
                Toggle("Stream watermark", isOn: $draft.watermarkEnabled)
                    .tint(CricTheme.primary)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                if draft.watermarkEnabled {
                    TextField("Watermark text", text: $draft.watermarkText)
                        .modifier(StudioFieldStyle())
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .cricEnterAnimation(value: draft.watermarkEnabled, duration: CricMotion.sheetEnterDuration)
            .cricExitAnimation(value: draft.watermarkEnabled)

            VStack(alignment: .leading, spacing: 10) {
                Toggle("Sponsor logo", isOn: $draft.sponsorEnabled)
                    .tint(CricTheme.primary)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                Text("On-stream sponsor graphics — fixed or scrolling")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
                if draft.sponsorEnabled && !sponsors.filter(\.isActive).isEmpty {
                    Text("How to show")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                    sponsorLayoutPicker
                    Text(
                        SponsorLayoutMode.allowsMultiSelect(draft.sponsorLayoutMode)
                            ? "Select sponsors (up to 6)"
                            : "Select sponsor for this match"
                    )
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
                    sponsorPicker
                    sponsorDisplayControls
                } else if draft.sponsorEnabled && sponsors.filter(\.isActive).isEmpty {
                    Text("No sponsors yet — upload logos in the web dashboard under Sponsor logos.")
                        .font(.caption)
                        .foregroundStyle(CricTheme.warning)
                }
            }
            .cricEnterAnimation(value: draft.sponsorEnabled, duration: CricMotion.sheetEnterDuration)
        }
    }

    private var sponsorLayoutPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SponsorLayoutMode.modes, id: \.id) { mode in
                    Button {
                        draft.sponsorLayoutMode = mode.id
                        if !SponsorLayoutMode.allowsMultiSelect(mode.id), draft.activeSponsorIds.count > 1 {
                            draft.activeSponsorIds = Array(draft.activeSponsorIds.prefix(1))
                            draft.activeSponsorId = draft.activeSponsorIds.first
                        }
                    } label: {
                        Text(mode.label)
                            .font(.caption.weight(draft.sponsorLayoutMode == mode.id ? .bold : .regular))
                            .foregroundStyle(draft.sponsorLayoutMode == mode.id ? CricTheme.onPrimary : .white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(
                                draft.sponsorLayoutMode == mode.id ? CricTheme.primary : CricTheme.surface,
                                in: Capsule()
                            )
                    }
                }
            }
        }
    }

    private var sponsorDisplayControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            sheetSectionLabel("Sponsor display")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SponsorDisplayMode.modes, id: \.id) { mode in
                        Button {
                            draft.sponsorDisplayMode = mode.id
                        } label: {
                            Text(mode.label)
                                .font(.caption.weight(draft.sponsorDisplayMode == mode.id ? .bold : .regular))
                                .foregroundStyle(draft.sponsorDisplayMode == mode.id ? CricTheme.onPrimary : .white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 8)
                                .background(
                                    draft.sponsorDisplayMode == mode.id ? CricTheme.primary : CricTheme.surface,
                                    in: Capsule()
                                )
                        }
                    }
                }
            }
            sliderRow(
                label: "Logo size",
                value: $draft.sponsorSizeScale,
                range: 0.3...3.0,
                format: { "\(Int($0 * 100))%" }
            )
            sliderRow(
                label: "Logo opacity",
                value: $draft.sponsorOpacity,
                range: 0.2...1.0,
                format: { "\(Int($0 * 100))%" }
            )
            if SponsorDisplayMode.isScroll(draft.sponsorDisplayMode) {
                sheetSectionLabel("Scroll direction")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SponsorScrollDirection.directions, id: \.id) { dir in
                            Button {
                                draft.sponsorScrollDirection = dir.id
                            } label: {
                                Text(dir.label)
                                    .font(.caption.weight(draft.sponsorScrollDirection == dir.id ? .bold : .regular))
                                    .foregroundStyle(draft.sponsorScrollDirection == dir.id ? CricTheme.onPrimary : .white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 8)
                                    .background(
                                        draft.sponsorScrollDirection == dir.id ? CricTheme.primary : CricTheme.surface,
                                        in: Capsule()
                                    )
                            }
                        }
                    }
                }
                sliderRow(
                    label: "Scroll speed",
                    value: $draft.sponsorScrollSpeed,
                    range: 0.3...3.0,
                    format: { String(format: "%.1f×", $0) }
                )
            } else {
                sliderRow(
                    label: "Horizontal position",
                    value: $draft.sponsorPositionX,
                    range: 0...1,
                    format: { "\(Int($0 * 100))%" }
                )
                sliderRow(
                    label: "Vertical position",
                    value: $draft.sponsorPositionY,
                    range: 0...1,
                    format: { "\(Int($0 * 100))%" }
                )
            }
            if draft.sponsorLayoutMode == SponsorLayoutMode.carousel {
                sliderRow(
                    label: "Carousel interval",
                    value: $draft.sponsorCarouselIntervalSec,
                    range: 2...30,
                    format: { "\(Int($0))s" }
                )
            }
        }
        .padding(.top, 4)
    }

    private var sponsors: [Sponsor] { viewModel.sponsors }

    private var sponsorPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(sponsors.filter(\.isActive)) { sponsor in
                    Button {
                        if SponsorLayoutMode.allowsMultiSelect(draft.sponsorLayoutMode) {
                            if draft.activeSponsorIds.contains(sponsor.id) {
                                draft.activeSponsorIds.removeAll { $0 == sponsor.id }
                            } else if draft.activeSponsorIds.count < 6 {
                                draft.activeSponsorIds.append(sponsor.id)
                            }
                            draft.activeSponsorId = draft.activeSponsorIds.first
                        } else {
                            draft.activeSponsorIds = [sponsor.id]
                            draft.activeSponsorId = sponsor.id
                        }
                    } label: {
                        let selected = draft.activeSponsorIds.contains(sponsor.id) ||
                            (draft.activeSponsorIds.isEmpty && draft.activeSponsorId == sponsor.id)
                        Text(sponsor.name)
                            .font(.caption.weight(selected ? .semibold : .regular))
                            .foregroundStyle(selected ? CricTheme.onPrimary : .white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(
                                selected ? CricTheme.primary : CricTheme.surface,
                                in: Capsule()
                            )
                    }
                }
            }
        }
    }

    private func sliderRow(label: String, value: Binding<Double>, range: ClosedRange<Double>, format: (Double) -> String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(CricTheme.textMuted)
                Spacer()
                Text(format(value.wrappedValue))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(CricTheme.primary)
            }
            Slider(value: value, in: range)
                .tint(CricTheme.primary)
        }
    }

    private func sheetSectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(CricTheme.textDim)
            .textCase(.uppercase)
            .tracking(0.8)
    }
}

// MARK: - Scoring sheet

struct ScoringSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    private let modes: [(id: String, icon: String, title: String, subtitle: String)] = [
        ("auto",   "bolt.fill",               "Auto (Play-Cricket)", "Follows your club scorer in real time"),
        ("auto_ch","link",                    "Auto (CricHeroes)",   "Best-effort scrape from CricHeroes"),
        ("manual", "pencil.and.list.clipboard", "Manual scorer",      "Open the web scorer interface"),
    ]

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 12) {
                        ForEach(modes, id: \.id) { mode in
                            let active = viewModel.scoringConfig?.mode == mode.id
                            Button {
                                Task {
                                    if mode.id == "auto_ch" {
                                        await viewModel.setScoringMode("auto", provider: "cricheroes")
                                    } else {
                                        await viewModel.setScoringMode(mode.id)
                                    }
                                }
                                dismiss()
                            } label: {
                                HStack(spacing: 14) {
                                    Image(systemName: mode.icon)
                                        .font(.system(size: 17, weight: .semibold))
                                        .foregroundStyle(active ? CricTheme.primary : CricTheme.textMuted)
                                        .frame(width: 40, height: 40)
                                        .background(
                                            (active ? CricTheme.primary : CricTheme.textDim).opacity(0.14),
                                            in: RoundedRectangle(cornerRadius: 11)
                                        )
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(mode.title)
                                            .font(.subheadline.bold())
                                            .foregroundStyle(.white)
                                        Text(mode.subtitle)
                                            .font(.caption)
                                            .foregroundStyle(CricTheme.textMuted)
                                    }
                                    Spacer()
                                    if active {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(CricTheme.primary)
                                    }
                                }
                                .padding(14)
                                .background(
                                    active ? CricTheme.primary.opacity(0.08) : CricTheme.surface,
                                    in: RoundedRectangle(cornerRadius: 14)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(active ? CricTheme.primary.opacity(0.3) : Color.white.opacity(0.08), lineWidth: 1)
                                )
                            }
                        }

                        if let config = viewModel.scoringConfig, let scorerUrl = URL(string: config.scorerUrl) {
                            Link(destination: scorerUrl) {
                                HStack {
                                    Image(systemName: "safari")
                                    Text("Open scorer in browser")
                                }
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(CricTheme.accent)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(CricTheme.accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                            }
                            .padding(.top, 6)
                        }
                    }
                    .padding(24)
                }
            }
            .navigationTitle("Scoring mode")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Preflight sheet

struct PreflightSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    var allGood: Bool {
        viewModel.preflightCameraOk && viewModel.preflightDestinationOk
    }

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 20) {
                    VStack(spacing: 10) {
                        checkRow(
                            label: "Camera ready",
                            passed: viewModel.preflightCameraOk,
                            hint: "Tap ⋯ → Restart camera preview"
                        )
                        checkRow(
                            label: "Destination configured",
                            passed: viewModel.preflightDestinationOk,
                            hint: "Select YouTube, Twitch, or enter custom RTMP"
                        )
                        checkRow(
                            label: "Scoreboard on stream",
                            passed: viewModel.preflightOverlayOk,
                            hint: "Overlay will render once live — optional"
                        )
                    }
                    .padding(16)
                    .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 16))

                    Button {
                        dismiss()
                        Task { await viewModel.confirmGoLive() }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "dot.radiowaves.left.and.right")
                            Text("Go Live")
                        }
                    }
                    .buttonStyle(PrimaryCtaStyle())
                    .disabled(!allGood)

                    Button("Cancel") { dismiss() }
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .navigationTitle("Pre-flight check")
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium])
    }

    private func checkRow(label: String, passed: Bool, hint: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: passed ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(passed ? CricTheme.accent : CricTheme.danger)
                .font(.system(size: 20))
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                if !passed {
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Menu sheet

struct StudioMenuSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 12) {
                    Button {
                        dismiss()
                        Task { await viewModel.restartCameraPreview() }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "camera.rotate")
                                .font(.system(size: 17))
                                .foregroundStyle(CricTheme.accent)
                                .frame(width: 36, height: 36)
                                .background(CricTheme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                            Text("Restart camera preview")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                            Spacer()
                        }
                        .padding(14)
                        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
                    }

                    Button {
                        dismiss()
                        Task { await viewModel.openPairRemote() }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "qrcode")
                                .font(.system(size: 17))
                                .foregroundStyle(CricTheme.primary)
                                .frame(width: 36, height: 36)
                                .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                            Text("Pair Remote")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                            Spacer()
                        }
                        .padding(14)
                        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
                    }

                    if !viewModel.watchUrl.isEmpty {
                        ShareLink(item: viewModel.watchUrl) {
                            HStack(spacing: 12) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 17))
                                    .foregroundStyle(CricTheme.primary)
                                    .frame(width: 36, height: 36)
                                    .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                                Text("Share watch link")
                                    .font(.subheadline)
                                    .foregroundStyle(.white)
                                Spacer()
                            }
                            .padding(14)
                            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
                        }
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .navigationTitle("Studio options")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium])
    }
}
