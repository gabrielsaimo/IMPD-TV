package br.com.impd.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The player URL on impd.org.br is not static: the church's streaming
 * platform (inChurch/inRadar) can rotate it at any time. Rather than trust a
 * value baked into the app, this asks the same API the website itself calls
 * and always plays whatever it currently points to.
 */
object StreamResolver {

    private const val LIVE_ENDPOINT =
        "https://www.inradar.com.br/api/v2/inchurch_channel/home_live/"
    private const val APP_ID = "br.com.inchurch.mundialpoderdeus"

    fun resolveStreamUrl(onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                val connection = URL(LIVE_ENDPOINT).openConnection() as HttpURLConnection
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

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val streamUrl = extractHlsUrl(body)
                    ?: throw IllegalStateException("No HLS channel in response")

                Handler(Looper.getMainLooper()).post { onResult(streamUrl) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onError(e) }
            }
        }.start()
    }

    /** Prefers a live HLS channel; falls back to any channel that has a stream URL at all. */
    private fun extractHlsUrl(body: String): String? {
        val channels = JSONObject(body).optJSONArray("channels") ?: return null
        var fallback: String? = null

        for (i in 0 until channels.length()) {
            val channel = channels.getJSONObject(i)
            val streamUrl = channel.optString("stream_url", "").takeIf { it.isNotBlank() } ?: continue

            if (channel.optString("channel_type") == "hls" && channel.optBoolean("is_live")) {
                return streamUrl
            }
            if (fallback == null) fallback = streamUrl
        }
        return fallback
    }
}
