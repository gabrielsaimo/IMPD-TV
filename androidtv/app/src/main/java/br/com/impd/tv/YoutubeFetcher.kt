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
     * Each feed returns fifteen entries, and every tile costs a thumbnail
     * download and decode. A weak box chokes on thirty of those at once, and
     * nobody scrolls a television that far anyway.
     */
    private const val MAX_VIDEOS = 12

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

                // Ordenar por data de publicação decrescente (mais novos primeiro)
                allVideos.sortByDescending { it.published }
                val videos = allVideos.take(MAX_VIDEOS)

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
