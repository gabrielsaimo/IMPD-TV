package br.com.impd.tv

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class VideoAdapter(private val videos: List<YoutubeVideo>) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnailCard: androidx.cardview.widget.CardView = view.findViewById(R.id.videoThumbnailCard)
        val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val title: TextView = view.findViewById(R.id.videoTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        val holder = VideoViewHolder(view)

        // Efeito de zoom + elevação ao focar (padrão Android TV)
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                holder.thumbnailCard.cardElevation = 16f
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                holder.thumbnailCard.cardElevation = 0f
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.title.text = video.title
        holder.thumbnail.load(video.thumbnailUrl) {
            // The source is a 1280x720 YouTube thumbnail; decoding it at the
            // 320x180 size it's actually shown at matters on weak TV boxes.
            size(320, 180)
            crossfade(true)
        }
        
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            
            // Lista de pacotes comuns de YouTube em TV Boxes (priorizando o oficial de TV)
            val packagesToTry = listOf(
                "com.google.android.youtube.tv",      // YouTube oficial para Android TV
                "com.google.android.youtube",         // YouTube mobile
                "com.teamsmart.videobase",            // SmartTubeNext
                "com.liskovsoft.smarttubetv.beta"     // SmartTubeNext Beta
            )

            var launched = false
            for (pkg in packagesToTry) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    intent.setPackage(pkg)
                    context.startActivity(intent)
                    launched = true
                    break
                } catch (e: Exception) {
                    // Pacote não encontrado, tenta o próximo
                }
            }

            if (!launched) {
                try {
                    // Fallback 1: Tenta o formato web sem forçar pacote (pode perguntar ao usuário)
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))
                    browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(browserIntent)
                } catch (e: Exception) {
                    try {
                        // Fallback 2: Formato antigo vnd.youtube
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.id}"))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    } catch (e2: Exception) {
                        android.widget.Toast.makeText(context, "Aplicativo do YouTube não encontrado neste aparelho.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun getItemCount() = videos.size
}
