package br.com.impd.tv

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

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

    private val CHANNELS = listOf(
        "UCfb8GIF7etM7HaMmBJ150qg" to "Bispo Roberto Santana",
        "UCHxVJ4kWtbDbzAwzIJ-_QpA" to "Igreja Mundial Ao Vivo"
    )

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

    fun fetchLatestVideos(onSuccess: (List<YoutubeVideo>) -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                val allVideos = mutableListOf<YoutubeVideo>()
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()

                for ((channelId, channelName) in CHANNELS) {
                    val url = URL("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val document = builder.parse(connection.inputStream)
                        val entries = document.getElementsByTagName("entry")
                        
                        for (i in 0 until entries.length) {
                            val node = entries.item(i)
                            var videoId = ""
                            var title = ""
                            var published = ""
                            
                            val childNodes = node.childNodes
                            for (j in 0 until childNodes.length) {
                                val child = childNodes.item(j)
                                if (child.nodeName == "yt:videoId") {
                                    videoId = child.textContent ?: ""
                                }
                                if (child.nodeName == "title") {
                                    title = child.textContent ?: ""
                                }
                                if (child.nodeName == "published") {
                                    published = child.textContent ?: ""
                                }
                            }
                            
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                // hqdefault, not maxresdefault: the latter 404s for any video
                                // that was never uploaded in HD, leaving a blank tile.
                                val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                                allVideos.add(
                                    YoutubeVideo(
                                        videoId, title, thumbnailUrl, published,
                                        channelName, dateLabelFor(published)
                                    )
                                )
                            }
                        }
                    }
                    connection.disconnect()
                }

                // Ordenar por data de publicação decrescente (mais novos primeiro).
                // O carimbo é ISO 8601, então ordenar o texto já ordena a data.
                allVideos.sortByDescending { it.published }
                // Uma transmissão que sai nos dois canais viria duas vezes.
                val videos = allVideos.distinctBy { it.id }

                Handler(Looper.getMainLooper()).post {
                    onSuccess(videos)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    onError(e)
                }
            }
        }.start()
    }
}
