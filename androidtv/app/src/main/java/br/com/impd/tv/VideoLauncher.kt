package br.com.impd.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast

/**
 * Opens a YouTube video in a YouTube app — never in a browser.
 *
 * A browser is not a fallback here, it is a failure with a picture. On a
 * television it cannot be driven with a remote, and youtube.com in it either
 * refuses to play or plays badly. So no browser is ever a candidate: if every
 * real player fails, this says so out loud instead of quietly handing the
 * viewer something unusable.
 *
 * Two things go wrong when handing a video away, and only one of them is an
 * exception:
 *
 * 1. Nothing answers. `startActivity` throws and the next candidate is tried.
 * 2. Something answers, opens, and dies a second later. The phone build of
 *    YouTube does this on a set-top box — it takes the link, finds no
 *    touchscreen, shows a black screen and quits. Some players do the same on
 *    the old `vnd.youtube:` deep link. The box drops back to the home screen,
 *    which is this app, so from the sofa it looks like nothing happened.
 *
 * The second cannot be caught, only observed: if MainActivity returns to the
 * foreground within [BOUNCE_WINDOW_MS] of a hand-off, nobody watched anything
 * and the candidate counts as failed exactly like a thrown exception —
 * [onHostResumed] resumes the cascade at the next one.
 *
 * Because a player that quits costs a whole round trip through onResume to
 * detect, each YouTube app gets several ways in before the cascade moves on to
 * the next app: the plain https link, both spellings of the vnd.youtube deep
 * link, and every activity that package itself declares for a YouTube address,
 * launched by explicit component. A repackaged build that ignores one of these
 * usually answers another.
 */
object VideoLauncher {

    /**
     * YouTube players we know by name, best to worst for a television. Must
     * stay in sync with `<queries>` in AndroidManifest, or none are visible.
     *
     * The phone build is last on purpose: it is the one that opens and dies on
     * a box with no touchscreen, and it is also the one most likely to be
     * installed, so anything else on the aparelho gets first refusal.
     */
    private val KNOWN_PLAYERS = listOf(
        "com.google.android.youtube.tv",      // YouTube oficial para Android TV
        "com.teamsmart.videobase",            // SmartTubeNext
        "com.liskovsoft.smarttubetv",         // SmartTubeNext
        "com.liskovsoft.smarttubetv.beta",    // SmartTubeNext Beta
        "com.liskovsoft.videomanager",        // SmartTube antigo
        "app.revanced.android.youtube",       // ReVanced
        "com.google.android.youtube"          // YouTube de celular: morre em TV, vai por último
    )

    /** Catches repackaged players whose exact name cannot be known in advance. */
    private val NAME_HINTS = listOf("youtube", "smarttube", "newpipe", "tubi", "vanced")

    /**
     * A player that quits on a box it cannot run on dies almost immediately —
     * well inside two seconds. The window has to stay under the time it takes
     * a viewer to open a video, change their mind and press back, because that
     * looks identical from here and must not be answered by launching a
     * different player at them.
     */
    private const val BOUNCE_WINDOW_MS = 2_000L

    /**
     * A bounce costs a visible flash of a player opening and closing. Past a
     * few of those in a row the box clearly has nothing that works, and saying
     * so beats flashing through the rest of the list.
     */
    private const val MAX_BOUNCES = 4

    private class Attempt(
        val candidates: List<Intent>,
        var next: Int,
        var launchedAt: Long,
        var bounces: Int = 0
    )

    private var pending: Attempt? = null

    /** Filled while building the list, so a failure can say what the box has. */
    private var lastSeenPlayers: List<String> = emptyList()

    fun open(context: Context, video: YoutubeVideo) {
        pending = null
        advance(context, Attempt(buildCandidates(context, video), 0, 0L))
    }

    /**
     * Called from MainActivity.onResume. Returning here this soon after a
     * hand-off means the player quit on its own, so the cascade continues.
     */
    fun onHostResumed(context: Context) {
        val attempt = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtime() - attempt.launchedAt >= BOUNCE_WINDOW_MS) return
        attempt.bounces++
        if (attempt.bounces > MAX_BOUNCES) {
            reportFailure(context)
            return
        }
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

        reportFailure(context)
    }

    /**
     * Naming what was found turns "não abriu" into something a person can read
     * off the television and repeat to whoever maintains this.
     */
    private fun reportFailure(context: Context) {
        val found = if (lastSeenPlayers.isEmpty()) {
            "Nenhum app de vídeo instalado."
        } else {
            "Tentados: " + lastSeenPlayers.joinToString(", ")
        }
        Toast.makeText(
            context,
            "Não foi possível abrir o vídeo neste aparelho.\n$found",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun buildCandidates(context: Context, video: YoutubeVideo): List<Intent> {
        val pm = context.packageManager
        val https = Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        // Both spellings exist in the wild and different builds answer different
        // ones; neither is reliable enough to be the only deep link tried.
        val vndSlash = Uri.parse("vnd.youtube://${video.id}")
        val vndPlain = Uri.parse("vnd.youtube:${video.id}")

        val browsers = browserPackages(pm)
        val known = KNOWN_PLAYERS.filter { isInstalled(pm, it) }
        val discovered = handlersFor(pm, https)
            .filterNot { it in KNOWN_PLAYERS || it == context.packageName }
            // A player that also happens to claim every web address stays; only
            // a package with nothing YouTube about its name is dropped as a browser.
            .filterNot { it in browsers && !isYoutubeApp(it) }

        // Leanback first: it is the signal Android TV itself uses to say an app
        // belongs on a television. Only an ordering hint, never a filter — on a
        // box running a manufacturer launcher nothing declares leanback at all.
        val players = (known.filter { isTelevisionApp(pm, it) } +
            discovered.filter { isTelevisionApp(pm, it) } +
            known.filterNot { isTelevisionApp(pm, it) } +
            discovered.filterNot { isTelevisionApp(pm, it) }).distinct()

        lastSeenPlayers = players

        val candidates = mutableListOf<Intent>()
        for (pkg in players) {
            // https first: the form every current build handles. The deep links
            // are older, and there are players that open on them and quit.
            candidates += viewIntent(https, pkg)
            candidates += viewIntent(vndSlash, pkg)
            candidates += viewIntent(vndPlain, pkg)
            // Explicit component: some repackaged builds have an activity that
            // plays the video fine but a manifest filter the system will not
            // match, so setPackage alone never reaches it.
            for (component in componentsFor(pm, pkg, https)) {
                candidates += viewIntent(https, null).setComponent(component)
            }
        }

        // Absolute last resort, and still not a browser: open the YouTube app
        // on its own home screen. The viewer lands somewhere they can actually
        // use a remote in, instead of a web page they cannot.
        for (pkg in players.filter { isYoutubeApp(it) }) {
            launchIntent(pm, pkg)?.let { candidates += it }
        }
        return candidates
    }

    /**
     * Extras the leanback builds of YouTube read: without them a video that
     * does open can come up windowed, or drop back to the app's home screen
     * when it ends instead of returning here.
     */
    private fun viewIntent(uri: Uri, pkg: String?) = Intent(Intent.ACTION_VIEW, uri).apply {
        if (pkg != null) setPackage(pkg)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra("force_fullscreen", true)
        putExtra("finish_on_ended", true)
    }

    private fun isYoutubeApp(pkg: String) =
        pkg in KNOWN_PLAYERS || NAME_HINTS.any { pkg.contains(it, ignoreCase = true) }

    private fun isInstalled(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun isTelevisionApp(pm: PackageManager, pkg: String) = try {
        pm.getLeanbackLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }

    /** Leanback entry point when the app has one, so the TV interface opens. */
    private fun launchIntent(pm: PackageManager, pkg: String): Intent? = try {
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (e: Exception) {
        null
    }

    private fun componentsFor(pm: PackageManager, pkg: String, uri: Uri): List<ComponentName> = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg), 0)
            .mapNotNull { it.activityInfo }
            .filter { it.exported }
            .map { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun handlersFor(pm: PackageManager, uri: Uri): List<String> = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), 0)
            .map { it.activityInfo.packageName }
            .distinct()
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
