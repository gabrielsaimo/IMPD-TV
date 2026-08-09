import Foundation

/// The one and only channel. There is no list, no settings and no menu: the
/// app exists to put this stream on screen and keep it there.
enum Channel {
    static let name = "IMPD TV"
    static let stream = URL(string:
        "https://68882bdaf156a.streamlock.net/impd/ngrp:impd_all/chunklist_w1464410885_b2691072.m3u8")!
}
