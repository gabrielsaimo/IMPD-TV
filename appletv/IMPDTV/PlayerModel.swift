import Foundation
import AVFoundation
import Combine

/// Playback for a single live channel.
///
/// Everything here serves one goal: the picture comes back on its own. Nobody
/// should have to know what a stream is, so failures never surface as an error
/// to dismiss — the app says it is reconnecting and keeps trying.
@MainActor
final class PlayerModel: ObservableObject {
    enum Phase: Equatable {
        case starting
        case playing
        case paused
        case reconnecting
    }

    @Published private(set) var phase: Phase = .starting

    let player = AVPlayer()

    private var statusObserver: NSKeyValueObservation?
    private var timeControlObserver: NSKeyValueObservation?
    private var notifications: [NSObjectProtocol] = []
    private var watchdog: Timer?
    private var stalledSince: Date?
    private var attempt = 0

    init() {
        player.automaticallyWaitsToMinimizeStalling = true
        player.allowsExternalPlayback = true
        observePlayer()
    }

    // MARK: - Control

    func start() {
        attempt = 0
        load()
        startWatchdog()
    }

    func togglePlayPause() {
        switch phase {
        case .playing:
            player.pause()
            phase = .paused
        case .paused:
            player.play()
            phase = .playing
        case .starting, .reconnecting:
            // A press during a hiccup means "try again now".
            reconnect(immediately: true)
        }
    }

    /// Live channels drift behind after a pause; resuming jumps to the edge so
    /// the viewer is never watching minutes-old video without realising.
    func jumpToLive() {
        guard let item = player.currentItem,
              let range = item.seekableTimeRanges.last?.timeRangeValue else { return }
        player.seek(to: CMTimeRangeGetEnd(range), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    // MARK: - Loading

    private func load() {
        tearDownItemObservers()

        let asset = AVURLAsset(url: Channel.stream)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 6

        statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    self.attempt = 0
                    self.stalledSince = nil
                    self.phase = .playing
                case .failed:
                    self.reconnect()
                default:
                    break
                }
            }
        }

        let center = NotificationCenter.default
        notifications.append(center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.reconnect() }
            })
        notifications.append(center.addObserver(
            forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.stalledSince = Date() }
            })

        player.replaceCurrentItem(with: item)
        player.play()
    }

    private func observePlayer() {
        timeControlObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            Task { @MainActor in
                guard let self, self.phase != .paused else { return }
                if player.timeControlStatus == .playing { self.phase = .playing }
            }
        }
    }

    private func reconnect(immediately: Bool = false) {
        guard phase != .reconnecting || immediately else { return }
        phase = .reconnecting
        attempt += 1
        let delay = immediately ? 0 : min(pow(1.6, Double(attempt)), 15)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.load()
        }
    }

    /// AVPlayer can sit in "waiting for data" forever on a dead live source, so
    /// a stall that outlasts ten seconds is treated as a disconnection.
    private func startWatchdog() {
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.phase == .playing || self.phase == .starting else { return }
                if self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
                    if let since = self.stalledSince {
                        if Date().timeIntervalSince(since) > 10 { self.reconnect() }
                    } else {
                        self.stalledSince = Date()
                    }
                } else {
                    self.stalledSince = nil
                }
            }
        }
    }

    private func tearDownItemObservers() {
        statusObserver?.invalidate()
        statusObserver = nil
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications.removeAll()
    }
}
