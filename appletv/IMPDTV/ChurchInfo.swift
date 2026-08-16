import Foundation

/// What the info panel shows about the church.
///
/// The layout is static on purpose: the panel must read the same every time so
/// a viewer who opens it twice sees the same thing in the same place. Only the
/// few values the church actually maintains online — the head office address,
/// the contact e-mail, how many congregations exist — are refreshed from
/// impd.org.br's own API, and each one falls back to the value baked in here
/// so the panel is never half-empty.
struct ChurchInfo: Equatable {
    var headOfficeAddress: String
    var email: String
    var congregationCount: Int
    var heroImageURL: URL?

    static let fallback = ChurchInfo(
        headOfficeAddress: "Rua Carneiro Leão, 439 - Brás, São Paulo - SP, 03040-000",
        email: "contato@impd.org.br",
        congregationCount: 554,
        heroImageURL: nil
    )

    static let name = "Igreja Mundial do Poder de Deus"
    static let tagline = "Fé, milagres e transformação de vidas"
    static let site = "impd.org.br"
    static let about = """
        Fundada em 3 de março de 1998, em Sorocaba, São Paulo, pelo Apóstolo \
        Valdemiro Santiago e pela Bispa Franciléia Santiago.

        Desde então cresceu para centenas de congregações em todo o Brasil, \
        levando a palavra de Deus, oração e acolhimento a milhões de famílias.
        """
}

enum ChurchInfoFetcher {
    private static let apiBase = "https://www.inradar.com.br/api/v2"
    private static let appId = "br.com.inchurch.mundialpoderdeus"

    /// Always returns something: live values where the lookup succeeded, the
    /// baked-in ones everywhere else.
    static func fetch() async -> ChurchInfo {
        var info = ChurchInfo.fallback

        if let root = try? await getJSON(
            "\(apiBase)/tertiary_group/?master_church=true&subgroup__app_id=\(appId)"
        ),
           let objects = root["objects"] as? [[String: Any]],
           let office = objects.first {
            if let address = office["address_full"] as? String {
                // The API suffixes " - Brasil"; the country is not news to anyone watching.
                info.headOfficeAddress = address.replacingOccurrences(
                    of: " - Brasil", with: "", options: [.anchored, .backwards]
                )
            }
            if let email = office["email"] as? String { info.email = email }
        }

        // The congregation count lives on the unfiltered list, not the head-office
        // one, which by definition returns exactly one record.
        if let root = try? await getJSON(
            "\(apiBase)/tertiary_group/?limit=1&subgroup__app_id=\(appId)"
        ),
           let meta = root["meta"] as? [String: Any],
           let total = meta["total_count"] as? Int, total > 0 {
            info.congregationCount = total
        }

        if let root = try? await getJSON("\(apiBase)/inchurch_channel/home_live/"),
           let channels = root["channels"] as? [[String: Any]] {
            for channel in channels {
                if let image = channel["image"] as? String, let url = URL(string: image) {
                    info.heroImageURL = url
                    break
                }
            }
        }

        return info
    }

    private static func getJSON(_ urlString: String) async throws -> [String: Any]? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        request.setValue("application/json;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue("site", forHTTPHeaderField: "Channel")
        request.setValue(appId, forHTTPHeaderField: "appId")
        request.setValue("pt-BR", forHTTPHeaderField: "Accept-language")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}
