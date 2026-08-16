import Foundation

/// The one and only channel. There is no list, no settings and no menu: the
/// app exists to put this stream on screen and keep it there.
///
/// The real stream URL is fetched fresh from impd.org.br's own API on every
/// launch and reconnect via `StreamResolver`, since the streaming host can
/// change it at any time. `fallbackStream` is only the last-resort URL for
/// when that lookup fails.
enum Channel {
    static let name = "IMPD TV"
    static let fallbackStream = URL(string:
        "https://68882bdaf156a.streamlock.net/impd/ngrp:impd_all/chunklist_w1464410885_b2691072.m3u8")!
}
