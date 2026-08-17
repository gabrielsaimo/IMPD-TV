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
    val published: String
)

object YoutubeFetcher {

    private val CHANNEL_IDS = listOf(
        "UCfb8GIF7etM7HaMmBJ150qg", // Bispo Roberto Santana
        "UCHxVJ4kWtbDbzAwzIJ-_QpA"  // Igreja Mundial Ao Vivo
    )

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

                for (channelId in CHANNEL_IDS) {
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
                                allVideos.add(YoutubeVideo(videoId, title, thumbnailUrl, published))
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
