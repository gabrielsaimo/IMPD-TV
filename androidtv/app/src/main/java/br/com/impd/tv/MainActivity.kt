package br.com.impd.tv

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.min
import kotlin.math.pow

/**
 * The whole app: one live channel, full screen, no menus.
 *
 * The audience is mostly elderly, so there is nothing to learn and nothing to
 * break: the channel just plays, always live. No pause, no menus, nothing can
 * be navigated into or misconfigured, and a dropped signal recovers on its own
 * instead of asking anyone to do something about it.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var overlayTop: View
    private lateinit var statusCard: View
    private lateinit var statusTitle: android.widget.TextView
    private lateinit var statusDetail: android.widget.TextView
    
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var pixQrCodes: List<ImageView>
    private lateinit var pixKeyTexts: List<android.widget.TextView>

    private lateinit var bottomDrawer: View
    private lateinit var youtubeRecyclerView: RecyclerView
    private lateinit var youtubeAdapter: VideoAdapter
    private lateinit var videosCount: android.widget.TextView
    private var isYoutubeLoaded = false
    private var isYoutubeLoading = false
    
    // Prayer request drawer
    private lateinit var qrCodePrayer: ImageView
    private lateinit var prayerSubtitle: android.widget.TextView
    private lateinit var prayerScanHint: android.widget.TextView
    private lateinit var prayerNumber: android.widget.TextView
    private lateinit var prayerNumber2: android.widget.TextView

    // Church info panel
    private lateinit var infoPanel: View
    private lateinit var infoHero: ImageView
    private lateinit var infoAddress: android.widget.TextView
    private lateinit var infoEmail: android.widget.TextView
    private lateinit var infoCongregations: android.widget.TextView
    private lateinit var infoQrSite: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { setOverlaysVisible(false) }
    private var retryCount = 0

    /** Last stream URL that actually loaded; used as a fallback if a re-resolve fails mid-retry. */
    private var lastKnownStreamUrl: String? = null

    /** Guards against a resolver callback landing after onPause released the player. */
    private var isResumed = false

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * Sending someone to the system Wi-Fi screen is the one thing in this app
     * that takes the television away from them, so it happens at most once per
     * launch: on a box that has never been connected, or one that was moved to
     * a place where its network is gone. Everything after that recovers on its
     * own — [networkCallback] resumes playback the moment a network appears,
     * with nobody having to press anything.
     */
    private var hasOfferedWifiSettings = false
    private var networkCallbackRegistered = false

    /**
     * Se a transmissão já tocou uma vez, este aparelho tem rede, e nenhuma
     * queda posterior pode mandar ninguém para os ajustes de Wi-Fi.
     *
     * Isto existe porque a garantia de "no máximo uma vez" estava furada: o
     * sinalizador era rearmado a cada STATE_READY, ou seja, toda vez que o
     * vídeo voltava. Numa conexão instável isso vira um ciclo — toca, rearma,
     * cai, abre o Wi-Fi, o espectador volta, toca, rearma — e a televisão é
     * arrancada da pessoa de minuto em minuto, exatamente o que o código dizia
     * estar evitando.
     */
    private var hasEverPlayed = false

    private val retryStream = Runnable { loadStream() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (!isResumed) return@post
                // A network came back: stop counting failures and try immediately
                // rather than waiting out the current backoff.
                retryCount = 0
                handler.removeCallbacks(retryStream)
                loadStream()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        overlayTop = findViewById(R.id.overlay_top)
        statusCard = findViewById(R.id.status)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        
        drawerLayout = findViewById(R.id.drawer_layout)
        pixQrCodes = listOf(findViewById(R.id.qr_code_1), findViewById(R.id.qr_code_2))
        pixKeyTexts = listOf(findViewById(R.id.pix_text_1), findViewById(R.id.pix_text_2))

        bottomDrawer = findViewById(R.id.bottom_drawer)
        videosCount = findViewById(R.id.videos_count)
        youtubeRecyclerView = findViewById(R.id.youtube_recycler_view)
        youtubeRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        youtubeRecyclerView.setHasFixedSize(true)
        // Attached empty from the start so the row can show that it is loading
        // instead of opening as a blank strip with nothing in it.
        youtubeAdapter = VideoAdapter(onRetry = { loadYoutubeVideos() })
        youtubeRecyclerView.adapter = youtubeAdapter
        bottomDrawer.visibility = View.GONE
        
        qrCodePrayer = findViewById(R.id.qr_code_whatsapp)
        prayerSubtitle = findViewById(R.id.prayer_subtitle)
        prayerScanHint = findViewById(R.id.prayer_scan_hint)
        prayerNumber = findViewById(R.id.whatsapp_number_text)
        prayerNumber2 = findViewById(R.id.prayer_number_2)

        infoPanel = findViewById(R.id.info_panel)
        infoHero = findViewById(R.id.info_hero)
        infoAddress = findViewById(R.id.info_address)
        infoEmail = findViewById(R.id.info_email)
        infoCongregations = findViewById(R.id.info_congregations)
        infoQrSite = findViewById(R.id.info_qr_site)
        infoPanel.visibility = View.GONE

        showStatus(getString(R.string.starting_title), getString(R.string.starting_detail))

        setupPixDrawer()
        setupChurchInfo()
    }

    /**
     * This activity is the television's home screen: nobody "reopens" it in
     * the way a normal app gets reopened, so onCreate can go days without
     * running again. onResume is what actually fires — every time the box
     * wakes up, and every time the viewer comes back from a YouTube video or
     * the Wi-Fi screen — so that is where the update check lives, on a loop
     * that keeps checking for as long as the app stays on screen.
     */
    private val checkForUpdates: Runnable = Runnable {
        UpdateManager.checkForUpdates(this)
        handler.postDelayed(checkForUpdates, UPDATE_CHECK_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        registerNetworkCallback()
        initializePlayer()
        // Coming back this fast means a video player quit on its own instead of
        // playing; VideoLauncher moves on to the next candidate on the box.
        VideoLauncher.onHostResumed(this)
        handler.removeCallbacks(checkForUpdates)
        handler.post(checkForUpdates)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        networkCallbackRegistered = false
    }

    private fun setupPixDrawer() {
        val usesWhatsapp = Contact.WHATSAPP != null

        // The panels read correctly before any QR code finishes rendering.
        prayerSubtitle.setText(
            if (usesWhatsapp) R.string.prayer_subtitle_whatsapp else R.string.prayer_subtitle
        )
        prayerScanHint.setText(
            if (usesWhatsapp) R.string.prayer_scan_hint_whatsapp else R.string.prayer_scan_hint
        )
        prayerNumber.text = Contact.formatBr(Contact.WHATSAPP ?: Contact.PRAYER_PHONE)
        prayerNumber2.text = if (usesWhatsapp) "" else Contact.formatBr(Contact.PRAYER_PHONE_2)
        prayerNumber2.visibility = if (usesWhatsapp) View.GONE else View.VISIBLE

        // As chaves aparecem escritas antes de qualquer QR terminar de ser
        // gerado: quem for digitar no aplicativo do banco não espera desenho.
        pixKeyTexts.forEachIndexed { i, view ->
            view.text = Contact.PIX_KEYS.getOrNull(i) ?: ""
        }

        // Gera QR Codes assincronamente
        Thread {
            val pixBitmaps = Contact.PIX_KEYS.map { key ->
                QrCodeUtils.generateQrCodeWithIcon(
                    this, QrCodeUtils.generatePixPayload(key), 300, 300, R.mipmap.ic_launcher
                )
            }

            val prayerIcon = if (usesWhatsapp) R.drawable.ic_whatsapp else R.mipmap.ic_launcher
            val bitmapPrayer = QrCodeUtils.generateQrCodeWithIcon(
                this, Contact.prayerQrPayload(), 300, 300, prayerIcon
            )

            handler.post {
                pixBitmaps.forEachIndexed { i, bitmap ->
                    if (bitmap != null) pixQrCodes.getOrNull(i)?.setImageBitmap(bitmap)
                }
                if (bitmapPrayer != null) qrCodePrayer.setImageBitmap(bitmapPrayer)
            }
        }.start()
    }

    /**
     * The panel is filled in once, at startup, so pressing up never waits on
     * the network: it always has the fallback text on screen already and only
     * swaps in fresher values if the lookup comes back.
     */
    private fun setupChurchInfo() {
        applyChurchInfo(ChurchInfo.fallback)
        // Held back so three extra HTTP calls do not compete with the stream for
        // a weak box's network and CPU during the first seconds after launch.
        // Nothing waits on it: the panel already reads correctly without it.
        handler.postDelayed({ ChurchInfoFetcher.fetch { applyChurchInfo(it) } }, 12_000)

        Thread {
            val bitmap = QrCodeUtils.generateQrCodeWithIcon(
                this, "https://impd.org.br", 280, 280, R.mipmap.ic_launcher
            )
            handler.post { if (bitmap != null) infoQrSite.setImageBitmap(bitmap) }
        }.start()
    }

    private fun applyChurchInfo(info: ChurchInfo) {
        infoAddress.text = info.headOfficeAddress
        infoEmail.text = info.email
        infoCongregations.text =
            getString(R.string.info_congregations_format, info.congregationCount)
        info.heroImageUrl?.let { url ->
            infoHero.load(url) {
                // The source is 306x267; asking for more only wastes memory.
                size(320, 320)
                crossfade(true)
                placeholder(R.drawable.banner)
                error(R.drawable.banner)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retryCount = 0
                    // Um aparelho que já tocou está conectado. O que vier depois
                    // é queda passageira, e não pode mais tirar a televisão de
                    // quem está assistindo.
                    hasEverPlayed = true
                    hideStatus()
                    revealBanner()
                }
                Player.STATE_BUFFERING -> Unit
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            reconnect()
        }
    }

    /**
     * Backs off a little further on each failure, capped so it never gives up.
     * Re-resolves the stream URL on every attempt rather than retrying the
     * same one, since the outage itself may be the streaming host having
     * moved the file.
     */
    private fun reconnect() {
        if (hasNetworkConnection()) {
            showStatus(getString(R.string.offline_title), getString(R.string.offline_detail))
        } else {
            showStatus(getString(R.string.no_network_title), getString(R.string.no_network_detail))
            offerWifiSettingsOnce()
        }
        retryCount++
        val delay = min(1.6.pow(retryCount.toDouble()) * 1000, 15_000.0).toLong()
        handler.removeCallbacks(retryStream)
        handler.postDelayed(retryStream, delay)
    }

    private fun hasNetworkConnection(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isConnected == true
    }

    /**
     * Opens the system Wi-Fi screen, but only the first time this launch: a box
     * fresh out of the box, or one that was carried to a new room, needs a
     * human to pick a network once. Repeating it would yank the television away
     * from the viewer over and over on a flaky connection.
     */
    private fun offerWifiSettingsOnce() {
        // Só um aparelho que nunca conseguiu tocar precisa de uma pessoa. Todo
        // o resto se recupera sozinho, e o networkCallback retoma a
        // transmissão no instante em que a rede volta.
        if (hasEverPlayed || hasOfferedWifiSettings) return
        hasOfferedWifiSettings = true
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    // MARK: - Remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isBottomDrawerOpen = bottomDrawer.visibility == View.VISIBLE
        val isEndDrawerOpen = drawerLayout.isDrawerOpen(GravityCompat.END)
        val isStartDrawerOpen = drawerLayout.isDrawerOpen(GravityCompat.START)
        val isInfoPanelOpen = infoPanel.visibility == View.VISIBLE
        val isAnyDrawerOpen = isBottomDrawerOpen || isEndDrawerOpen || isStartDrawerOpen

        // The info panel covers the screen: while it is up, the navigation keys
        // only close it, so no other panel can open behind it. Volume and power
        // still belong to the television and are passed straight through.
        if (isInfoPanelOpen) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    hideInfoPanel()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isBottomDrawerOpen) {
                    super.onKeyDown(keyCode, event)
                } else if (!isAnyDrawerOpen) {
                    setOverlaysVisible(false)
                    handler.removeCallbacks(hideBanner)
                    bottomDrawer.visibility = View.VISIBLE
                    youtubeRecyclerView.requestFocus()
                    if (!isYoutubeLoaded) loadYoutubeVideos()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isBottomDrawerOpen) {
                    bottomDrawer.visibility = View.GONE
                    playerView.requestFocus()
                    true
                } else if (!isAnyDrawerOpen) {
                    showInfoPanel()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isEndDrawerOpen) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    true
                } else if (isStartDrawerOpen) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                } else if (isBottomDrawerOpen) {
                    bottomDrawer.visibility = View.GONE
                    playerView.requestFocus()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isStartDrawerOpen) {
                    // A gaveta da esquerda abre com ◀ e tem de fechar com ▶, que
                    // é o que o rodapé dela promete. Antes a tecla caía no
                    // super e não fazia nada: só o BACK fechava.
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                } else if (isBottomDrawerOpen) {
                    // Let the list navigate items horizontally
                    super.onKeyDown(keyCode, event)
                } else if (!isEndDrawerOpen && !isAnyDrawerOpen) {
                    setOverlaysVisible(false)
                    handler.removeCallbacks(hideBanner)
                    drawerLayout.openDrawer(GravityCompat.END)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isBottomDrawerOpen) {
                    // Let the list navigate items horizontally
                    super.onKeyDown(keyCode, event)
                } else if (isEndDrawerOpen) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    true
                } else if (!isStartDrawerOpen && !isAnyDrawerOpen) {
                    setOverlaysVisible(false)
                    handler.removeCallbacks(hideBanner)
                    drawerLayout.openDrawer(GravityCompat.START)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                revealBanner()
                true
            }
            else -> {
                revealBanner()
                super.onKeyDown(keyCode, event)
            }
        }
    }

    /**
     * The row reports its own state at the end of the list, so a failure is
     * something the viewer can see and press again rather than a toast that
     * disappears and leaves an empty strip behind forever.
     */
    private fun loadYoutubeVideos() {
        if (isYoutubeLoading) return
        isYoutubeLoading = true
        youtubeAdapter.submit(emptyList(), VideoAdapter.State.LOADING)

        YoutubeFetcher.fetchLatestVideos(
            onSuccess = { videos ->
                isYoutubeLoading = false
                if (videos.isEmpty()) {
                    youtubeAdapter.submit(emptyList(), VideoAdapter.State.ERROR)
                } else {
                    isYoutubeLoaded = true
                    youtubeAdapter.submit(videos, VideoAdapter.State.DONE)
                    videosCount.text =
                        getString(R.string.videos_count_format, videos.size)
                    videosCount.visibility = View.VISIBLE
                }
                youtubeRecyclerView.requestFocus()
            },
            onError = {
                isYoutubeLoading = false
                youtubeAdapter.submit(emptyList(), VideoAdapter.State.ERROR)
                youtubeRecyclerView.requestFocus()
            }
        )
    }

    // MARK: - Overlays

    /**
     * A faixa ao vivo e as dicas de tecla andam juntas: as duas flutuam sobre
     * o vídeo, e as duas somem no instante em que qualquer painel abre. As
     * dicas ficam na base da tela, exatamente onde a gaveta de vídeos sobe, e
     * uma gaveta opaca por cima delas seria a única coisa neste app desenhada
     * em cima de outra.
     */
    private fun setOverlaysVisible(visible: Boolean) {
        overlayTop.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun isAnyPanelOpen() =
        bottomDrawer.visibility == View.VISIBLE ||
            infoPanel.visibility == View.VISIBLE ||
            statusCard.visibility == View.VISIBLE ||
            drawerLayout.isDrawerOpen(GravityCompat.START) ||
            drawerLayout.isDrawerOpen(GravityCompat.END)

    private fun revealBanner() {
        // Com um painel aberto não há vídeo à mostra para legendar.
        if (isAnyPanelOpen()) return
        setOverlaysVisible(true)
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 6_000)
    }

    private fun showInfoPanel() {
        setOverlaysVisible(false)
        handler.removeCallbacks(hideBanner)
        infoPanel.visibility = View.VISIBLE
        infoPanel.requestFocus()
    }

    private fun hideInfoPanel() {
        infoPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun showStatus(title: String, detail: String) {
        statusTitle.text = title
        statusDetail.text = detail
        statusCard.visibility = View.VISIBLE
        setOverlaysVisible(false)
    }

    private fun hideStatus() {
        statusCard.visibility = View.GONE
    }

    private fun initializePlayer() {
        // onPause releases the player, which leaves it in STATE_IDLE and unusable
        // for a fresh prepare(); a released instance is always rebuilt, never reused.
        if (!::player.isInitialized || player.playbackState == Player.STATE_IDLE) {
            // Weak set-top boxes are memory constrained: this is a live-only
            // channel, so nothing is ever seeked backward, and the back
            // buffer is dropped entirely instead of holding onto old segments.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
                .setBackBuffer(0, false)
                .build()
            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build().apply { addListener(playerListener) }
            playerView.player = player
            playerView.useController = false
            loadStream()
        } else {
            player.playWhenReady = true
        }
    }

    /** Always fetches the current stream URL from impd.org.br before playing: it can change. */
    private fun loadStream() {
        if (!hasNetworkConnection()) {
            // No point resolving or preparing anything. The network callback
            // restarts this the moment a connection appears.
            showStatus(getString(R.string.no_network_title), getString(R.string.no_network_detail))
            offerWifiSettingsOnce()
            handler.removeCallbacks(retryStream)
            handler.postDelayed(retryStream, 5_000)
            return
        }

        StreamResolver.resolveStreamUrl(
            onResult = { url ->
                lastKnownStreamUrl = url
                playUrl(url)
            },
            onError = {
                val fallback = lastKnownStreamUrl ?: Channel.FALLBACK_STREAM
                playUrl(fallback)
            }
        )
    }

    private fun playUrl(url: String) {
        // The resolver call is async and can outlive onPause; a released player must not be touched.
        if (!isResumed || !::player.isInitialized) return
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
    }

    private fun releasePlayer() {
        // Only playback callbacks are dropped here. Clearing the whole queue
        // would also throw away the QR code and church info hand-offs that
        // background threads post back to the UI.
        handler.removeCallbacks(retryStream)
        handler.removeCallbacks(hideBanner)
        if (::player.isInitialized) {
            playerView.player = null
            player.release()
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        unregisterNetworkCallback()
        handler.removeCallbacks(checkForUpdates)
        releasePlayer()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val UPDATE_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L
    }
}
