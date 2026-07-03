import SwiftUI
import Shared

struct CreateStreamView: View {
    let mode: String  // "play_cricket" or "cricheroes"
    @ObservedObject var viewModel: HomeViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedFixtureId: String?
    @State private var label = ""
    @State private var cricheroesUrl = ""
    @State private var error: String?
    @State private var busy = false

    private var isPlayCricket: Bool { mode == "play_cricket" }

    private var canCreate: Bool {
        if isPlayCricket { return selectedFixtureId != nil }
        return !cricheroesUrl.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 16) {
                        if isPlayCricket {
                            playCricketContent
                        } else {
                            cricheroesContent
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
            .navigationTitle(isPlayCricket ? "Play-Cricket stream" : "CricHeroes stream")
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

    private var playCricketContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Select fixture")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(CricTheme.textDim)
                .textCase(.uppercase)
                .tracking(0.8)

            if viewModel.fixturesLoading {
                VStack(spacing: 8) {
                    ProgressView().tint(CricTheme.accent)
                    Text("Loading fixtures…")
                        .font(.footnote)
                        .foregroundStyle(CricTheme.textMuted)
                }
                .frame(maxWidth: .infinity)
                .padding(24)
            } else if let fixturesError = viewModel.fixturesError {
                VStack(spacing: 10) {
                    Text(fixturesError)
                        .font(.footnote)
                        .foregroundStyle(CricTheme.danger)
                        .multilineTextAlignment(.center)
                    Button("Retry") {
                        Task { await viewModel.loadFixtures() }
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(CricTheme.accent)
                }
                .frame(maxWidth: .infinity)
                .padding(24)
            } else if viewModel.fixtures.isEmpty {
                Text("No upcoming fixtures found for your club on Play-Cricket.")
                    .font(.footnote)
                    .foregroundStyle(CricTheme.textMuted)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(24)
            } else {
                ForEach(viewModel.fixtures, id: \.matchId) { fixture in
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

    private var cricheroesContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CricHeroes scorecard URL")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(CricTheme.textDim)
                .textCase(.uppercase)
                .tracking(0.8)

            TextField("https://cricheroes.in/scorecard/…/live", text: $cricheroesUrl)
                .modifier(StudioFieldStyle())
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)

            TextField("Stream label (optional)", text: $label)
                .modifier(StudioFieldStyle())

            Text("R&D / best-effort — CricHeroes may block automated scraping.")
                .font(.footnote)
                .foregroundStyle(CricTheme.textDim)
                .padding(.top, 4)
        }
    }

    private func create() async {
        error = nil
        busy = true
        defer { busy = false }
        do {
            if isPlayCricket {
                // Never fall through to the CricHeroes branch with no fixture selected —
                // that would create a stream with an empty URL.
                guard let fixtureId = selectedFixtureId else { return }
                let fixtureTitle = viewModel.fixtures.first { $0.matchId == fixtureId }?.title ?? fixtureId
                _ = try await viewModel.createPlayCricketStream(matchId: fixtureId, label: fixtureTitle)
            } else {
                _ = try await viewModel.createCricHeroesStream(
                    matchUrl: cricheroesUrl.trimmingCharacters(in: .whitespaces),
                    label: label.isEmpty ? "CricHeroes stream" : label
                )
            }
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
