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
     * Hands the video to a player that can actually run on a television.
     *
     * The trap here is the phone build of YouTube: it happily claims the link,
     * then finds no touchscreen, shows a black screen and quits. The box drops
     * back to the home screen, which is this app, so from the sofa it looks
     * like pressing OK did nothing.
     *
     * So a candidate is only used if the system reports a leanback launcher for
     * it — that is the same signal Android TV itself uses to decide whether an
     * app belongs on the television at all. Only when nothing on the box passes
     * does it fall back to whatever will take the link.
     */
    private fun openVideo(context: android.content.Context, video: YoutubeVideo) {
        val watchUri = Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        val packageManager = context.packageManager

        fun isTelevisionApp(pkg: String) =
            packageManager.getLeanbackLaunchIntentForPackage(pkg) != null

        fun viewIntent(uri: Uri, pkg: String?) = Intent(Intent.ACTION_VIEW, uri).apply {
            if (pkg != null) setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Players conhecidos, do melhor para o pior em TV.
        val knownPlayers = listOf(
            "com.google.android.youtube.tv",      // YouTube oficial para Android TV
            "com.teamsmart.videobase",            // SmartTubeNext
            "com.liskovsoft.smarttubetv",         // SmartTubeNext
            "com.liskovsoft.smarttubetv.beta",    // SmartTubeNext Beta
            "com.google.android.youtube"          // YouTube celular, só se rodar em TV
        )

        for (pkg in knownPlayers) {
            if (!isTelevisionApp(pkg)) continue
            val intent = viewIntent(watchUri, pkg)
            if (intent.resolveActivity(packageManager) != null && start(context, intent)) return
        }

        // Qualquer outro app do aparelho que abra o link e seja de televisão.
        val handlers = try {
            packageManager.queryIntentActivities(viewIntent(watchUri, null), 0)
        } catch (e: Exception) {
            emptyList()
        }
        for (handler in handlers) {
            val pkg = handler.activityInfo?.packageName ?: continue
            if (!isTelevisionApp(pkg)) continue
            if (start(context, viewIntent(watchUri, pkg))) return
        }

        // Formato antigo, ainda aceito por builds mais velhas do YouTube.
        val legacy = viewIntent(Uri.parse("vnd.youtube:${video.id}"), null)
        if (legacy.resolveActivity(packageManager) != null && start(context, legacy)) return

        // Nada de televisão no aparelho: deixa o sistema escolher, é melhor que
        // não fazer nada.
        if (start(context, viewIntent(watchUri, null))) return

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
