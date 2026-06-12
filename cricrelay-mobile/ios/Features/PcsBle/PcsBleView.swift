import SwiftUI

struct PcsBleView: View {
    @StateObject private var manager = PcsBleManager()
    @Environment(\.dismiss) private var dismiss

    @State private var ingestUrl  = UserDefaults.standard.string(forKey: "pcs_ingest_url")  ?? ""
    @State private var bearerToken = UserDefaults.standard.string(forKey: "pcs_bearer_token") ?? ""
    @State private var settingsSaved = false

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                ScrollView {
                    VStack(spacing: 16) {
                        introCard
                        settingsCard
                        statusCard
                        if !manager.recentPackets.isEmpty {
                            packetLog
                        }
                        toggleButton
                    }
                    .padding(24)
                }
            }
            .navigationTitle("PCS BLE relay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }.foregroundStyle(CricTheme.textMuted)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Intro card

    private var introCard: some View {
        HStack(spacing: 14) {
            Image(systemName: "wave.3.right")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(CricTheme.primary)
                .frame(width: 48, height: 48)
                .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 14))
            VStack(alignment: .leading, spacing: 3) {
                Text("Bluetooth scoreboard relay")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Text("Advertises as BT-Scoreboard so PCS can connect and send scores via BLE.")
                    .font(.footnote)
                    .foregroundStyle(CricTheme.textMuted)
            }
        }
        .padding(14)
        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - Settings card

    private var settingsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionLabel("Relay settings")

            TextField("Ingest URL (https://…)", text: $ingestUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .modifier(StudioFieldStyle())

            SecureField("Bearer token (optional)", text: $bearerToken)
                .modifier(StudioFieldStyle())

            Button {
                UserDefaults.standard.set(ingestUrl, forKey: "pcs_ingest_url")
                UserDefaults.standard.set(bearerToken, forKey: "pcs_bearer_token")
                manager.configure(ingestUrl: ingestUrl, bearerToken: bearerToken)
                settingsSaved = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { settingsSaved = false }
            } label: {
                HStack {
                    if settingsSaved {
                        Image(systemName: "checkmark")
                        Text("Saved")
                    } else {
                        Text("Save settings")
                    }
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(CricTheme.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(CricTheme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(PressableScaleStyle())
            .cricEnterAnimation(value: settingsSaved, duration: CricMotion.enterDuration)
        }
    }

    // MARK: - Status card

    private var statusCard: some View {
        VStack(spacing: 12) {
            sectionLabel("Relay status")

            HStack {
                // Status chip
                HStack(spacing: 6) {
                    Circle()
                        .fill(manager.advertising ? CricTheme.accent : CricTheme.textDim)
                        .frame(width: 7, height: 7)
                    Text(manager.advertising ? "Advertising" : "Stopped")
                        .font(.footnote.bold())
                        .foregroundStyle(manager.advertising ? CricTheme.accent : CricTheme.textDim)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(
                    (manager.advertising ? CricTheme.accent : CricTheme.textDim).opacity(0.12),
                    in: Capsule()
                )

                Spacer()

                Text(manager.statusMessage)
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
                    .lineLimit(1)
            }

            HStack(spacing: 0) {
                statItem(value: "\(manager.packetCount)", label: "PACKETS")
                Divider().frame(height: 32).overlay(Color.white.opacity(0.12))
                statItem(value: "\(manager.postedOk)", label: "POSTED OK")
                Divider().frame(height: 32).overlay(Color.white.opacity(0.12))
                statItem(value: "\(manager.postFail)", label: "FAILED", tint: manager.postFail > 0 ? CricTheme.danger : .white)
            }
            .padding(12)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func statItem(value: String, label: String, tint: Color = .white) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(CricTheme.textDim)
                .tracking(0.5)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Packet log

    private var packetLog: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("Recent packets")
            VStack(alignment: .leading, spacing: 4) {
                ForEach(manager.recentPackets, id: \.self) { packet in
                    Text(packet)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(CricTheme.accent)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.08), lineWidth: 1))
        }
    }

    // MARK: - Toggle button

    private var toggleButton: some View {
        Button {
            if manager.advertising {
                manager.stop()
            } else {
                manager.configure(ingestUrl: ingestUrl, bearerToken: bearerToken)
                manager.start()
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: manager.advertising ? "stop.fill" : "wave.3.right")
                Text(manager.advertising ? "Stop relay" : "Start relay")
            }
        }
        .buttonStyle(PrimaryCtaStyle())
        .tint(manager.advertising ? CricTheme.danger : CricTheme.primary)
    }

    // MARK: - Helper

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(CricTheme.textDim)
            .textCase(.uppercase)
            .tracking(0.8)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
