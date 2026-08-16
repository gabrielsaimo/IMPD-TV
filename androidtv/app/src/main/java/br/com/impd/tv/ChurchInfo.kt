package br.com.impd.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the info panel shows about the church.
 *
 * The layout is static on purpose: the panel must read the same every time so
 * a viewer who opens it twice sees the same thing in the same place. Only the
 * few values that the church actually maintains online — the head office
 * address, the contact e-mail, how many congregations exist — are refreshed
 * from impd.org.br's own API, and each one falls back to the value baked in
 * here so the panel is never half-empty.
 */
data class ChurchInfo(
    val headOfficeName: String,
    val headOfficeAddress: String,
    val email: String,
    val congregationCount: Int,
    val heroImageUrl: String?
) {
    companion object {
        val fallback = ChurchInfo(
            headOfficeName = "(SEDE) BRÁS, SÃO PAULO - SP",
            headOfficeAddress = "Rua Carneiro Leão, 439 - Brás, São Paulo - SP, 03040-000",
            email = "contato@impd.org.br",
            congregationCount = 554,
            heroImageUrl = null
        )
    }
}

object ChurchInfoFetcher {

    private const val API_BASE = "https://www.inradar.com.br/api/v2"
    private const val APP_ID = "br.com.inchurch.mundialpoderdeus"

    /**
     * Always calls back, with live values where the lookup succeeded and the
     * baked-in ones everywhere else.
     */
    fun fetch(onResult: (ChurchInfo) -> Unit) {
        Thread {
            var info = ChurchInfo.fallback

            try {
                val headOffice = getJson(
                    "$API_BASE/tertiary_group/?master_church=true&subgroup__app_id=$APP_ID"
                )
                val office = headOffice.optJSONArray("objects")?.optJSONObject(0)
                if (office != null) {
                    info = info.copy(
                        headOfficeName = office.optString("name", info.headOfficeName),
                        // The API suffixes " - Brasil"; the country is not news to anyone watching.
                        headOfficeAddress = office.optString("address_full", info.headOfficeAddress)
                            .removeSuffix(" - Brasil"),
                        email = office.optString("email", info.email)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // The congregation count lives on the unfiltered list, not the head-office one,
            // which by definition returns exactly one record.
            try {
                val all = getJson("$API_BASE/tertiary_group/?limit=1&subgroup__app_id=$APP_ID")
                val total = all.optJSONObject("meta")?.optInt("total_count", 0) ?: 0
                if (total > 0) info = info.copy(congregationCount = total)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val live = getJson("$API_BASE/inchurch_channel/home_live/")
                val channels = live.optJSONArray("channels")
                if (channels != null) {
                    for (i in 0 until channels.length()) {
                        val image = channels.getJSONObject(i).optString("image", "")
                        if (image.isNotBlank()) {
                            info = info.copy(heroImageUrl = image)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val result = info
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            connection.setRequestProperty("Channel", "site")
            connection.setRequestProperty("appId", APP_ID)
            connection.setRequestProperty("Accept-language", "pt-BR")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
