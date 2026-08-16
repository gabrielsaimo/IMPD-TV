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
        
        holder.itemView.setOnClickListener { openVideo(holder.itemView.context, video) }
    }

    /**
     * Hands the video to whichever player the box actually has.
     *
     * Cheap Android TV sticks — this app's audience — often run a vendor
     * launcher instead of the real Android TV/Google TV shell, and none of
     * their apps declare a leanback launcher at all, working YouTube included.
     * Requiring one, as an earlier version of this method did, rejected every
     * app on exactly those boxes and broke playback entirely. So the only
     * requirement here is that the package can resolve the intent — checked
     * with the package manager rather than launched blind, because since
     * Android 11 a target-SDK-30+ app cannot even see another app is
     * installed unless it is declared in AndroidManifest's `<queries>`.
     */
    private fun openVideo(context: android.content.Context, video: YoutubeVideo) {
        val watchUri = Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        val packageManager = context.packageManager

        fun viewIntent(uri: Uri, pkg: String?) = Intent(Intent.ACTION_VIEW, uri).apply {
            if (pkg != null) setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Do melhor para o pior em TV; o de celular por último, mas ainda tentado.
        val knownPlayers = listOf(
            "com.google.android.youtube.tv",      // YouTube oficial para Android TV
            "com.teamsmart.videobase",            // SmartTubeNext
            "com.liskovsoft.smarttubetv",         // SmartTubeNext
            "com.liskovsoft.smarttubetv.beta",    // SmartTubeNext Beta
            "com.google.android.youtube"          // YouTube celular
        )

        for (pkg in knownPlayers) {
            val intent = viewIntent(watchUri, pkg)
            if (intent.resolveActivity(packageManager) != null && start(context, intent)) return
        }

        // Nenhum dos conhecidos: deixa o sistema escolher entre o que estiver instalado.
        val open = viewIntent(watchUri, null)
        if (open.resolveActivity(packageManager) != null && start(context, open)) return

        // Formato antigo, ainda aceito por builds mais velhas do YouTube.
        val legacy = viewIntent(Uri.parse("vnd.youtube:${video.id}"), null)
        if (legacy.resolveActivity(packageManager) != null && start(context, legacy)) return

        android.widget.Toast.makeText(
            context,
            "Nenhum aplicativo de vídeo encontrado neste aparelho.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /** resolveActivity can still go stale between the check and the launch. */
    private fun start(context: android.content.Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun getItemCount() = videos.size
}
