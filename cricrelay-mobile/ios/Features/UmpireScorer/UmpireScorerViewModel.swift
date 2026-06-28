import Foundation
import Combine

enum UmpireDeliveryMode: String, Codable {
    case normal, wide, noBall, bye, legBye
}

struct UmpireBallEvent: Codable, Identifiable {
    let id: UUID
    let label: String
    let totalRuns: Int
    let isLegal: Bool
    let isWicket: Bool

    init(label: String, totalRuns: Int, isLegal: Bool, isWicket: Bool) {
        self.id = UUID()
        self.label = label
        self.totalRuns = totalRuns
        self.isLegal = isLegal
        self.isWicket = isWicket
    }
}

struct UmpireScorerPersistable: Codable {
    var totalRuns: Int
    var totalWickets: Int
    var completedOvers: Int
    var legalBallCount: Int
    var currentOverBalls: [UmpireBallEvent]
}

@MainActor
final class UmpireScorerViewModel: ObservableObject {
    @Published var totalRuns = 0
    @Published var totalWickets = 0
    @Published var completedOvers = 0
    @Published var currentOverBalls: [UmpireBallEvent] = []
    @Published var legalBallCount = 0
    @Published var deliveryMode: UmpireDeliveryMode = .normal
    @Published var pendingWicket = false

    private var history: [[UmpireBallEvent]] = []
    private let persistKey = "umpire_scorer_state"

    init() {
        load()
    }

    func onRunsPressed(_ runs: Int) {
        history.append(currentOverBalls)
        let isLegal = deliveryMode == .normal || deliveryMode == .bye || deliveryMode == .legBye
        let runsScored = (deliveryMode == .wide || deliveryMode == .noBall) ? runs + 1 : runs
        let ball = UmpireBallEvent(
            label: buildLabel(mode: deliveryMode, wicket: pendingWicket, runs: runs),
            totalRuns: runsScored,
            isLegal: isLegal,
            isWicket: pendingWicket
        )
        currentOverBalls.append(ball)
        totalRuns += runsScored
        if pendingWicket { totalWickets += 1 }
        if isLegal { legalBallCount += 1 }
        deliveryMode = .normal
        pendingWicket = false
        save()
    }

    func onWidePressed() {
        deliveryMode = deliveryMode == .wide ? .normal : .wide
    }

    func onNoBallPressed() {
        deliveryMode = deliveryMode == .noBall ? .normal : .noBall
    }

    func onByePressed() {
        deliveryMode = deliveryMode == .bye ? .normal : .bye
    }

    func onLegByePressed() {
        deliveryMode = deliveryMode == .legBye ? .normal : .legBye
    }

    func onWicketToggle() {
        pendingWicket.toggle()
    }

    func onUndo() {
        guard let prev = history.popLast() else { return }
        let undone = currentOverBalls.last
        currentOverBalls = prev
        if let ball = undone {
            totalRuns -= ball.totalRuns
            if ball.isWicket { totalWickets -= 1 }
            if ball.isLegal { legalBallCount -= 1 }
        }
        deliveryMode = .normal
        pendingWicket = false
        save()
    }

    func onEndOver() {
        completedOvers += 1
        currentOverBalls = []
        legalBallCount = 0
        deliveryMode = .normal
        pendingWicket = false
        history.removeAll()
        save()
    }

    func onReset() {
        totalRuns = 0
        totalWickets = 0
        completedOvers = 0
        currentOverBalls = []
        legalBallCount = 0
        deliveryMode = .normal
        pendingWicket = false
        history.removeAll()
        save()
    }

    var modeIndicatorText: String {
        switch (deliveryMode, pendingWicket) {
        case (.normal, false): return "Tap runs — or pick type first"
        case (.normal, true): return "WICKET pending — tap runs"
        case (let m, true): return "\(m.rawValue.uppercased()) + WICKET — tap runs"
        case (let m, false): return "\(m.rawValue.uppercased()) — tap runs"
        }
    }

    // MARK: - Helpers

    private func buildLabel(mode: UmpireDeliveryMode, wicket: Bool, runs: Int) -> String {
        let base: String
        switch mode {
        case .normal:  base = runs == 0 ? "·" : "\(runs)"
        case .wide:    base = runs == 0 ? "Wd" : "Wd+\(runs)"
        case .noBall:  base = runs == 0 ? "Nb" : "Nb+\(runs)"
        case .bye:     base = runs == 0 ? "B" : "B+\(runs)"
        case .legBye:  base = runs == 0 ? "Lb" : "Lb+\(runs)"
        }
        return wicket ? "\(base)+W" : base
    }

    // MARK: - Persistence

    private func save() {
        let data = UmpireScorerPersistable(
            totalRuns: totalRuns,
            totalWickets: totalWickets,
            completedOvers: completedOvers,
            legalBallCount: legalBallCount,
            currentOverBalls: currentOverBalls
        )
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: persistKey)
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: persistKey),
              let saved = try? JSONDecoder().decode(UmpireScorerPersistable.self, from: data)
        else { return }
        totalRuns = saved.totalRuns
        totalWickets = saved.totalWickets
        completedOvers = saved.completedOvers
        legalBallCount = saved.legalBallCount
        currentOverBalls = saved.currentOverBalls
    }
}
