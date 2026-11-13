import SwiftUI
import WMMModel

/// Calls `changed` when the account's downloads change in a way a show or
/// movie page shows: one added or removed, a state, a mark or where the
/// viewer is. Not the progress of one under way, which moves every second
/// the engine runs (PLAN-APPLE-REVIEW.md §4.3.6). It reads the downloads in
/// a view of its own, so their ticks redraw this empty view and not the page.
struct DownloadsChangeWatcher: View {
    let downloads: any DownloadRepository
    let changed: () -> Void

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .onChange(of: Self.key(downloads.downloads)) { changed() }
    }

    /// The downloads without what a running one changes as it goes.
    static func key(_ downloads: [OfflineDownload]) -> [OfflineDownload] {
        downloads.map { download in
            var key = download
            switch download.status {
            case .downloading: key.status = .downloading(percent: 0, bytes: 0)
            case .paused: key.status = .paused(percent: 0, bytes: 0)
            default: break
            }
            if download.status.coming { key.sizeBytes = 0 }
            return key
        }
    }
}
