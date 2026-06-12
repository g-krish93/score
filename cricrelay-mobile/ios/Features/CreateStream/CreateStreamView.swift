import SwiftUI

struct CreateStreamView: View {
    let mode: String  // "play_cricket" or "pcs_ble"
    @ObservedObject var viewModel: HomeViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedFixtureId: String?
    @State private var label = ""
    @State private var error: String?
    @State private var busy = false

    private var isPlayCricket: Bool { mode == "play_cricket" }

    private var canCreate: Bool {
        isPlayCricket ? selectedFixtureId != nil : !label.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 16) {
                        if isPlayCricket {
                            playCricketContent
                        } else {
                            pcsBleContent
                        }

                        if let error {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(CricTheme.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                        }

                        Button {
                            Task { await create() }
                        } label: {
                            if busy { ProgressView().tint(CricTheme.onPrimary) }
                            else { Text("Create stream") }
                        }
                        .buttonStyle(PrimaryCtaStyle())
                        .disabled(!canCreate || busy)
                        .padding(.top, 8)
                    }
                    .padding(24)
                }
            }
            .navigationTitle(isPlayCricket ? "Play-Cricket stream" : "PCS BLE stream")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Play-Cricket content

    private var playCricketContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Select fixture")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(CricTheme.textDim)
                .textCase(.uppercase)
                .tracking(0.8)

            if viewModel.fixtures.isEmpty {
                VStack(spacing: 8) {
                    ProgressView().tint(CricTheme.accent)
                    Text("Loading fixtures…")
                        .font(.footnote)
                        .foregroundStyle(CricTheme.textMuted)
                }
                .frame(maxWidth: .infinity)
                .padding(24)
            } else {
                ForEach(viewModel.fixtures) { fixture in
                    let isActive = viewModel.activeMatchIds.contains(fixture.matchId)
                    Button {
                        if !isActive { selectedFixtureId = fixture.matchId }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: selectedFixtureId == fixture.matchId ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(
                                    selectedFixtureId == fixture.matchId ? CricTheme.primary : CricTheme.textDim
                                )
                                .font(.system(size: 18))

                            VStack(alignment: .leading, spacing: 3) {
                                Text(fixture.title)
                                    .font(.subheadline)
                                    .foregroundStyle(isActive ? CricTheme.textDim : .white)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                if isActive {
                                    Text("Already live")
                                        .font(.caption)
                                        .foregroundStyle(CricTheme.danger)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(12)
                        .background(
                            selectedFixtureId == fixture.matchId
                                ? CricTheme.primary.opacity(0.1)
                                : CricTheme.surface,
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(
                                    selectedFixtureId == fixture.matchId
                                        ? CricTheme.primary.opacity(0.4)
                                        : Color.white.opacity(0.08),
                                    lineWidth: 1
                                )
                        )
                        .opacity(isActive ? 0.5 : 1)
                    }
                    .buttonStyle(PressableScaleStyle())
                    .disabled(isActive)
                }
            }
        }
    }

    // MARK: - PCS BLE content

    private var pcsBleContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Stream label")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(CricTheme.textDim)
                .textCase(.uppercase)
                .tracking(0.8)

            TextField("e.g. Saturday XI vs Visitors", text: $label)
                .modifier(StudioFieldStyle())

            Text("A BLE relay stream links directly to your PCS hardware scoreboard via Bluetooth.")
                .font(.footnote)
                .foregroundStyle(CricTheme.textDim)
                .padding(.top, 4)
        }
    }

    // MARK: - Create action

    private func create() async {
        error = nil
        busy = true
        defer { busy = false }
        do {
            if isPlayCricket, let fixtureId = selectedFixtureId {
                let fixtureTitle = viewModel.fixtures.first { $0.matchId == fixtureId }?.title ?? fixtureId
                _ = try await viewModel.createPlayCricketStream(matchId: fixtureId, label: fixtureTitle)
            } else {
                _ = try await viewModel.createPcsBleStream(label: label)
            }
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
