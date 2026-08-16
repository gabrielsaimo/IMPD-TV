import Foundation

/// The player URL on impd.org.br is not static: the church's streaming
/// platform (inChurch/inRadar) can rotate it at any time. Rather than trust a
/// value baked into the app, this asks the same API the website itself calls
/// and always plays whatever it currently points to.
enum StreamResolver {
    private static let liveEndpoint = URL(string:
        "https://www.inradar.com.br/api/v2/inchurch_channel/home_live/")!
    private static let appId = "br.com.inchurch.mundialpoderdeus"

    struct ResolutionFailure: Error {}

    static func resolveStreamURL() async throws -> URL {
        var request = URLRequest(url: liveEndpoint)
        request.httpMethod = "GET"
        request.timeoutInterval = 8
        request.setValue("application/json;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue("site", forHTTPHeaderField: "Channel")
        request.setValue(appId, forHTTPHeaderField: "appId")
        request.setValue("pt-BR", forHTTPHeaderField: "Accept-language")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw ResolutionFailure()
        }

        guard let url = try extractHLSURL(from: data) else {
            throw ResolutionFailure()
        }
        return url
    }

    /// Prefers a live HLS channel; falls back to any channel that has a stream URL at all.
    private static func extractHLSURL(from data: Data) throws -> URL? {
        guard
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let channels = root["channels"] as? [[String: Any]]
        else { return nil }

        var fallback: URL?
        for channel in channels {
            guard
                let streamUrlString = channel["stream_url"] as? String,
                let streamUrl = URL(string: streamUrlString)
            else { continue }

            let isLive = channel["is_live"] as? Bool ?? false
            let type = channel["channel_type"] as? String
            if type == "hls" && isLive {
                return streamUrl
            }
            if fallback == nil { fallback = streamUrl }
        }
        return fallback
    }
}
