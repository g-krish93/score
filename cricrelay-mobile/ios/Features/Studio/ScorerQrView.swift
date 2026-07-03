import SwiftUI

// MARK: - Scorer QR sheet (manual streams)
//
// A second phone scans this to open the scoring webpage in its browser — no
// app install. Always re-fetches a fresh link on open (tokens expire ~12h).

struct ScorerQrSheet: View {
    let slug: String
    @Environment(\.dismiss) private var dismiss

    @State private var link: ScorerLink?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 24) {
                    Text("Scan with the scorer's phone camera — the scoring page opens in their browser. No app needed.")
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)

                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(CricTheme.danger)
                            .multilineTextAlignment(.center)
                            .padding(12)
                            .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    } else if let link, let image = QRCodeGenerator.image(from: link.scorerUrl, size: 240) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 240, height: 240)
                            .padding(16)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16))

                        if let expires = link.expiresAt, !expires.isEmpty {
                            Text("Link lasts about 12 hours — reopen this screen for a fresh one.")
                                .font(.caption)
                                .foregroundStyle(CricTheme.textDim)
                                .multilineTextAlignment(.center)
                        }

                        if let url = URL(string: link.scorerUrl) {
                            ShareLink(item: url) {
                                HStack {
                                    Image(systemName: "square.and.arrow.up")
                                    Text("Share link instead")
                                }
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(CricTheme.accent)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(CricTheme.accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                            }
                            .padding(.top, 6)
                        }
                    } else {
                        ProgressView()
                            .tint(CricTheme.accent)
                            .frame(width: 240, height: 240)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .navigationTitle("Scorer link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
        .task {
            do {
                link = try await CricRelayAPI.shared.scorerLink(slug: slug)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
