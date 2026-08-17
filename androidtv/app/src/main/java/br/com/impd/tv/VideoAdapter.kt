package br.com.impd.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * The video row, with a tile at the end that says what the row is doing.
 *
 * That last tile is not decoration. Pressing right on a television gives no
 * indication of how much is left, so without it the row either sits empty
 * while the feeds are being fetched, or simply stops moving at the last video
 * with no way to tell "that is all there is" apart from "it broke".
 *
 * It is also where a failed load becomes recoverable: the row used to fetch
 * once, and a request that failed left an empty drawer that nothing would ever
 * retry.
 */
class VideoAdapter(
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class State { LOADING, DONE, ERROR }

    private var videos: List<YoutubeVideo> = emptyList()
    private var state: State = State.LOADING

    fun submit(videos: List<YoutubeVideo>, state: State) {
        this.videos = videos
        this.state = state
        notifyDataSetChanged()
    }

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnailCard: androidx.cardview.widget.CardView = view.findViewById(R.id.videoThumbnailCard)
        val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val title: TextView = view.findViewById(R.id.videoTitle)
    }

    class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val spinner: ProgressBar = view.findViewById(R.id.statusSpinner)
        val icon: TextView = view.findViewById(R.id.statusIcon)
        val title: TextView = view.findViewById(R.id.statusTitle)
        val detail: TextView = view.findViewById(R.id.statusDetail)
    }

    /**
     * Which tile is selected has to be readable from a sofa, by someone who is
     * not looking for it. Scale alone was not: a tile slightly larger than its
     * neighbours reads as nothing across a room, and once the row scrolls and
     * the selected tile sits at the edge there is no neighbour left to compare
     * it against. So the selected tile carries a white ring, a lit panel, a
     * brighter title and the zoom, all at once. White rather than a brand
     * colour because the thumbnail underneath is a photograph of anything.
     */
    private fun applyFocusEffect(view: View, hasFocus: Boolean, onCard: (Boolean) -> Unit) {
        val scale = if (hasFocus) 1.10f else 1.0f
        view.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
        onCard(hasFocus)
        // The focused tile is scaled up and must not be painted under its neighbour.
        if (hasFocus) view.bringToFront()
    }

    override fun getItemViewType(position: Int) =
        if (position == videos.size) TYPE_STATUS else TYPE_VIDEO

    /** Always one more than the videos: the status tile closes every row. */
    override fun getItemCount() = videos.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        if (viewType == TYPE_STATUS) {
            val view = inflater.inflate(R.layout.item_video_status, parent, false)
            val holder = StatusViewHolder(view)
            view.setOnFocusChangeListener { v, hasFocus ->
                applyFocusEffect(v, hasFocus) {
                    holder.title.setTextColor(textColor(v, it))
                }
            }
            return holder
        }

        val view = inflater.inflate(R.layout.item_video, parent, false)
        val holder = VideoViewHolder(view)
        view.setOnFocusChangeListener { v, hasFocus ->
            applyFocusEffect(v, hasFocus) { focused ->
                holder.thumbnailCard.cardElevation = if (focused) 16f else 0f
                holder.thumbnailCard.foreground =
                    if (focused) {
                        ContextCompat.getDrawable(v.context, R.drawable.video_focus_border)
                    } else {
                        null
                    }
                holder.title.setTextColor(textColor(v, focused))
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // A recycled tile arrives wearing the selection of whichever tile it was
        // last, and scrolled far enough off screen it never gets the focus
        // change that would take it off.
        if (!holder.itemView.hasFocus()) {
            holder.itemView.scaleX = 1.0f
            holder.itemView.scaleY = 1.0f
        }

        when (holder) {
            is StatusViewHolder -> bindStatus(holder)
            is VideoViewHolder -> bindVideo(holder, videos[position])
        }
    }

    private fun bindVideo(holder: VideoViewHolder, video: YoutubeVideo) {
        holder.title.text = video.title
        holder.thumbnail.load(video.thumbnailUrl) {
            // The source is a 1280x720 YouTube thumbnail; decoding it at the
            // 320x180 size it's actually shown at matters on weak TV boxes.
            size(320, 180)
            crossfade(true)
        }

        if (!holder.itemView.hasFocus()) {
            holder.thumbnailCard.cardElevation = 0f
            holder.thumbnailCard.foreground = null
        }
        holder.title.setTextColor(textColor(holder.itemView, holder.itemView.hasFocus()))

        holder.itemView.setOnClickListener {
            VideoLauncher.open(holder.itemView.context, video)
        }
    }

    private fun bindStatus(holder: StatusViewHolder) {
        val loading = state == State.LOADING
        holder.spinner.visibility = if (loading) View.VISIBLE else View.GONE
        holder.icon.visibility = if (loading) View.GONE else View.VISIBLE

        when (state) {
            State.LOADING -> {
                holder.icon.text = ""
                holder.title.setText(R.string.videos_loading)
                holder.detail.text = ""
            }
            State.DONE -> {
                holder.icon.text = "✓"
                holder.title.setText(R.string.videos_end)
                holder.detail.setText(R.string.videos_end_detail)
            }
            State.ERROR -> {
                holder.icon.text = "↻"
                holder.title.setText(R.string.videos_error)
                holder.detail.setText(R.string.videos_error_detail)
            }
        }

        holder.title.setTextColor(textColor(holder.itemView, holder.itemView.hasFocus()))
        // Only a failed row has anything to press; the other two just report.
        holder.itemView.setOnClickListener(
            if (state == State.ERROR) View.OnClickListener { onRetry() } else null
        )
    }

    private fun textColor(view: View, focused: Boolean) = ContextCompat.getColor(
        view.context,
        if (focused) R.color.text_primary else R.color.text_secondary
    )

    private companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_STATUS = 1
    }
}
