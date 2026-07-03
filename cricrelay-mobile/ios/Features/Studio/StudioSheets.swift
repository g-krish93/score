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

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 20) {
                        scoreboardToggle
                        if draft.overlayEnabled {
                            arrangeOnScreenButton
                            boardPresetSelector
                            bowlerIslandToggle
                            Divider().overlay(Color.white.opacity(0.1))
                        }
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

    /// Master switch for the score bar — off for book-scored matches where there's no data
    /// feed and an empty scoreboard bar would just clutter the preview and stream.
    private var scoreboardToggle: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Show scoreboard", isOn: $draft.overlayEnabled)
                .tint(CricTheme.primary)
                .font(.subheadline)
                .foregroundStyle(.white)
            Text("Turn off when scoring in a book — removes the score bar from the preview and the stream. Watermark and sponsor logos are unaffected.")
                .font(.caption)
                .foregroundStyle(CricTheme.textDim)
        }
        .cricEnterAnimation(value: draft.overlayEnabled, duration: CricMotion.sheetEnterDuration)
        .cricExitAnimation(value: draft.overlayEnabled)
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
            String(draft.bowlingIslandEnabled),
            String(draft.overlayEnabled),
            draft.bgColor,
            draft.textColor,
            draft.sponsorScrollDirection,
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

    /// Preset picker: two-tone swatches (row-1 background + accent dot) driven by the shared
    /// BoardPreset catalogue. Selecting a preset writes theme=id and clears the legacy
    /// bgColor/textColor overrides (they only ever applied to the Classic Barlow board).
    private var boardPresetSelector: some View {
        VStack(alignment: .leading, spacing: 10) {
            sheetSectionLabel("Board style")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(BoardPreset.all) { preset in
                        let selected = OverlayLayoutPrefs.sanitizeTheme(draft.theme) == preset.id
                        Button {
                            draft.theme = preset.id
                            draft.bgColor = ""
                            draft.textColor = ""
                        } label: {
                            VStack(spacing: 6) {
                                ZStack(alignment: .bottomTrailing) {
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(Color(cssColor: preset.row1Bg) ?? CricTheme.surface)
                                        .frame(width: 52, height: 52)
                                    Circle()
                                        .fill(Color(cssColor: preset.accent) ?? CricTheme.primary)
                                        .frame(width: 14, height: 14)
                                        .padding(6)
                                }
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(
                                            selected ? CricTheme.primary : Color.white.opacity(0.1),
                                            lineWidth: selected ? 2 : 1
                                        )
                                )
                                Text(preset.displayName)
                                    .font(CricFont.dmSans(10, weight: .medium))
                                    .foregroundStyle(selected ? CricTheme.primary : CricTheme.textDim)
                            }
                        }
                        .buttonStyle(PressableScaleStyle())
                    }
                }
            }
        }
    }

    /// Bowling island (bowler figures + THIS OVER strip) — floodlight-era boards only;
    /// the legacy Classic board has no island to toggle.
    private var bowlerIslandToggle: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Bowler island", isOn: $draft.bowlingIslandEnabled)
                .tint(CricTheme.primary)
                .font(.subheadline)
                .foregroundStyle(.white)
                .disabled(OverlayLayoutPrefs.sanitizeTheme(draft.theme) == "barlow")
            Text(
                OverlayLayoutPrefs.sanitizeTheme(draft.theme) == "barlow"
                    ? "The Classic board has no bowler island — pick a newer style to use it."
                    : "Separate bowler box (figures + this-over balls) beside the scoreboard."
            )
            .font(.caption)
            .foregroundStyle(CricTheme.textDim)
        }
    }

    private var overlaySliders: some View {
        VStack(spacing: 16) {
            if draft.overlayEnabled {
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
                if draft.heightFraction <= 0.11 {
                    Text("At the smallest height the batsmen strip is hidden.")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
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
                if draft.opacity < 0.6 {
                    Text("Below 60% the board is hard to read in sunlight.")
                        .font(.caption)
                        .foregroundStyle(CricTheme.warning)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                sliderRow(
                    label: "Position",
                    value: $draft.bottomMargin,
                    // Same reach as an Arrange drag (720-canvas px) — a 0…48 cap would snap a
                    // dragged board back down the moment this slider is touched.
                    range: 0...400,
                    format: { "\(Int($0))" }
                )

                Divider().overlay(Color.white.opacity(0.1))
            }

            // Stabilisation / keep-screen-on moved to the Camera settings sheet (SPEC
            // hierarchy: device settings live behind the camera check, style stays here).

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

                        if viewModel.scoringConfig?.mode == "manual" {
                            Button {
                                dismiss()
                                viewModel.activeSheet = .scorerQr
                            } label: {
                                HStack {
                                    Image(systemName: "qrcode")
                                    Text("Show scorer QR")
                                }
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(CricTheme.primary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(CricTheme.primary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                            }
                            .padding(.top, 6)
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

// MARK: - Camera settings sheet

/// Device-scoped camera controls behind checklist row 1 (SPEC hierarchy: stabilization /
/// orientation / keep-screen-on live here, pre-live only — the encoder must not be
/// reconfigured under an active publish).
struct CameraSettingsSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    private var locked: Bool { viewModel.streaming }

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        if locked {
                            HStack(spacing: 10) {
                                Image(systemName: "lock.fill")
                                    .foregroundStyle(CricTheme.warning)
                                    .font(.footnote)
                                Text("Camera settings are locked while you're live.")
                                    .font(.footnote)
                                    .foregroundStyle(CricTheme.warning)
                            }
                            .padding(12)
                            .background(CricTheme.warning.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                        }

                        HStack {
                            Text("Stream quality")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                            Spacer()
                            Text(StudioViewModel.streamQualityLabel)
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(CricTheme.textMuted)
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            Text("Video stabilisation")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                            // Persists via saveOverlay → device settings store + live engine apply.
                            Picker("Video stabilisation", selection: Binding(
                                get: { viewModel.overlayPrefs.stabilizationLevel },
                                set: { level in
                                    Task {
                                        await viewModel.saveOverlay(
                                            viewModel.overlayPrefs.withStabilizationLevel(level)
                                        )
                                    }
                                }
                            )) {
                                Text("Off").tag(StabilizationLevel.off.rawValue)
                                Text("Standard").tag(StabilizationLevel.standard.rawValue)
                                Text("Cinematic").tag(StabilizationLevel.cinematic.rawValue)
                            }
                            .pickerStyle(.segmented)
                            if viewModel.overlayPrefs.stabilizationLevel == StabilizationLevel.cinematic.rawValue {
                                Text("Strong stabilization slightly narrows the camera's field of view.")
                                    .font(.caption)
                                    .foregroundStyle(CricTheme.textDim)
                            }
                        }

                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Orientation")
                                    .font(.subheadline)
                                    .foregroundStyle(.white)
                                Text(orientationValueLabel)
                                    .font(.caption)
                                    .foregroundStyle(CricTheme.textDim)
                            }
                            Spacer()
                            Button {
                                Task { await viewModel.toggleOrientation() }
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "rotate.right")
                                    Text("Rotate")
                                }
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(CricTheme.accent)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(CricTheme.accent.opacity(0.12), in: Capsule())
                            }
                        }

                        Toggle("Keep screen on", isOn: Binding(
                            get: { viewModel.overlayPrefs.keepScreenOn },
                            set: { _ in Task { await viewModel.toggleKeepScreenOn() } }
                        ))
                        .tint(CricTheme.primary)
                        .font(.subheadline)
                        .foregroundStyle(.white)

                        Divider().overlay(Color.white.opacity(0.1))

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
                    }
                    .padding(24)
                    .disabled(locked)
                }
            }
            .navigationTitle("Camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private var orientationValueLabel: String {
        switch viewModel.orientationMode {
        case .auto: return "Auto — follows how you hold the phone"
        case .landscape: return "Locked to landscape"
        case .portrait: return "Locked to portrait"
        }
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

// MARK: - CSS colour parsing (BoardPreset swatches)

extension Color {
    /// Parse the CSS colour strings BoardPreset carries ("#RRGGBB" or "rgba(r,g,b,a)")
    /// into a SwiftUI Color for the preset swatches. Returns nil on anything unexpected.
    init?(cssColor: String) {
        let s = cssColor.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.hasPrefix("#") {
            let hex = String(s.dropFirst())
            guard hex.count == 6, let value = UInt64(hex, radix: 16) else { return nil }
            self.init(
                red: Double((value >> 16) & 0xFF) / 255.0,
                green: Double((value >> 8) & 0xFF) / 255.0,
                blue: Double(value & 0xFF) / 255.0
            )
            return
        }
        if s.hasPrefix("rgba(") || s.hasPrefix("rgb(") {
            let inner = s
                .replacingOccurrences(of: "rgba(", with: "")
                .replacingOccurrences(of: "rgb(", with: "")
                .replacingOccurrences(of: ")", with: "")
            let parts = inner.split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            guard parts.count >= 3,
                  let r = Double(parts[0]),
                  let g = Double(parts[1]),
                  let b = Double(parts[2]) else { return nil }
            let a = parts.count >= 4 ? (Double(parts[3]) ?? 1.0) : 1.0
            self.init(
                red: min(max(r / 255.0, 0), 1),
                green: min(max(g / 255.0, 0), 1),
                blue: min(max(b / 255.0, 0), 1),
                opacity: min(max(a, 0), 1)
            )
            return
        }
        return nil
    }
}
