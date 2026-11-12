import Foundation
import Observation
import WMMModel

/// One movie's or episode's download (MovieDownload.dc.html,
/// DownloadSheet.dc.html; android ItemDownloadViewModel): the button's
/// state, the sheet's qualities with their sizes and the space free, and
/// what the menu does. `fileID` is the version the page plays first, the one
/// a new download is of; nil when there is no file. A download of any of
/// `otherFiles` (a movie's other versions) is the page's download too.
@MainActor
@Observable
public final class ItemDownloadModel {
    public let fileID: Int?
    /// How the file would download at each quality; loaded for the button's size and the sheet.
    public private(set) var plan: Loadable<DownloadPlan>?
    public private(set) var storage = DownloadStorage(usedBytes: 0, freeBytes: 0, totalBytes: 0)
    /// A download is being made.
    public private(set) var starting = false
    /// The sheet is open.
    public var sheetShown = false
    /// The held download's menu is open.
    public var menuShown = false
    /// The sheet's choices.
    public var quality: DownloadQuality
    public var audioIndex: Int?
    public var burnInSubtitleID: String?
    /// Said once, then cleared by the view.
    public var message: String?

    @ObservationIgnored private let downloads: any DownloadRepository
    @ObservationIgnored private let otherFiles: [Int]
    /// "iPhone", "iPad": this device in the sheet's lines.
    public let device: String

    public init(downloads: any DownloadRepository, fileID: Int?, otherFiles: [Int] = [], device: String = "iPhone") {
        self.downloads = downloads
        self.fileID = fileID
        self.otherFiles = otherFiles
        self.device = device
        quality = downloads.settings.quality
    }

    /// The download of the page's files held here, if any.
    public var download: OfflineDownload? {
        let held = downloads.downloads
        // A copy whose file the server replaced still belongs to the page of the new one.
        return held.forFile(fileID) ?? otherFiles.lazy.compactMap { held.forFile($0) }.first ?? held.first { $0.replacedBy != nil && $0.replacedBy == fileID }
    }

    /// The server lets this person download, and the page has a file to.
    public var canDownload: Bool { downloads.canDownload && fileID != nil }

    /// The button shows: a held download's state, or Download.
    public var offered: Bool { download != nil || canDownload }

    public var settings: DownloadSettings { downloads.settings }

    /// "about 46 GB", "4.2 GB" at the Settings quality, once the plan is in.
    public var defaultSize: String? {
        guard let option = plan?.value?.option(settings.quality) else { return nil }
        return downloadSize(option.sizeBytes, estimated: option.estimated)
    }

    // MARK: The button

    /// "Download · about 46 GB", "Downloading 42%", "Downloaded"…
    public var buttonLabel: String {
        guard let download else { return defaultSize.map { "Download · \($0)" } ?? "Download" }
        if download.status == .failed { return "Couldn’t download · Try again" }
        return downloadLabel(download.status)
    }

    /// The button's SF Symbol.
    public var buttonIcon: String {
        switch download?.status {
        case nil, .downloading?: "arrow.down.to.line"
        case .waiting(.wifi)?: "wifi"
        case .waiting?: "clock"
        case .preparing?: "hourglass"
        case .paused?: "pause.circle"
        case .downloaded?: "checkmark.circle"
        case .failed?: "exclamationmark.triangle"
        }
    }

    /// Gold for a download coming or held, orange when it failed.
    public var buttonTone: DownloadTone {
        switch download?.status {
        case .downloading?, .downloaded?: .gold
        case .failed?: .warning
        default: .plain
        }
    }

    /// The progress line inside the button, 0–1; nil when none shows.
    public var progress: Double? {
        switch download?.status {
        case .downloading(let percent, _)?, .paused(let percent, _)?: Double(percent) / 100
        default: nil
        }
    }

    /// The line under the button for a wait the person can't see the reason of.
    public var help: String? { download.flatMap { downloadHelp($0.status) } }

    // MARK: The menu

    public var menuTitle: String { download.map { downloadMenuTitle($0.status) } ?? "" }

    public var menuLine: String? { download.flatMap { downloadMenuLine($0, device: device, wifiOnly: settings.wifiOnly) } }

    /// Download pressed: the sheet for a new download, the menu for a held one, a retry for a failed one.
    public func press() {
        guard let download else {
            openSheet()
            return
        }
        if download.status == .failed {
            downloads.retry(download.id)
        } else {
            menuShown = true
        }
    }

    // MARK: Loading

    /// Loads the plan once, for the button's size, when there's something to offer.
    public func load() async {
        guard plan == nil, download == nil, canDownload else { return }
        await loadPlan()
    }

    public func loadPlan() async {
        guard let fileID else { return }
        plan = .loading
        plan = await Loadable.run { try await self.downloads.plan(fileID: fileID) } ?? plan
    }

    /// Opening the sheet: the Settings quality, the plan again if it failed, and the space free now.
    public func openSheet() {
        quality = settings.quality
        audioIndex = nil
        burnInSubtitleID = nil
        storage = downloads.storage()
        sheetShown = true
        if plan?.value == nil { Task { await loadPlan() } }
    }

    // MARK: The sheet

    /// The quality picked, or the plan's first when the server didn't offer it.
    public var chosen: DownloadOption? { plan?.value?.option(quality) }

    /// Each quality with its size, best first.
    public var options: [DownloadOption] { plan?.value?.options ?? [] }

    /// Whether `option` leaves 1 GB free.
    public func fits(_ option: DownloadOption) -> Bool { storage.fits(option.sizeBytes) }

    /// The option's line: what it is, or how much it needs.
    public func detail(_ option: DownloadOption) -> String {
        fits(option) ? option.detail : "Needs \(neededSize(option.sizeBytes)) free"
    }

    /// An HLS download carries one audio track: offered when there are several.
    public var audioChoices: [AudioOption] {
        guard chosen?.protocol == .hls, let audio = plan?.value?.audio, audio.count > 1 else { return [] }
        return audio
    }

    /// The audio track the sheet shows as chosen: the viewer's pick, else the
    /// one the server would put in this quality (the person's audio
    /// languages), else the file's default.
    public var selectedAudioIndex: Int? {
        audioIndex ?? chosen?.audioIndex.flatMap { i in audioChoices.contains { $0.index == i } ? i : nil }
            ?? audioChoices.first(where: \.isDefault)?.index ?? audioChoices.first?.index
    }

    /// Image subtitles come only burned in, into an HLS download.
    public var burnInChoices: [SubtitleOption] {
        guard chosen?.protocol == .hls else { return [] }
        return plan?.value?.imageSubtitles ?? []
    }

    /// "Every audio and subtitle track in the file comes with it.", "English and Spanish subtitles come with it."
    public var tracksLine: String {
        guard let chosen, chosen.protocol == .hls else { return "Every audio and subtitle track in the file comes with it." }
        let names = (plan?.value?.textSubtitles ?? []).compactMap(\.language).map(languageName)
        var unique: [String] = []
        for n in names where !unique.contains(n) { unique.append(n) }
        switch unique.count {
        case 0: return "No subtitles come with it."
        case 1: return "\(unique[0]) subtitles come with it."
        default: return "\(unique.dropLast().joined(separator: ", ")) and \(unique.last!) subtitles come with it."
        }
    }

    public var wifiLine: String { settings.wifiOnly ? "Downloads on Wi-Fi only." : "Downloads on Wi-Fi and mobile data." }

    /// "96.4 GB free on this iPhone", or what the chosen quality needs.
    public var spaceLine: String {
        guard let chosen, !fits(chosen) else { return "\(freeSize(storage.freeBytes)) free on this \(device)" }
        return "Needs \(neededSize(chosen.sizeBytes)) free. This \(device) has \(freeSize(storage.freeBytes)). Pick a lower quality or free up space."
    }

    /// "Download · about 46 GB".
    public var downloadButtonLabel: String {
        guard let chosen else { return "Download" }
        return "Download · \(downloadSize(chosen.sizeBytes, estimated: chosen.estimated))"
    }

    public var canStart: Bool { !starting && chosen.map(fits) == true }

    /// Download pressed in the sheet: makes the download, then closes the sheet.
    public func start() async {
        guard let fileID, let chosen, canStart else { return }
        starting = true
        defer { starting = false }
        do {
            try await downloads.download(DownloadChoice(fileID: fileID, quality: chosen.quality, audioIndex: audioChoices.isEmpty ? nil : audioIndex, burnInSubtitleID: burnInChoices.isEmpty ? nil : burnInSubtitleID))
            sheetShown = false
        } catch {
            message = error.displayMessage
        }
    }

    // MARK: The menu's actions

    public func pause() { download.map { downloads.pause($0.id) } }

    public func resume() { download.map { downloads.resume($0.id) } }

    public func retry() { download.map { downloads.retry($0.id) } }

    /// Remove download, or Cancel download while it comes.
    public func remove() { download.map { downloads.remove($0.id) } }
}

/// How the download button is coloured.
public enum DownloadTone: Hashable, Sendable { case plain, gold, warning }

/// "English" for "en", the language's own name when the system knows it.
func languageName(_ code: String) -> String {
    Locale(identifier: "en").localizedString(forLanguageCode: code) ?? code
}
