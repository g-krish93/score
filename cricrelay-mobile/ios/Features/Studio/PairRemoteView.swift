import SwiftUI
import CoreImage.CIFilterBuiltins

// MARK: - Pair remote QR sheet (broadcasting phone)

struct PairRemoteSheet: View {
    @ObservedObject var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                VStack(spacing: 24) {
                    Text("Scan with a companion device to control this broadcast remotely.")
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

                    if let expires = viewModel.pairRemoteExpiresAt, !expires.isEmpty {
                        Text("Code expires soon")
                            .font(.caption)
                            .foregroundStyle(CricTheme.textDim)
                    }

                    Text("Start/stop, mute mic, and toggle focus lock are available to the paired device.")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                        .multilineTextAlignment(.center)
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
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
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
