import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Downloads (Downloads.dc.html, SettingsDownloads.dc.html): the
/// space downloads take, movies, shows with their episodes, and the download
/// settings under them. Swipe a row to remove it, or use its ⋯ menu. On iPad
/// (Pad2Downloads.dc.html, the Settings split view's page) movies and the
/// settings stand beside the shows when the page is wide enough.
struct DownloadsScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        DownloadsView(model: DownloadsModel(downloads: services.downloads, navigator: navigator, device: downloadsDevice(services.client)))
    }
}

/// "iPad" or "iPhone": this device in the downloads' wording.
func downloadsDevice(_ client: ClientInfo) -> String {
    deviceFamily(model: client.model) == "iPad" ? "iPad" : "iPhone"
}

private struct DownloadsView: View {
    @State var model: DownloadsModel
    @State private var width: CGFloat = 0

    /// Movies and the settings beside the shows once the page has the room (a landscape iPad).
    private var twoColumns: Bool { width >= 700 }

    var body: some View {
        Group {
            if twoColumns {
                HStack(spacing: 0) {
                    list {
                        storage
                        if model.all.isEmpty { empty } else { movies }
                        settings
                    }
                    Rectangle().fill(Palette.separator).frame(width: 1)
                    list { shows }
                }
            } else {
                list {
                    storage
                    if model.all.isEmpty { empty }
                    movies
                    shows
                    settings
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .groundBackground()
        .navigationTitle("Downloads")
        .onAppear { model.refreshStorage() }
        .alert("Remove all downloads?", isPresented: $model.confirmingRemoveAll) {
            Button("Cancel", role: .cancel) {}
            Button("Remove all", role: .destructive) { model.removeAll() }
        } message: {
            Text(model.removeAllLine)
        }
        .messageToast($model.message)
    }

    private func list(@ViewBuilder _ content: () -> some View) -> some View {
        List { content() }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
    }

    private var storage: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    (Text(model.used).fontWeight(.semibold) + Text(" used by downloads").foregroundStyle(Palette.muted))
                    Spacer(minLength: 8)
                    Text("\(model.free) free").foregroundStyle(Palette.muted)
                }
                .font(.footnote.monospacedDigit())
                GeometryReader { geo in
                    HStack(spacing: 0) {
                        Rectangle().fill(Palette.gold).frame(width: geo.size.width * model.usedShare)
                        Rectangle().fill(Color(hex: 0x4A4A4F)).frame(width: geo.size.width * model.otherShare)
                        Spacer(minLength: 0)
                    }
                }
                .frame(height: 6)
                .background(Palette.separator)
                .clipShape(Capsule())
                .accessibilityHidden(true)
                HStack(spacing: 14) {
                    legend(Palette.gold, "Downloads")
                    legend(Color(hex: 0x4A4A4F), "Other apps and files")
                }
                .accessibilityHidden(true)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4))
            .accessibilityElement(children: .combine)
        }
    }

    private func legend(_ color: Color, _ text: String) -> some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 8, height: 8)
            Text(text)
        }
        .font(.caption2)
        .foregroundStyle(Palette.muted)
    }

    private var empty: some View {
        Section {
            EmptyState(systemImage: "arrow.down.circle", title: "Nothing downloaded yet", message: "Download a movie or an episode from its page to watch it without a connection.")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .listRowBackground(Color.clear)
        }
    }

    @ViewBuilder private var movies: some View {
        if !model.movies.isEmpty {
            Section("Movies") {
                ForEach(model.movies) { row($0, art: CGSize(width: 40, height: 60)) }
            }
        }
    }

    @ViewBuilder private var shows: some View {
        ForEach(model.shows) { group in
            Section {
                ForEach(group.episodes) { row($0, art: CGSize(width: 88, height: 50)) }
            } header: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.title).font(.headline).foregroundStyle(Palette.text).textCase(nil)
                    Text(group.line).font(.footnote).foregroundStyle(Palette.muted).textCase(nil)
                }
            }
        }
    }

    private var settings: some View {
        Section {
            Picker(selection: Binding(get: { model.settings.quality }, set: { model.setQuality($0) })) {
                ForEach(DownloadQuality.allCases, id: \.self) { q in
                    Text(q.label).tag(q)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Quality")
                    Text("For new downloads; each can pick another").font(.caption).foregroundStyle(Palette.muted)
                }
            }
            .pickerStyle(.menu)
            Toggle(isOn: Binding(get: { model.settings.wifiOnly }, set: { model.setWifiOnly($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Wi-Fi only")
                    Text(model.wifiLine).font(.caption).foregroundStyle(Palette.muted)
                }
            }
            .tint(Palette.gold)
            if !model.all.isEmpty {
                Button("Remove all downloads", role: .destructive) { model.confirmingRemoveAll = true }
                    .frame(maxWidth: .infinity)
            }
        } header: {
            Text("Download settings")
        } footer: {
            Text("Downloads keep going in the background and play until you remove them, even with no connection. Signing out deletes them.")
        }
        .listRowBackground(Palette.surfaceRaised)
    }

    private func row(_ d: OfflineDownload, art: CGSize) -> some View {
        DownloadRow(download: d, art: art, facts: model.factsLine(d), state: model.stateLine(d))
            .contentShape(Rectangle())
            .onTapGesture { model.open(d) }
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: "Open") { model.open(d) }
            .swipeActions(edge: .trailing) {
                Button(d.downloaded ? "Remove" : "Cancel", systemImage: "trash", role: .destructive) { model.remove(d) }
            }
            .overlay(alignment: .trailing) {
                Menu {
                    rowMenu(d)
                } label: {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(Palette.muted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Options for \(downloadTitle(d.item))")
            }
            .listRowBackground(Palette.surfaceRaised)
    }

    @ViewBuilder private func rowMenu(_ d: OfflineDownload) -> some View {
        if d.downloaded {
            Button("Play", systemImage: "play.fill") { model.play(d) }
        }
        switch d.status {
        case .downloading, .preparing: Button("Pause download", systemImage: "pause") { model.pause(d) }
        case .paused: Button("Resume download", systemImage: "arrow.down") { model.resume(d) }
        case .failed: Button("Try again", systemImage: "arrow.clockwise") { model.retry(d) }
        default: EmptyView()
        }
        if d.item.movieID != nil || d.item.episodeID != nil {
            Button(d.mark == .newVersion ? "Open to download again" : "Open page", systemImage: "info.circle") { model.open(d) }
        }
        Button(d.downloaded ? "Remove download" : "Cancel download", systemImage: d.downloaded ? "trash" : "xmark", role: .destructive) { model.remove(d) }
    }
}

/// A download in a list: artwork, title, quality and size, and its state or the server's mark.
struct DownloadRow: View {
    let download: OfflineDownload
    let art: CGSize
    let facts: String
    let state: String
    @Environment(AppServices.self) private var services

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: services.library.imageURL(artPath, size: art.width > art.height ? .w300 : .w342), seed: download.item.showTitle ?? download.item.title)
                .frame(width: art.width, height: art.height)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 2) {
                Text(downloadTitle(download.item)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                Text(facts).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
                Label {
                    Text(state)
                } icon: {
                    Image(systemName: icon)
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
                .lineLimit(1)
                if case .downloading(let percent, _) = download.status {
                    ProgressStrip(progress: Double(percent) / 100).clipShape(Capsule()).padding(.top, 2)
                }
            }
            Spacer(minLength: 44)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var artPath: String? {
        art.width > art.height ? download.item.artPath : download.item.posterPath
    }

    private var icon: String {
        switch download.mark {
        case .removedFromServer: return "minus.circle"
        case .newVersion: return "arrow.triangle.2.circlepath"
        case .none: break
        }
        switch download.status {
        case .downloaded: return "checkmark.circle"
        case .downloading: return "arrow.down"
        case .waiting(.wifi): return "wifi"
        case .waiting: return "clock"
        case .preparing: return "hourglass"
        case .paused: return "pause.circle"
        case .failed: return "exclamationmark.triangle"
        }
    }

    private var color: Color {
        switch download.mark {
        case .removedFromServer: return Palette.warning
        case .newVersion: return Palette.gold
        case .none: break
        }
        switch download.status {
        case .downloading: return Palette.gold
        case .failed: return Palette.warning
        default: return Palette.muted
        }
    }
}
