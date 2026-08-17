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
 * A fileira de vídeos, com um bloco no fim que diz o que ela está fazendo.
 *
 * Esse último bloco não é enfeite. Apertar para a direita numa televisão não
 * dá nenhuma noção de quanto falta, então sem ele a fileira ou fica vazia
 * enquanto os feeds são buscados, ou simplesmente para de andar no último
 * vídeo, sem como distinguir "acabou" de "quebrou". É também onde uma busca
 * que falhou volta a ser recuperável.
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
        val date: TextView = view.findViewById(R.id.videoDate)
        val channel: TextView = view.findViewById(R.id.videoChannel)
    }

    class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val spinner: ProgressBar = view.findViewById(R.id.statusSpinner)
        val icon: TextView = view.findViewById(R.id.statusIcon)
        val title: TextView = view.findViewById(R.id.statusTitle)
        val detail: TextView = view.findViewById(R.id.statusDetail)
    }

    /**
     * Qual bloco está selecionado tem de ser legível do sofá, por alguém que
     * não está procurando. Só a escala não era: um bloco pouco maior que os
     * vizinhos não se lê do outro lado da sala, e assim que a fileira rola e o
     * selecionado vai para a ponta não sobra vizinho com que comparar. Por
     * isso ele carrega anel, painel aceso, título mais claro e zoom, tudo
     * junto. Branco e não azul da marca porque atrás está uma fotografia de
     * qualquer coisa.
     */
    private fun applyFocus(view: View, hasFocus: Boolean, extra: (Boolean) -> Unit) {
        val scale = if (hasFocus) 1.10f else 1.0f
        view.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
        extra(hasFocus)
        // O bloco em foco está ampliado e não pode ser pintado por baixo do vizinho.
        if (hasFocus) view.bringToFront()
    }

    override fun getItemViewType(position: Int) =
        if (position == videos.size) TYPE_STATUS else TYPE_VIDEO

    /** Sempre um a mais que os vídeos: o bloco de estado fecha toda fileira. */
    override fun getItemCount() = videos.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        if (viewType == TYPE_STATUS) {
            val view = inflater.inflate(R.layout.item_video_status, parent, false)
            val holder = StatusViewHolder(view)
            view.setOnFocusChangeListener { v, hasFocus ->
                applyFocus(v, hasFocus) { holder.title.setTextColor(titleColor(v, it)) }
            }
            return holder
        }

        val view = inflater.inflate(R.layout.item_video, parent, false)
        val holder = VideoViewHolder(view)
        view.setOnFocusChangeListener { v, hasFocus ->
            applyFocus(v, hasFocus) { focused ->
                holder.thumbnailCard.cardElevation = if (focused) 16f else 0f
                holder.thumbnailCard.foreground =
                    if (focused) {
                        ContextCompat.getDrawable(v.context, R.drawable.video_focus_border)
                    } else {
                        null
                    }
                holder.title.setTextColor(titleColor(v, focused))
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Um bloco reciclado chega vestindo a seleção do bloco que era antes, e
        // rolado longe o bastante nunca recebe o evento de foco que a tiraria.
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
        holder.channel.text = video.channel
        holder.date.text = video.dateLabel
        holder.date.visibility = if (video.dateLabel.isEmpty()) View.GONE else View.VISIBLE

        holder.thumbnail.load(video.thumbnailUrl) {
            // A origem é uma miniatura do YouTube de 1280x720; decodificá-la no
            // tamanho em que ela realmente aparece importa numa box fraca.
            size(224, 126)
            crossfade(true)
        }

        val focused = holder.itemView.hasFocus()
        if (!focused) {
            holder.thumbnailCard.cardElevation = 0f
            holder.thumbnailCard.foreground = null
        }
        holder.title.setTextColor(titleColor(holder.itemView, focused))

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

        holder.title.setTextColor(titleColor(holder.itemView, holder.itemView.hasFocus()))
        // Só uma fileira que falhou tem o que apertar; as outras duas relatam.
        holder.itemView.setOnClickListener(
            if (state == State.ERROR) View.OnClickListener { onRetry() } else null
        )
    }

    private fun titleColor(view: View, focused: Boolean) = ContextCompat.getColor(
        view.context,
        if (focused) R.color.text_primary else R.color.text_secondary
    )

    private companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_STATUS = 1
    }
}
