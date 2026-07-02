import SwiftUI

struct ScoringView: View {
    let matchSlug: String
    @StateObject private var viewModel = ScoringViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 20) {
                    if viewModel.loading {
                        ProgressView().tint(CricTheme.accent)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        scoringContent
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .padding(24)
            }
            .navigationTitle("Scoring")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
        .task { await viewModel.load(slug: matchSlug) }
    }

    private var scoringContent: some View {
        VStack(spacing: 16) {
            // Header card
            HStack(spacing: 14) {
                Image(systemName: "pencil.and.list.clipboard")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(CricTheme.accent)
                    .frame(width: 48, height: 48)
                    .background(CricTheme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 14))

                VStack(alignment: .leading, spacing: 3) {
                    Text("Web scorer")
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                    Text("Input scores via the manual scoring interface")
                        .font(.footnote)
                        .foregroundStyle(CricTheme.textMuted)
                }
                Spacer()
            }
            .padding(14)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08), lineWidth: 1))

            // Current mode chip
            if let config = viewModel.config {
                HStack {
                    Text("Active mode:")
                        .font(.footnote)
                        .foregroundStyle(CricTheme.textDim)
                    Text(config.mode.capitalized)
                        .font(.footnote.bold())
                        .foregroundStyle(CricTheme.primary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(CricTheme.primary.opacity(0.15), in: Capsule())
                    Spacer()
                }

                if let scorerUrl = URL(string: config.scorerUrl) {
                    Link(destination: scorerUrl) {
                        HStack {
                            Image(systemName: "safari")
                            Text("Open scorer in browser")
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(CricTheme.onPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(CricTheme.ctaGradient, in: RoundedRectangle(cornerRadius: 14))
                        .shadow(color: CricTheme.primary.opacity(0.35), radius: 10, y: 4)
                    }
                }
            }

            if let error = viewModel.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(CricTheme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }
}

// MARK: - ViewModel

@MainActor
private final class ScoringViewModel: ObservableObject {
    @Published var config: ScoringConfig?
    @Published var loading = false
    @Published var error: String?

    private let api = CricRelayAPI.shared

    func load(slug: String) async {
        loading = true
        defer { loading = false }
        do {
            config = try await api.scoringConfig(slug: slug)
        } catch {
            self.error = error.localizedDescription
        }
    }
}
