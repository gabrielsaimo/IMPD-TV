package br.com.impd.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.min
import kotlin.math.pow

/**
 * The whole app: one live channel, full screen, no menus.
 *
 * The audience is mostly elderly, so there is exactly one control to learn —
 * the OK button pauses and resumes. Nothing can be navigated into, nothing can
 * be misconfigured, and a dropped signal recovers on its own instead of asking
 * anyone to do something about it.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var pausedCard: View
    private lateinit var statusCard: View
    private lateinit var statusTitle: android.widget.TextView
    private lateinit var statusDetail: android.widget.TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private var retryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        banner = findViewById(R.id.banner)
        pausedCard = findViewById(R.id.paused)
        statusCard = findViewById(R.id.status)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)

        player = ExoPlayer.Builder(this).build().apply {
            // A live channel should always show the newest picture available.
            setMediaItem(MediaItem.fromUri(Channel.STREAM))
            playWhenReady = true
            addListener(playerListener)
            prepare()
        }
        playerView.player = player
        playerView.useController = false

        showStatus(getString(R.string.starting_title), getString(R.string.starting_detail))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retryCount = 0
                    hideStatus()
                    if (player.playWhenReady) revealBanner() else showPaused()
                }
                Player.STATE_BUFFERING -> Unit
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            reconnect()
        }
    }

    /** Backs off a little further on each failure, capped so it never gives up. */
    private fun reconnect() {
        showStatus(getString(R.string.offline_title), getString(R.string.offline_detail))
        retryCount++
        val delay = min(1.6.pow(retryCount.toDouble()) * 1000, 15_000.0).toLong()
        handler.postDelayed({
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
        }, delay)
    }

    // MARK: - Remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePlayback()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { resume(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pause(); true }
            else -> {
                revealBanner()
                super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun togglePlayback() {
        if (player.playWhenReady) pause() else resume()
    }

    private fun pause() {
        player.playWhenReady = false
        showPaused()
    }

    private fun resume() {
        pausedCard.visibility = View.GONE
        // Jump back to the live edge: after a pause the stream is behind.
        player.seekToDefaultPosition()
        player.playWhenReady = true
        revealBanner()
    }

    // MARK: - Overlays

    private fun revealBanner() {
        pausedCard.visibility = View.GONE
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 6_000)
    }

    private fun showPaused() {
        handler.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        pausedCard.visibility = View.VISIBLE
    }

    private fun showStatus(title: String, detail: String) {
        statusTitle.text = title
        statusDetail.text = detail
        statusCard.visibility = View.VISIBLE
        banner.visibility = View.GONE
        pausedCard.visibility = View.GONE
    }

    private fun hideStatus() {
        statusCard.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        player.playWhenReady = false
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) {
            player.seekToDefaultPosition()
            player.playWhenReady = true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
