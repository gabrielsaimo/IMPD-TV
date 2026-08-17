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
     * Every step names a package with `setPackage`, so the video opens in that
     * app and nothing else. That is deliberate: an implicit intent with no
     * package makes the system put up the "Abrir com" chooser whenever two
     * apps can take a YouTube link, and this television is a single-app stick
     * in front of an elderly audience — a dialog nobody expects, drawn in a
     * phone layout on a leanback ROM that may not even focus it with a remote,
     * is a dead end. The chooser is only reached as the very last resort,
     * after every named package has been tried and failed.
     *
     * Launches are attempted, not pre-checked with `resolveActivity()`. Many of
     * the YouTube-for-TV builds that end up on cheap sticks are unofficial
     * repackages that skip the manifest intent-filter a resolve check looks
     * for, even though the app opens the video fine if asked directly; gating
     * on that check meant a perfectly capable player was being skipped as if
     * it were not installed. A failed launch is just a caught exception, same
     * cost as the check would have been, so nothing is lost by trying first.
     *
     * `<queries>` in AndroidManifest is what makes these packages visible to
     * begin with — required since Android 11 for any app targeting API 30+.
     */
    private fun openVideo(context: android.content.Context, video: YoutubeVideo) {
        val watchUriHttps = Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        val watchUriVnd = Uri.parse("vnd.youtube:${video.id}")

        fun viewIntent(uri: Uri, pkg: String?) = Intent(Intent.ACTION_VIEW, uri).apply {
            if (pkg != null) setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // 1. The players we know by name, best to worst for a television.
        for (pkg in KNOWN_PLAYERS) {
            // Some TV boxes only answer vnd.youtube from their built-in app.
            if (start(context, viewIntent(watchUriVnd, pkg))) return
            if (start(context, viewIntent(watchUriHttps, pkg))) return
        }

        // 2. Whatever else this particular box declares for a YouTube link —
        //    covers the repackaged players whose names we cannot know in
        //    advance — still launched by name so no chooser appears.
        for (pkg in handlersFor(context, watchUriHttps)) {
            if (start(context, viewIntent(watchUriVnd, pkg))) return
            if (start(context, viewIntent(watchUriHttps, pkg))) return
        }

        // 3. Nothing named worked: let the system decide, chooser and all.
        if (start(context, viewIntent(watchUriVnd, null))) return
        if (start(context, viewIntent(watchUriHttps, null))) return

        android.widget.Toast.makeText(
            context,
            "Nenhum aplicativo de vídeo encontrado neste aparelho.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Packages on this box that answer a YouTube link, real players first.
     *
     * A browser declares every https address, so it also turns up here; it is
     * pushed to the back by checking which packages answer an unrelated web
     * address too. A YouTube app declares the youtube.com host specifically
     * and so is not in that second set.
     */
    private fun handlersFor(context: android.content.Context, uri: Uri): List<String> = try {
        val pm = context.packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in KNOWN_PLAYERS }
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0
        ).map { it.activityInfo.packageName }.toSet()
        all.filterNot { it in browsers } + all.filter { it in browsers }
    } catch (e: Exception) {
        emptyList()
    }

    private fun start(context: android.content.Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun getItemCount() = videos.size

    private companion object {
        /** Kept in sync with `<queries>` in AndroidManifest, or none of these are visible. */
        val KNOWN_PLAYERS = listOf(
            "com.google.android.youtube.tv",      // YouTube oficial para Android TV
            "com.google.android.youtube",         // YouTube oficial de celular
            "com.teamsmart.videobase",            // SmartTubeNext
            "com.liskovsoft.smarttubetv",         // SmartTubeNext
            "com.liskovsoft.smarttubetv.beta",    // SmartTubeNext Beta
            "com.liskovsoft.videomanager",        // SmartTube antigo
            "app.revanced.android.youtube"        // ReVanced
        )
    }
}
