package br.com.impd.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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

        /*
         * Which tile is selected has to be readable from a sofa, by someone who
         * is not looking for it. Scale alone was not: a tile 5% larger than its
         * neighbours reads as nothing at all across a room, and there is no
         * second cue once the row scrolls and the enlarged tile sits at the
         * edge with nothing beside it to compare against.
         *
         * So the selected tile carries four cues at once — a white ring on the
         * thumbnail, a lit panel behind the whole tile, a brighter title, and
         * the zoom. Ring and panel are white rather than a brand colour because
         * the thumbnail underneath is a photograph of anything at all.
         */
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.10f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
            holder.thumbnailCard.cardElevation = if (hasFocus) 16f else 0f
            holder.thumbnailCard.foreground =
                if (hasFocus) {
                    ContextCompat.getDrawable(v.context, R.drawable.video_focus_border)
                } else {
                    null
                }
            holder.title.setTextColor(
                ContextCompat.getColor(
                    v.context,
                    if (hasFocus) R.color.text_primary else R.color.text_secondary
                )
            )
            // The focused tile is scaled up and must not be painted under the
            // one next to it.
            if (hasFocus) v.bringToFront()
        }

        return holder
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.title.text = video.title

        // A recycled tile arrives wearing the selection of whichever tile it
        // was last, and scrolled far enough off screen it never gets the focus
        // change that would take it off.
        if (!holder.itemView.hasFocus()) {
            holder.itemView.scaleX = 1.0f
            holder.itemView.scaleY = 1.0f
            holder.thumbnailCard.cardElevation = 0f
            holder.thumbnailCard.foreground = null
            holder.title.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
            )
        }

        holder.thumbnail.load(video.thumbnailUrl) {
            // The source is a 1280x720 YouTube thumbnail; decoding it at the
            // 320x180 size it's actually shown at matters on weak TV boxes.
            size(320, 180)
            crossfade(true)
        }
        
        holder.itemView.setOnClickListener {
            VideoLauncher.open(holder.itemView.context, video)
        }
    }

    override fun getItemCount() = videos.size
}
