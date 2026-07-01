import SwiftUI
import AVFoundation

// MARK: - Companion remote control (scan QR → send commands)

struct RemoteControlView: View {
    @State private var phase: Phase = .scan
    @State private var matchSlug = ""
    @State private var companionToken = ""
    @State private var statusMessage = ""
    @State private var error: String?

    private let api = CricRelayAPI.shared

    enum Phase {
        case scan, controls
    }

    var body: some View {
        StudioBackdrop {
            switch phase {
            case .scan:
                scanContent
            case .controls:
                controlsContent
            }
        }
        .navigationTitle("Remote Control")
        .navigationBarTitleDisplayMode(.inline)
        .preferredColorScheme(.dark)
        .onAppear { restoreSession() }
    }

    private var scanContent: some View {
        VStack(spacing: 20) {
            Text("Scan the Pair Remote QR code shown on the broadcasting phone.")
                .font(.subheadline)
                .foregroundStyle(CricTheme.textMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)

            QRScannerView { payload in
                Task { await handleScan(payload) }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 16)

            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(CricTheme.danger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
            }
        }
        .padding(.top, 16)
    }

    private var controlsContent: some View {
        VStack(spacing: 16) {
            Text("Paired to \(matchSlug)")
                .font(.subheadline.bold())
                .foregroundStyle(.white)

            if !statusMessage.isEmpty {
                Text(statusMessage)
                    .font(.caption)
                    .foregroundStyle(CricTheme.accent)
            }

            controlButton("Start broadcast", icon: "play.fill", command: "start_broadcast")
            controlButton("Stop broadcast", icon: "stop.fill", command: "stop_broadcast")
            controlButton("Mute mic", icon: "mic.slash.fill", command: "mute_mic")
            controlButton("Toggle focus lock", icon: "lock.fill", command: "toggle_focus_lock")

            Button("Unpair") {
                CompanionTokenStore.clear()
                phase = .scan
                matchSlug = ""
                companionToken = ""
                statusMessage = ""
            }
            .font(.subheadline)
            .foregroundStyle(CricTheme.danger)
            .padding(.top, 12)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func controlButton(_ label: String, icon: String, command: String) -> some View {
        Button {
            Task { await sendCommand(command) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(CricTheme.primary)
                    .frame(width: 36, height: 36)
                    .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                Text(label)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Spacer()
            }
            .padding(14)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(PressableScaleStyle())
    }

    private func restoreSession() {
        if let saved = CompanionTokenStore.load() {
            companionToken = saved.token
            matchSlug = saved.slug
            phase = .controls
        }
    }

    private func handleScan(_ payload: String) async {
        error = nil
        guard let components = URLComponents(string: payload),
              components.scheme == "cricrelay",
              components.host == "pair" else {
            error = "Not a CricRelay pairing code"
            return
        }
        let items = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        guard let slug = items["slug"], !slug.isEmpty,
              let token = items["token"], !token.isEmpty else {
            error = "Invalid pairing code"
            return
        }
        do {
            let session = try await api.redeemPairToken(slug: slug, pairToken: token)
            companionToken = session.companionToken
            matchSlug = session.matchSlug
            CompanionTokenStore.save(token: companionToken, slug: matchSlug)
            phase = .controls
            statusMessage = "Paired successfully"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func sendCommand(_ command: String) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteCommand(slug: matchSlug, command: command, companionToken: companionToken)
            statusMessage = "Sent \(command.replacingOccurrences(of: "_", with: " "))"
        } catch {
            statusMessage = ""
            self.error = error.localizedDescription
        }
    }
}

// MARK: - QR scanner (AVCaptureMetadataOutput)

struct QRScannerView: UIViewControllerRepresentable {
    var onScan: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let vc = QRScannerViewController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        uiViewController.onScan = onScan
    }
}

final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didReport = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return }
        if session.canAddInput(input) { session.addInput(input) }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = [.qr]
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didReport,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr,
              let value = obj.stringValue else { return }
        didReport = true
        session.stopRunning()
        onScan?(value)
    }
}
