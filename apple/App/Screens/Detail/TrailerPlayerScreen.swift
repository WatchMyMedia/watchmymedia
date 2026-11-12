import SwiftUI
import UIKit
import WebKit
import WMMFeatures
import WMMModel
import WMMUI

/// A movie's YouTube trailer, full screen (TrailerPlayer.dc.html,
/// Pad2TrailerPlayer.dc.html; PLAN-TRAILERS.md §6.3). Our bar holds ✕, the
/// trailer's and the movie's names and YouTube's terms; YouTube's own player
/// fills the rest at 16:9 with its own controls, and nothing of ours is drawn
/// over it. When no trailer's owner lets it play outside YouTube, the screen
/// says so and offers it in YouTube. Landscape on iPhone, like the player.
struct TrailerPlayerScreen: View {
    @State var model: TrailerPlayerModel
    let onClose: () -> Void
    @State private var safari: URL?
    @Environment(\.scenePhase) private var scenePhase

    /// The iPad's larger bar (Pad2TrailerPlayer.dc.html). Not the size class:
    /// the largest iPhones are regular width in landscape.
    private let wide = UIDevice.current.userInterfaceIdiom == .pad

    var body: some View {
        VStack(spacing: 0) {
            bar
            ZStack {
                Color.black
                if model.state == .refused {
                    refused
                } else {
                    // YouTube's rules: nothing plays while it can't be seen.
                    YouTubePlayerView(key: model.trailer.key, paused: scenePhase != .active) { event in
                        switch event {
                        case .error(let key, let code): model.playerFailed(key: key, code: code)
                        case .state(let key, let state): model.playerChanged(key: key, state: state)
                        case .open(let url): openOutside(url)
                        }
                    }
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .accessibilityIdentifier("trailer-player")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.black.ignoresSafeArea())
        .landscapeWhileShown()
        .persistentSystemOverlays(.hidden)
        // Back to the movie when the trailer ends, as after a trailer file in the player.
        .onChange(of: model.state) { _, state in if state == .ended { onClose() } }
        .inAppSafari(url: $safari)
    }

    private var bar: some View {
        HStack(spacing: wide ? 14 : 12) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .frame(width: 44, height: 44)
                    .background(.white.opacity(0.12), in: Circle())
            }
            .accessibilityLabel("Close")
            .accessibilityIdentifier("trailer-close")
            VStack(alignment: .leading, spacing: 2) {
                Text(model.trailer.name)
                    .scaledFont(wide ? 17 : 15, weight: .semibold)
                    .foregroundStyle(Palette.text)
                Text(model.movieTitle)
                    .scaledFont(wide ? 14 : 12)
                    .foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            terms
        }
        .padding(.horizontal, wide ? 24 : 16)
        .frame(height: wide ? 72 : 56)
        .background(Palette.ground.ignoresSafeArea(edges: [.top, .horizontal]))
        .overlay(alignment: .bottom) { Rectangle().fill(Palette.sheet).frame(height: 1) }
    }

    /// "Plays from YouTube · YouTube Terms of Service", the terms in the in-app Safari.
    private var terms: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 0) {
                Text("Plays from YouTube · ").foregroundStyle(Palette.muted)
                termsLink("YouTube Terms of Service")
            }
            termsLink("YouTube Terms")
        }
        .scaledFont(11)
        .lineLimit(1)
    }

    private func termsLink(_ title: String) -> some View {
        Button { safari = youTubeTermsURL } label: {
            Text(title).underline().foregroundStyle(Palette.textSecondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("YouTube Terms of Service")
        .accessibilityHint("Plays from YouTube")
    }

    private var refused: some View {
        VStack(spacing: 10) {
            Image(systemName: "film")
                .font(.system(size: 30))
                .foregroundStyle(Palette.muted)
                .accessibilityHidden(true)
            Text("This trailer can’t play here")
                .scaledFont(18, weight: .bold)
                .foregroundStyle(Palette.text)
            Text("Its owner only lets it play on YouTube.")
                .scaledFont(14)
                .foregroundStyle(Palette.muted)
            Button { openOutside(model.trailer.watchURL) } label: {
                Label("Open in YouTube", systemImage: "arrow.up.right").labelStyle(TrailingIconLabelStyle())
            }
            .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
            .padding(.top, 8)
            .accessibilityIdentifier("trailer-open-youtube")
        }
        .multilineTextAlignment(.center)
        .padding(24)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("trailer-refused")
    }

    /// The YouTube app when it takes the link, else the in-app Safari.
    private func openOutside(_ url: URL) {
        guard url.scheme == "https" || url.scheme == "http" else { return }
        UIApplication.shared.open(url, options: [.universalLinksOnly: true]) { opened in
            if !opened { safari = url }
        }
    }
}

private let youTubeTermsURL = URL(string: "https://www.youtube.com/t/terms")!

/// The title, then the icon: "Open in YouTube ↗".
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.title
            configuration.icon.font(.system(size: 13, weight: .bold))
        }
    }
}

/// YouTube's IFrame player in a web view. A small page of ours is loaded
/// with an HTTPS base URL naming the app (its bundle ID, as YouTube's
/// required minimum functionality asks), so the player gets the Referer its
/// embeds need: without one it refuses, error 153. The page passes the
/// player's errors and state to the app through a script message handler.
/// Media plays without a further tap (the press on Trailer was the
/// gesture), nothing is kept between trailers, and a link that would
/// navigate away leaves for the YouTube app or Safari.
struct YouTubePlayerView: UIViewRepresentable {
    enum Event {
        case error(key: String, code: Int)
        case state(key: String, state: Int)
        /// A link in the player (YouTube's logo, its title, "Watch on YouTube").
        case open(URL)
    }

    let key: String
    let paused: Bool
    let onEvent: (Event) -> Void

    /// `https://media.watchmy.client`: the app's bundle ID, lowercased.
    static let origin = "https://" + (Bundle.main.bundleIdentifier ?? "media.watchmy.client").lowercased()

    /// `-trailerError 150` in a debug build: once YouTube's player is ready,
    /// the page reports that error, as for a video whose owner keeps it on
    /// YouTube. No video is known to do that reliably, so this is how a
    /// simulator reaches the refused screen.
    static let simulatedError: Int? = {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-trailerError"), arguments.indices.contains(i + 1) { return Int(arguments[i + 1]) }
        #endif
        return nil
    }()

    func makeCoordinator() -> Coordinator { Coordinator(onEvent: onEvent) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(WeakMessageHandler(context.coordinator), name: Coordinator.channel)
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.allowsLinkPreview = false
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        context.coordinator.load(key, in: web)
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onEvent = onEvent
        if coordinator.key != key { coordinator.load(key, in: web) }
        if paused && !coordinator.paused { web.evaluateJavaScript("pauseTrailer()") }
        coordinator.paused = paused
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: Coordinator.channel)
        web.pauseAllMediaPlayback(completionHandler: nil)
        web.stopLoading()
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        static let channel = "trailer"
        var onEvent: (Event) -> Void
        private(set) var key: String?
        var paused = false

        init(onEvent: @escaping (Event) -> Void) { self.onEvent = onEvent }

        func load(_ key: String, in web: WKWebView) {
            self.key = key
            web.loadHTMLString(YouTubePlayerView.page(key: key), baseURL: URL(string: YouTubePlayerView.origin + "/"))
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let key = body["key"] as? String, let event = body["event"] as? String else { return }
            switch event {
            case "error": if let code = body["code"] as? Int { onEvent(.error(key: key, code: code)) }
            case "state": if let state = body["state"] as? Int { onEvent(.state(key: key, state: state)) }
            default: break
            }
        }

        /// The page itself and YouTube's player in its frame load here; anything else leaves the app.
        func webView(_ web: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = action.request.url else { return .cancel }
            if let frame = action.targetFrame, !frame.isMainFrame { return .allow }
            if action.targetFrame != nil, url.host == URL(string: YouTubePlayerView.origin)?.host || url.scheme == "about" { return .allow }
            onEvent(.open(url))
            return .cancel
        }

        /// A link that opens a new window.
        func webView(_ web: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url { onEvent(.open(url)) }
            return nil
        }
    }

    /// The player's page. The key is a YouTube id (letters, digits, - and _),
    /// so it can't break out of the script. `host` is YouTube's
    /// privacy-enhanced player, as on the web; `origin` is the page's own, or
    /// the player sends its events nowhere.
    static func page(key: String) -> String {
        """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
        </head>
        <body>
        <div id="player"></div>
        <script>
        var KEY = "\(key)";
        var SIMULATED_ERROR = \(simulatedError.map(String.init) ?? "0");
        var player = null;
        function post(message) {
          message.key = KEY;
          window.webkit.messageHandlers.\(Coordinator.channel).postMessage(message);
        }
        function onYouTubeIframeAPIReady() {
          player = new YT.Player("player", {
            host: "https://www.youtube-nocookie.com",
            videoId: KEY,
            width: "100%",
            height: "100%",
            playerVars: { autoplay: 1, playsinline: 1, rel: 0, origin: "\(origin)" },
            events: {
              onReady: function (e) { if (SIMULATED_ERROR) { post({ event: "error", code: SIMULATED_ERROR }); } else { e.target.playVideo(); } },
              onError: function (e) { post({ event: "error", code: e.data }); },
              onStateChange: function (e) { post({ event: "state", state: e.data }); }
            }
          });
        }
        function pauseTrailer() { if (player && player.pauseVideo) { player.pauseVideo(); } }
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
        </body>
        </html>
        """
    }
}

/// The user content controller keeps its handlers, so a handler that is
/// the coordinator would keep the web view alive.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: (any WKScriptMessageHandler)?

    init(_ target: any WKScriptMessageHandler) { self.target = target }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}
