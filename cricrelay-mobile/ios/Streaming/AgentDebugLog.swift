import Foundation

// #region agent log
enum AgentDebugLog {
    private static let endpoint =
        "http://127.0.0.1:7503/ingest/36692bbc-7afc-43eb-bc7d-1cc39e5034e1"
    private static let session = "f8bbc4"

    static func log(
        location: String,
        message: String,
        data: [String: Any] = [:],
        hypothesisId: String = "",
        runId: String = "pre-fix"
    ) {
        guard let url = URL(string: endpoint) else { return }
        var payload: [String: Any] = [
            "sessionId": session,
            "location": location,
            "message": message,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            "runId": runId,
            "data": data,
        ]
        if !hypothesisId.isEmpty { payload["hypothesisId"] = hypothesisId }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(session, forHTTPHeaderField: "X-Debug-Session-Id")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req).resume()
    }
}
// #endregion
