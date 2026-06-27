import Foundation
import CoreBluetooth

// BLE peripheral that advertises as "BT-Scoreboard" so the PCS app can connect.
// Received scoring packets are forwarded to a configurable HTTP ingest endpoint.

@MainActor
final class PcsBleManager: NSObject, ObservableObject {
    @Published var advertising = false
    @Published var statusMessage = "Idle"
    @Published var packetCount = 0
    @Published var postedOk = 0
    @Published var postFail = 0
    @Published var recentPackets: [String] = []

    private static let serviceUUID   = CBUUID(string: "5a0d6a15-b664-4304-8530-3a0ec53e5bc1")
    private static let characteristicUUID = CBUUID(string: "df531f62-fc0b-40ce-81b2-32a6262ea440")
    private static let advertiseName = "BT-Scoreboard"

    private var peripheralManager: CBPeripheralManager?
    private var scoreCharacteristic: CBMutableCharacteristic?
    private var ingestUrl = ""
    private var bearerToken = ""

    func configure(ingestUrl: String, bearerToken: String) {
        self.ingestUrl = ingestUrl
        self.bearerToken = bearerToken
    }

    func start() {
        guard !advertising else { return }
        statusMessage = "Starting…"
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func stop() {
        peripheralManager?.stopAdvertising()
        peripheralManager = nil
        advertising = false
        statusMessage = "Stopped"
    }

    private func beginAdvertising() {
        let characteristic = CBMutableCharacteristic(
            type: Self.characteristicUUID,
            properties: [.write, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.writeable, .readable]
        )
        scoreCharacteristic = characteristic

        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheralManager?.add(service)
        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: Self.advertiseName,
        ])
    }

    private func handlePacket(_ data: Data) {
        // Only count + relay packets that decode to a PCS scoreboard line, exactly like Android's
        // onPacket — noise/keepalives are dropped silently.
        guard let line = decodePacket(data) else { return }
        packetCount += 1
        recentPackets.insert(line, at: 0)
        if recentPackets.count > 12 { recentPackets.removeLast() }
        Task { await relay(line: line) }
    }

    /// Decode a raw BLE packet into a PCS scoreboard text line, mirroring Android's `decodePacket`:
    /// prefer a UTF-8 decode, else fall back to the printable-ASCII bytes; accept only when the
    /// result is at least 3 chars and begins with 3 letters (the PCS line prefix).
    private func decodePacket(_ data: Data) -> String? {
        if data.isEmpty { return nil }
        if let utf = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           utf.count >= 3, looksLikePcs(utf) {
            return utf
        }
        let ascii = String(
            data.filter { $0 >= 32 && $0 <= 126 }.map { Character(UnicodeScalar($0)) }
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        return (ascii.count >= 3 && looksLikePcs(ascii)) ? ascii : nil
    }

    private func looksLikePcs(_ s: String) -> Bool {
        guard s.count >= 3 else { return false }
        return s.prefix(3).allSatisfy { $0.isLetter }
    }

    /// POST the decoded line as `{"line": "..."}` JSON, byte-for-byte equivalent to the Android
    /// relay so the same server ingest endpoint behaves identically across platforms.
    private func relay(line: String) async {
        guard let url = URL(string: ingestUrl), !ingestUrl.isEmpty else {
            postFail += 1
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !bearerToken.isEmpty {
            // Match Android: pass an existing "Bearer " prefix through untouched, otherwise add one.
            let header = bearerToken.hasPrefix("Bearer ") ? bearerToken : "Bearer \(bearerToken)"
            request.setValue(header, forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try? JSONEncoder().encode(LinePayload(line: line))
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                postedOk += 1
            } else {
                postFail += 1
            }
        } catch {
            postFail += 1
        }
    }
}

private struct LinePayload: Encodable {
    let line: String
}

// MARK: - CBPeripheralManagerDelegate

extension PcsBleManager: @preconcurrency CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            switch peripheral.state {
            case .poweredOn:
                statusMessage = "BLE powered on — setting up…"
                beginAdvertising()
            case .poweredOff:
                advertising = false
                statusMessage = "Bluetooth is off"
            case .unauthorized:
                advertising = false
                statusMessage = "Bluetooth permission denied"
            case .unsupported:
                advertising = false
                statusMessage = "BLE not supported on this device"
            default:
                statusMessage = "BLE not ready"
            }
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        Task { @MainActor in
            if let error {
                advertising = false
                statusMessage = "Advertising failed: \(error.localizedDescription)"
            } else {
                advertising = true
                statusMessage = "Advertising as \(PcsBleManager.advertiseName)"
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if let data = request.value {
                Task { @MainActor in handlePacket(data) }
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }
}

// MARK: - Data hex helper

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
