package br.com.impd.tv

/**
 * The one and only channel: no list, no settings, no menu.
 *
 * The real stream URL is fetched fresh from impd.org.br's own API on every
 * launch and reconnect via [StreamResolver], since the streaming host can
 * change it at any time. This constant is only the last-resort fallback for
 * when that lookup fails (e.g. no network yet).
 */
object Channel {
    const val NAME = "IMPD TV"
    const val FALLBACK_STREAM =
        "https://68882bdaf156a.streamlock.net/impd/ngrp:impd_all/chunklist_w1464410885_b2691072.m3u8"
}
