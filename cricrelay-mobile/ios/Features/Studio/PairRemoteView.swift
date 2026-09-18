import SwiftUI
import CoreImage.CIFilterBuiltins

// MARK: - Pair remote QR sheet (broadcasting phone)

struct PairRemoteSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var secondsLeft: Int? = nil

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 24) {
                    Text("Scan with the phone camera to open companion controls — no club login needed on that phone.")
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)

                    if let payload = viewModel.pairRemotePayload,
                       let image = QRCodeGenerator.image(from: payload, size: 240) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 240, height: 240)
                            .padding(16)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16))
                    } else {
                        ProgressView()
                            .tint(CricTheme.accent)
                            .frame(width: 240, height: 240)
                    }

                    if viewModel.isCompanionPaired {
                        Text("Companion connected")
                            .font(.headline)
                            .foregroundStyle(CricTheme.accent)
                        Text("You can close this screen — keep broadcasting on this phone.")
                            .font(.caption)
                            .foregroundStyle(CricTheme.textDim)
                            .multilineTextAlignment(.center)
                    } else {
                        Text("Waiting for companion…")
                            .font(.headline)
                            .foregroundStyle(Color.white)
                        if let left = secondsLeft {
                            Text(left <= 0 ? "Code expired — generate a new one" : "Expires in \(formatCountdown(left))")
                                .font(.caption)
                                .foregroundStyle(left <= 30 ? Color.orange : CricTheme.textDim)
                        }
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .navigationTitle("Pair Remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
            .task(id: viewModel.pairRemoteExpiresAt) {
                await tickExpiry()
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private func tickExpiry() async {
        guard let iso = viewModel.pairRemoteExpiresAt, !iso.isEmpty else {
            secondsLeft = nil
            return
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var expiry = formatter.date(from: iso)
        if expiry == nil {
            formatter.formatOptions = [.withInternetDateTime]
            expiry = formatter.date(from: iso)
        }
        guard let expiry else {
            secondsLeft = nil
            return
        }
        while !Task.isCancelled {
            let left = max(0, Int(expiry.timeIntervalSinceNow))
            secondsLeft = left
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    private func formatCountdown(_ total: Int) -> String {
        String(format: "%d:%02d", total / 60, total % 60)
    }
}

enum QRCodeGenerator {
    static func image(from string: String, size: CGFloat) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
