package br.com.impd.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast

/**
 * Opens a YouTube video on a box whose contents we cannot know in advance.
 *
 * Two failures happen out in the field and only one of them is an exception:
 *
 * 1. Nothing on the box answers the link. `startActivity` throws, we move on.
 * 2. Something answers it, opens, and dies a second later. The phone build of
 *    YouTube does exactly this on a set-top box: it takes the link, finds no
 *    touchscreen, shows a black screen and quits. Some players do the same with
 *    the old `vnd.youtube:` deep link. The television drops back to the home
 *    screen, which is this app, so from the sofa it looks like pressing OK did
 *    nothing at all.
 *
 * The second one cannot be caught, only observed: if this activity comes back
 * to the foreground within [BOUNCE_WINDOW_MS] of handing a video away, nobody
 * watched anything, and the candidate is treated as failed exactly like a
 * thrown exception — [onHostResumed] resumes the cascade at the next one. A
 * viewer who really did watch and came back takes far longer than that, and the
 * pending attempt is dropped.
 *
 * Candidates are ordered, never filtered. Leanback comes first because that is
 * how Android TV itself decides an app belongs on a television, but cheap boxes
 * run a manufacturer launcher where nothing declares leanback at all — YouTube
 * included, working fine — so a plain app is still tried, just last. Ordering
 * where an earlier attempt used a filter is the whole fix: the filter version
 * opened nothing on those boxes, the unfiltered version opened the phone build
 * first and it died on screen.
 */
object VideoLauncher {

    /** Kept in sync with `<queries>` in AndroidManifest, or none of these are visible. */
    private val KNOWN_PLAYERS = listOf(
        "com.google.android.youtube.tv",      // YouTube oficial para Android TV
        "com.teamsmart.videobase",            // SmartTubeNext
        "com.liskovsoft.smarttubetv",         // SmartTubeNext
        "com.liskovsoft.smarttubetv.beta",    // SmartTubeNext Beta
        "com.liskovsoft.videomanager",        // SmartTube antigo
        "app.revanced.android.youtube",       // ReVanced
        "com.google.android.youtube"          // YouTube de celular: morre em TV, vai por último
    )

    /**
     * Long enough that a player which merely starts slowly is not mistaken for
     * one that quit, short enough that nobody watches a video inside it.
     */
    private const val BOUNCE_WINDOW_MS = 4_000L

    private class Attempt(val candidates: List<Intent>, var next: Int, var launchedAt: Long)

    private var pending: Attempt? = null

    fun open(context: Context, video: YoutubeVideo) {
        pending = null
        val candidates = buildCandidates(context, video)
        advance(context, Attempt(candidates, 0, 0L))
    }

    /**
     * Called from MainActivity.onResume. Returning here this soon after a
     * hand-off means the player quit on its own, so the cascade continues.
     */
    fun onHostResumed(context: Context) {
        val attempt = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtime() - attempt.launchedAt >= BOUNCE_WINDOW_MS) return
        advance(context, attempt)
    }

    /** Walks the list from [Attempt.next] until one candidate stays on screen. */
    private fun advance(context: Context, attempt: Attempt) {
        while (attempt.next < attempt.candidates.size) {
            val intent = attempt.candidates[attempt.next]
            attempt.next++
            if (start(context, intent)) {
                attempt.launchedAt = SystemClock.elapsedRealtime()
                pending = attempt
                return
            }
        }

        Toast.makeText(
            context,
            "Nenhum aplicativo de vídeo conseguiu abrir neste aparelho.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Every candidate names its package. An intent with no package makes the
     * system put up the "Abrir com" chooser, drawn in a phone layout over a
     * leanback ROM that may not even focus it with a remote — a dead end in
     * front of the elderly audience this app is for. The chooser is only
     * reached after every named package has been tried and failed.
     */
    private fun buildCandidates(context: Context, video: YoutubeVideo): List<Intent> {
        val https = Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        val vnd = Uri.parse("vnd.youtube:${video.id}")
        val pm = context.packageManager

        val known = KNOWN_PLAYERS.filter { isInstalled(pm, it) }
        val discovered = handlersFor(context, https).filterNot { it in KNOWN_PLAYERS }
        val browsers = browserPackages(pm)

        // Leanback first, browsers last, everything else in between.
        val ordered =
            known.filter { isTelevisionApp(pm, it) } +
                discovered.filter { it !in browsers && isTelevisionApp(pm, it) } +
                known.filterNot { isTelevisionApp(pm, it) } +
                discovered.filter { it !in browsers && !isTelevisionApp(pm, it) } +
                discovered.filter { it in browsers }

        val candidates = mutableListOf<Intent>()
        for (pkg in ordered.distinct()) {
            // https first: it is the form every current build handles. The
            // vnd deep link is older and some players now open on it and quit.
            candidates += viewIntent(https, pkg)
            if (pkg !in browsers) candidates += viewIntent(vnd, pkg)
        }
        candidates += viewIntent(https, null)
        candidates += viewIntent(vnd, null)
        return candidates
    }

    private fun viewIntent(uri: Uri, pkg: String?) = Intent(Intent.ACTION_VIEW, uri).apply {
        if (pkg != null) setPackage(pkg)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    private fun isInstalled(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A leanback launcher is the signal Android TV uses to decide an app
     * belongs on a television. It is a hint about ordering only: plenty of
     * boxes run a manufacturer launcher where no app declares it.
     */
    private fun isTelevisionApp(pm: PackageManager, pkg: String) = try {
        pm.getLeanbackLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }

    private fun handlersFor(context: Context, uri: Uri): List<String> = try {
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Anything that answers an unrelated web address is a browser. A YouTube
     * app declares the youtube.com host specifically and is not in this set.
     */
    private fun browserPackages(pm: PackageManager): Set<String> = try {
        pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0
        ).map { it.activityInfo.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
