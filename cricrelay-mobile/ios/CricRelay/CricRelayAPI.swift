import Foundation

final class CricRelayAPI {
    private(set) var baseUrl = ""
    private(set) var token = ""

    func configure(baseUrl: String, token: String) {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.token = token
    }

    func login(email: String, password: String, baseUrl: String) async throws {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(self.baseUrl)/api/auth/login") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["email": email, "password": password])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.userAuthenticationRequired)
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let newToken = json?["token"] as? String else { throw URLError(.badServerResponse) }
        token = newToken
    }

    func listStreams() async throws -> [StreamItem] {
        guard let url = URL(string: "\(baseUrl)/api/streams") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let rows = json?["streams"] as? [[String: Any]] ?? []
        return rows.compactMap { row in
            guard let slug = row["slug"] as? String else { return nil }
            let label = row["label"] as? String ?? slug
            return StreamItem(slug: slug, label: label)
        }
    }
}
