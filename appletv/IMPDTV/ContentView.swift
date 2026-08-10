import SwiftUI
import AVFoundation
import UIKit

/// Video surface. A bare AVPlayerLayer rather than AVPlayerViewController: the
/// system transport bar offers scrubbing and menus that do not apply to a live
/// channel and only give a viewer ways to get lost.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer

    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.backgroundColor = .black
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        return view
    }

    func updateUIView(_ view: Surface, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }
}

struct ContentView: View {
    @StateObject private var model = PlayerModel()
    @State private var showsBanner = true
    @State private var hideWork: DispatchWorkItem?

    var body: some View {
        // A focusable, full-screen button: on tvOS the Select press only
        // reaches a view that can hold focus, so a bare gesture on the stack
        // would silently do nothing and the press would fall through to the
        // system instead.
        Button(action: press) { screen }
            .buttonStyle(.plain)
            .ignoresSafeArea()
            .onPlayPauseCommand { press() }
            .onMoveCommand { _ in reveal() }
            .onAppear {
                model.start()
                reveal()
            }
    }

    private var screen: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: model.player).ignoresSafeArea()

            switch model.phase {
            case .reconnecting, .starting:
                statusCard
            case .playing:
                if showsBanner { liveBanner }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }

    // MARK: - Overlays

    private var liveBanner: some View {
        VStack {
            HStack(spacing: 24) {
                liveDot
                Text(Channel.name)
                    .font(.system(size: 56, weight: .heavy, design: .rounded))
                Spacer()
            }
            .padding(.horizontal, 72)
            .padding(.vertical, 40)
            .background(.black.opacity(0.55))
            .clipShape(RoundedRectangle(cornerRadius: 36, style: .continuous))
            .padding(.horizontal, 60)
            .padding(.top, 50)

            Spacer()
        }
        .foregroundStyle(.white)
        .transition(.opacity)
    }

    private var liveDot: some View {
        HStack(spacing: 16) {
            Circle().fill(.red).frame(width: 28, height: 28)
            Text("AO VIVO")
                .font(.system(size: 40, weight: .black, design: .rounded))
        }
    }

    private var statusCard: some View {
        VStack(spacing: 44) {
            ProgressView()
                .controlSize(.extraLarge)
                .tint(.white)
                .scaleEffect(2.4)
            Text(model.phase == .starting ? "Ligando o canal…" : "Sinal caiu")
                .font(.system(size: 68, weight: .heavy, design: .rounded))
            Text(model.phase == .starting
                 ? "Só um instante"
                 : "Estamos reconectando sozinho. Não precisa fazer nada.")
                .font(.system(size: 38, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 140)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black.opacity(0.85))
    }

    // MARK: - Interaction

    private func press() {
        // Pause was removed on purpose: the channel plays live, always.
        // Pressing OK only brings the channel banner back for a moment.
        reveal()
    }

    /// The banner shows on any remote activity and gets out of the way again.
    private func reveal() {
        hideWork?.cancel()
        withAnimation(.easeInOut(duration: 0.25)) { showsBanner = true }
        let work = DispatchWorkItem {
            withAnimation(.easeInOut(duration: 0.4)) { showsBanner = false }
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
    }
}
