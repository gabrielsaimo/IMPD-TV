package br.com.impd.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class YoutubeVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val published: String,
    /** Nome do canal, mostrado embaixo do título do bloco. */
    val channel: String,
    /** "16 ago", já formatado, para o bloco não formatar data ao rolar. */
    val dateLabel: String
)

object YoutubeFetcher {

    /**
     * Lista de reserva, e só isso. As fontes de verdade vivem no painel e
     * chegam pelo [Telemetry]: incluir um canal novo é uma linha no painel, e
     * não um aplicativo novo em milhares de televisões.
     *
     * Esta cópia existe para o primeiro instante depois de ligar, antes de a
     * consulta voltar, e para o dia em que o painel estiver fora do ar. Uma
     * fileira vazia é pior que uma fileira desatualizada.
     */
    private val FALLBACK_CHANNELS = listOf(
        "UCfb8GIF7etM7HaMmBJ150qg" to "Bispo Roberto Santana",
        "UCHxVJ4kWtbDbzAwzIJ-_QpA" to "Igreja Mundial Ao Vivo"
    )

    private fun channels(): List<Pair<String, String>> =
        Telemetry.channels.takeIf { it.isNotEmpty() } ?: FALLBACK_CHANNELS

    /**
     * O feed traz o carimbo em ISO 8601. Formatar aqui, uma vez por vídeo, e
     * não no adaptador: lá isso rodaria de novo a cada bloco que entra na tela
     * enquanto a fileira rola, que é justamente quando a box não tem sobra.
     */
    private fun dateLabelFor(published: String): String = try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val date = parser.parse(published.take(10))
        if (date == null) {
            ""
        } else {
            java.text.SimpleDateFormat("dd MMM", java.util.Locale("pt", "BR"))
                .format(date)
                .replace(".", "")
        }
    } catch (e: Exception) {
        ""
    }

    /**
     * No cap: the row runs to the end of what the feeds carry, which is the
     * fifteen most recent entries per channel and nothing more — that limit is
     * YouTube's, not ours, and reaching for older videos would mean an API key.
     *
     * Holding every entry costs almost nothing. A tile is only expensive when
     * it is on screen: RecyclerView keeps a handful of views alive and Coil
     * only fetches a thumbnail as its tile scrolls into view, so a weak box
     * pays for what the viewer actually looks at, not for the whole list.
     */

    /**
     * Pede a fileira ao painel da igreja, e não ao YouTube.
     *
     * Cada televisão buscava o feed RSS por conta própria até esse endpoint do
     * YouTube sair do ar — passou a responder 404 até para o canal oficial do
     * próprio YouTube, e a fileira parou em todas as salas de uma vez.
     *
     * Com a busca do outro lado, três coisas melhoram: mil aparelhos deixam de
     * bater no YouTube mil vezes, nenhuma credencial precisa viajar dentro do
     * APK, e o dia em que o YouTube mudar de novo o conserto é um deploy — não
     * uma atualização em cada televisão do país.
     */
    fun fetchLatestVideos(onSuccess: (List<YoutubeVideo>) -> Unit, onError: (Exception) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val base = Telemetry.baseUrl()
                if (base.isBlank()) throw IllegalStateException("sem painel configurado")

                connection = (URL("$base/videos").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }

                val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val arr = body.optJSONArray("videos")
                val videos = mutableListOf<YoutubeVideo>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optJSONObject(i) ?: continue
                        val id = v.optString("id", "")
                        val title = v.optString("title", "")
                        if (id.isBlank() || title.isBlank()) continue
                        videos.add(
                            YoutubeVideo(
                                id = id,
                                title = title,
                                thumbnailUrl = v.optString(
                                    "thumbnailUrl",
                                    "https://img.youtube.com/vi/$id/hqdefault.jpg"
                                ),
                                published = v.optString("published", ""),
                                channel = v.optString("channel", ""),
                                dateLabel = v.optString("dateLabel", "")
                            )
                        )
                    }
                }

                if (videos.isEmpty()) {
                    Telemetry.sourceFailed(
                        "videos",
                        body.optString("reason", "").takeIf { it.isNotBlank() } ?: "lista vazia"
                    )
                }

                Handler(Looper.getMainLooper()).post { onSuccess(videos) }
            } catch (e: Exception) {
                e.printStackTrace()
                Telemetry.sourceFailed("videos", "fileira de vídeos")
                Handler(Looper.getMainLooper()).post { onError(e) }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

}
