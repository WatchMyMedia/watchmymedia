import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Your requests (MyRequests.dc.html): each request with its
/// poster, what was asked for and when, and its state; Cancel request while
/// it waits for approval. A request in the library opens its page.
struct MyRequestsScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        MyRequestsView(model: MyRequestsModel(requests: services.requests))
    }
}

private struct MyRequestsView: View {
    @State var model: MyRequestsModel
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        Group {
            switch model.state {
            case .loading:
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorState(message: message, retry: model.retry)
            case .loaded(let list) where list.isEmpty:
                EmptyState(systemImage: "tray", title: "No requests yet", message: model.emptyMessage)
            case .loaded(let list):
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(list.enumerated()), id: \.element.id) { index, request in
                            row(request)
                                .overlay(alignment: .top) { if index > 0 { RowSeparator() } }
                        }
                    }
                    .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                    .padding(.horizontal, sizeClass.isWide ? PadLayout.margin : 16)
                    .padding(.vertical, 8)
                }
            }
        }
        .groundBackground()
        .navigationTitle("Your requests")
        .navigationBarTitleDisplayMode(.large)
        .task { await model.run() }
        .messageToast($model.message)
    }

    private func row(_ request: MediaRequest) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Artwork(url: services.library.imageURL(request.posterPath, size: .w185), seed: request.title, maxPixel: 200)
                .frame(width: 40, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(request.title).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1)
                Text("\(request.what) · \(formatWhen(request.createdAt))").scaledFont(13).foregroundStyle(Palette.muted)
                StatusLineView(request.stateLine, fontSize: 13)
                    .padding(.top, 2)
                if request.status == .pending {
                    Button { Task { await model.cancel(request) } } label: {
                        BusyLabel(title: "Cancel request", busy: model.cancelling == request.id)
                    }
                    .buttonStyle(SmallGhostButtonStyle())
                    .accessibilityLabel("Cancel request for \(request.title)")
                    .disabled(model.cancelling != nil)
                    .padding(.top, -4)
                }
            }
            Spacer(minLength: 0)
            if request.status == .available, page(request) != nil {
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.faint).padding(.top, 20)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .onTapGesture { if request.status == .available, let route = page(request) { navigator.open(route) } }
        .accessibilityElement(children: .contain)
    }

    /// The movie's or show's page, once it is in the library.
    private func page(_ request: MediaRequest) -> Route? {
        if let id = request.movieID { return .movie(id: id) }
        if let id = request.showID { return .show(id: id, season: request.seasons.first) }
        return nil
    }
}
