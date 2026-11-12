import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Your requests on Apple TV: the iPhone's list
/// (MyRequests.dc.html) at ten feet, each request a focusable row with its
/// state; Select cancels one that waits, after asking, or opens one in the library.
struct MyRequestsScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        MyRequestsView(model: MyRequestsModel(requests: services.requests))
    }
}

private struct MyRequestsView: View {
    @State var model: MyRequestsModel
    @State private var cancelling: MediaRequest?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 24) {
                Text("Your requests")
                    .font(.system(size: 52, weight: .heavy))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                switch model.state {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 80)
                case .failed(let message):
                    TVErrorState(title: "Couldn't load your requests", message: message, retry: model.retry)
                case .loaded(let list) where list.isEmpty:
                    TVEmptyState(systemImage: "tray", title: "No requests yet", message: model.emptyMessage)
                case .loaded(let list):
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(list) { request in
                            Button { select(request) } label: { RequestRow(request: request, imageURL: services.library.imageURL(request.posterPath, size: .w342)) }
                                .buttonStyle(.borderless)
                        }
                    }
                    .frame(maxWidth: 1300, alignment: .leading)
                    .focusSection()
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.bottom, 60)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .alert(
            cancelling.map { "Cancel your request for \($0.title)?" } ?? "",
            isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
            presenting: cancelling
        ) { request in
            Button("Cancel request", role: .destructive) { Task { await model.cancel(request) } }
            Button("Keep it", role: .cancel) {}
        }
    }

    private func select(_ request: MediaRequest) {
        if request.status == .pending {
            cancelling = request
        } else if request.status == .available, let id = request.movieID {
            navigator.open(.movie(id: id))
        } else if request.status == .available, let id = request.showID {
            navigator.open(.show(id: id, season: request.seasons.first))
        }
    }
}

private struct RequestRow: View {
    let request: MediaRequest
    let imageURL: URL?
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(alignment: .center, spacing: 24) {
            Artwork(url: imageURL, seed: request.title, maxPixel: 200)
                .frame(width: 64, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(request.title).font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text)
                Text("\(request.what) · \(formatWhen(request.createdAt))").font(.system(size: 18)).foregroundStyle(Palette.muted)
                StatusLineView(request.stateLine, fontSize: 18, barWidth: 440)
            }
            Spacer(minLength: 12)
            if request.status == .pending {
                Text("Cancel request").font(.system(size: 18, weight: .semibold)).foregroundStyle(focused ? Palette.text : Palette.muted)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(focused ? Color.white.opacity(0.10) : .white.opacity(0.04), in: RoundedRectangle(cornerRadius: 18))
        .focusRim(focused, cornerRadius: 18, scale: 1.015)
        .accessibilityElement(children: .combine)
    }
}

/// Settings › Inbox on Apple TV, for admins: the iPhone's Inbox
/// (Inbox.dc.html) at ten feet. Requests to approve or decline (with an
/// optional note from the keyboard or dictation), and problems to mark resolved.
struct InboxScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        InboxView(model: InboxModel(requests: services.requests))
    }
}

private struct InboxView: View {
    @State var model: InboxModel
    @Environment(AppServices.self) private var services

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 24) {
                Text("Inbox")
                    .font(.system(size: 52, weight: .heavy))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 4) {
                    ForEach(InboxModel.Tab.allCases, id: \.self) { tab in
                        Button(model.count(tab).map { "\(tab.title)  \($0)" } ?? tab.title) { model.tab = tab }
                            .buttonStyle(TVChipStyle(selected: model.tab == tab, height: 52, fontSize: 21))
                    }
                }
                .padding(6)
                .background(.white.opacity(0.08), in: Capsule())
                .focusSection()
                switch model.state {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 80)
                case .failed(let message):
                    TVErrorState(title: "Couldn't load the Inbox", message: message, retry: model.retry)
                case .loaded:
                    VStack(alignment: .leading, spacing: 16) {
                        if model.tab == .requests { requests } else { problems }
                    }
                    .frame(maxWidth: 1300, alignment: .leading)
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.bottom, 60)
        }
        .task { await model.run() }
        .messagePill($model.message)
    }

    @ViewBuilder private var requests: some View {
        if model.viaSeerr {
            HStack(spacing: 24) {
                if let url = model.seerrRequestsURL { QRCodeView(url.absoluteString).frame(width: 160, height: 160) }
                Text("Requests are approved in Seerr").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text)
            }
        }
        if model.requestList.isEmpty && !model.viaSeerr {
            TVEmptyState(systemImage: "tray", title: "Nothing to approve", message: "Requests from people who need approval wait here.")
        } else {
            ForEach(model.requestList) { request in
                card {
                    HStack(alignment: .top, spacing: 24) {
                        Artwork(url: services.library.imageURL(request.posterPath, size: .w342), seed: request.title, maxPixel: 200)
                            .frame(width: 80, height: 120)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 14) {
                            Text("\(request.requestedBy) asked for \(request.inboxSeasons.map { "\($0) of " } ?? "")\(request.title) · \(formatWhen(request.createdAt))")
                                .font(.system(size: 22))
                                .foregroundStyle(Palette.text)
                            if request.status != .pending { StatusLineView(request.stateLine, fontSize: 18) }
                            if request.needsDecision { decision(request) }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private func decision(_ request: MediaRequest) -> some View {
        if model.declining == request.id {
            TextField(declinePrompt(request.requestedBy), text: $model.note)
                .font(.system(size: 20))
                .frame(maxWidth: 900)
            HStack(spacing: 18) {
                Button("Decline") { Task { await model.decline(request) } }
                    .buttonStyle(TVButtonStyle(kind: .primary, height: 56, fontSize: 20))
                Button("Cancel") { model.stopDeclining() }
                    .buttonStyle(TVButtonStyle(height: 56, fontSize: 20))
            }
            .disabled(model.busy != nil)
            .focusSection()
        } else {
            HStack(spacing: 18) {
                Button("Approve") { Task { await model.approve(request) } }
                    .buttonStyle(TVButtonStyle(kind: .primary, height: 56, fontSize: 20))
                Button("Decline") { model.startDeclining(request) }
                    .buttonStyle(TVButtonStyle(height: 56, fontSize: 20))
            }
            .disabled(model.busy != nil)
            .focusSection()
        }
    }

    @ViewBuilder private var problems: some View {
        if model.problems.isEmpty {
            TVEmptyState(systemImage: "checkmark.circle", title: "No problems", message: "Problems people report with a file wait here.")
        } else {
            ForEach(model.problems) { problem in
                card {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("\(problem.reportedBy) reported \(problem.kind.label) on \(problem.title) · \(formatWhen(problem.createdAt))")
                            .font(.system(size: 22))
                            .foregroundStyle(Palette.text)
                        if !problem.note.isEmpty {
                            Text("“\(problem.note)”").font(.system(size: 22)).foregroundStyle(Palette.textSecondary)
                        }
                        if !problem.details.isEmpty {
                            Text(problem.details).font(.system(size: 17)).foregroundStyle(Palette.muted)
                        }
                        Button("Mark resolved") { Task { await model.resolve(problem) } }
                            .buttonStyle(TVButtonStyle(height: 56, fontSize: 20))
                            .disabled(model.busy != nil)
                    }
                }
            }
        }
    }

    private func card(@ViewBuilder _ content: () -> some View) -> some View {
        content()
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 20))
            .focusSection()
    }
}
