import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Inbox, for admins (Inbox.dc.html): Requests to approve, or
/// decline with a note for whoever asked, and Problems to mark resolved.
/// With Seerr carrying requests out, approvals happen there instead.
struct InboxScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        InboxView(model: InboxModel(requests: services.requests))
    }
}

private struct InboxView: View {
    @State var model: InboxModel
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass
    @FocusState private var noteFocused: Bool
    @State private var selectedRequest: Int?
    @State private var selectedProblem: Int?

    var body: some View {
        GeometryReader { geometry in
            if sizeClass.isWide && geometry.size.width >= 700 {
                HStack(alignment: .top, spacing: 24) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            tabs
                            selectionList
                        }
                        .padding(.vertical, 8)
                    }
                    .frame(width: min(340, geometry.size.width * 0.42))
                    ScrollView { selectedDetail.padding(.vertical, 8) }
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, PadLayout.margin)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        tabs
                        content
                    }
                    .padding(.horizontal, sizeClass.isWide ? PadLayout.margin : 16)
                    .padding(.vertical, 8)
                    .frame(maxWidth: sizeClass.isWide ? 720 : .infinity, alignment: .leading)
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .groundBackground()
        .navigationTitle("Inbox")
        .navigationBarTitleDisplayMode(.large)
        .task { await model.run() }
        .messageToast($model.message)
    }

    private var tabs: some View {
        Picker("Inbox", selection: $model.tab) {
            ForEach(InboxModel.Tab.allCases, id: \.self) { tab in
                Text(model.count(tab).map { "\(tab.title) \($0)" } ?? tab.title).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .onChange(of: model.tab) { _, _ in model.stopDeclining() }
    }

    @ViewBuilder private var selectionList: some View {
        if case .loaded = model.state {
            switch model.tab {
            case .requests:
                if model.viaSeerr {
                    card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Requests are approved in Seerr").scaledFont(15, weight: .semibold)
                            if let url = model.seerrRequestsURL { Link("Open Seerr", destination: url).foregroundStyle(Palette.gold) }
                            if !model.requestList.isEmpty { Text("Asked for before Seerr was connected").scaledFont(13).foregroundStyle(Palette.muted) }
                        }
                    }
                }
                ForEach(model.requestList) { request in
                    selection(title: request.title, subtitle: request.requestedBy, selected: chosenRequest?.id == request.id) {
                        model.stopDeclining()
                        selectedRequest = request.id
                    }
                    .accessibilityIdentifier("inbox-request-\(request.id)")
                }
            case .problems:
                ForEach(model.problems) { problem in
                    selection(title: problem.title, subtitle: "\(problem.reportedBy) · \(problem.kind.label)", selected: chosenProblem?.id == problem.id) {
                        selectedProblem = problem.id
                    }
                    .accessibilityIdentifier("inbox-problem-\(problem.id)")
                }
            }
        } else { content }
    }

    private var chosenRequest: MediaRequest? { model.requestList.first { $0.id == selectedRequest } ?? model.requestList.first }
    private var chosenProblem: Problem? { model.problems.first { $0.id == selectedProblem } ?? model.problems.first }

    @ViewBuilder private var selectedDetail: some View {
        if case .loaded = model.state {
            switch model.tab {
            case .requests:
                if let request = chosenRequest {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(request.title).scaledFont(26, weight: .bold).foregroundStyle(Palette.text)
                        card { requestCard(request) }
                    }
                } else if !model.viaSeerr { EmptyState(systemImage: "tray", title: "Nothing to approve", message: "Requests from people who need approval wait here.") }
            case .problems:
                if let problem = chosenProblem {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(problem.title).scaledFont(26, weight: .bold).foregroundStyle(Palette.text)
                        card { problemCard(problem) }
                    }
                } else { EmptyState(systemImage: "checkmark.circle", title: "No problems", message: "Problems people report with a file wait here.") }
            }
        }
    }

    private func selection(title: String, subtitle: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).scaledFont(16, weight: .semibold).foregroundStyle(selected ? Palette.gold : Palette.text)
                Text(subtitle).scaledFont(13).foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(selected ? Palette.gold.opacity(0.12) : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(.top, 80)
        case .failed(let message):
            ErrorState(message: message, retry: model.retry).padding(.top, 40)
        case .loaded:
            switch model.tab {
            case .requests: requests
            case .problems: problems
            }
        }
    }

    @ViewBuilder private var requests: some View {
        if model.viaSeerr {
            card {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "arrow.up.right.square")
                        .foregroundStyle(Palette.gold)
                        .frame(width: 30, height: 30)
                        .background(Palette.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Requests are approved in Seerr").scaledFont(15, weight: .semibold).foregroundStyle(Palette.text)
                        if let url = model.seerrRequestsURL {
                            Link("Open Seerr", destination: url).scaledFont(15, weight: .semibold).foregroundStyle(Palette.gold)
                        }
                    }
                }
            }
        }
        if model.requestList.isEmpty && !model.viaSeerr {
            EmptyState(systemImage: "tray", title: "Nothing to approve", message: "Requests from people who need approval wait here.")
                .padding(.top, 40)
        } else {
            ForEach(model.requestList) { request in
                card { requestCard(request) }
            }
        }
    }

    private func requestCard(_ request: MediaRequest) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                poster(request.posterPath, title: request.title, width: 44, height: 66)
                VStack(alignment: .leading, spacing: 6) {
                    Text(askedLine(request)).scaledFont(15).lineSpacing(2).fixedSize(horizontal: false, vertical: true)
                    if request.status != .pending { StatusLineView(request.stateLine, fontSize: 13) }
                }
            }
            if request.needsDecision {
                if model.declining == request.id {
                    TextField(declinePrompt(request.requestedBy), text: $model.note, axis: .vertical)
                        .lineLimit(2, reservesSpace: true)
                        .focused($noteFocused)
                        .scaledFont(15)
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outlineStrong))
                        .onAppear { noteFocused = true }
                    HStack(spacing: 8) {
                        Button("Cancel") { model.stopDeclining() }
                            .buttonStyle(GhostButtonStyle(height: 40))
                        Button { Task { await model.decline(request) } } label: {
                            BusyLabel(title: "Decline", busy: model.busy == request.id)
                        }
                        .buttonStyle(PrimaryButtonStyle(height: 40))
                    }
                    .disabled(model.busy != nil)
                } else {
                    HStack(spacing: 8) {
                        Button { Task { await model.approve(request) } } label: {
                            BusyLabel(title: "Approve", busy: model.busy == request.id)
                        }
                        .buttonStyle(PrimaryButtonStyle(height: 40))
                        Button("Decline") { model.startDeclining(request) }
                            .buttonStyle(GhostButtonStyle(height: 40))
                    }
                    .disabled(model.busy != nil)
                }
            }
        }
    }

    /// "**Alex** asked for **Season 2** of *Copperline* · 2 h ago".
    private func askedLine(_ request: MediaRequest) -> AttributedString {
        var line = bold(request.requestedBy) + plain(" asked for ")
        if let seasons = request.inboxSeasons { line += bold(seasons) + plain(" of ") }
        line += emphasis(request.title) + muted(" · \(unbroken(formatWhen(request.createdAt)))")
        return line
    }

    @ViewBuilder private var problems: some View {
        if model.problems.isEmpty {
            EmptyState(systemImage: "checkmark.circle", title: "No problems", message: "Problems people report with a file wait here.")
                .padding(.top, 40)
        } else {
            ForEach(model.problems) { problem in
                card {
                    problemCard(problem)
                }
            }
        }
    }

    private func problemCard(_ problem: Problem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Color.clear
                    .overlay { DashedPlaceholder(cornerRadius: 6) }
                    .frame(width: problem.itemType == .episode ? 96 : 44, height: problem.itemType == .episode ? 54 : 66)
                    .accessibilityHidden(true)
                Text(bold(problem.reportedBy) + plain(" reported ") + bold(problem.kind.label) + plain(" on ") + emphasis(problem.title) + muted(" · \(unbroken(formatWhen(problem.createdAt)))"))
                    .scaledFont(15)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !problem.note.isEmpty {
                Text("“\(problem.note)”").scaledFont(15).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
            }
            if !problem.details.isEmpty {
                Label(problem.details, systemImage: "doc")
                    .scaledFont(12)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button { Task { await model.resolve(problem) } } label: {
                BusyLabel(title: "Mark resolved", busy: model.busy == problem.id)
            }
            .buttonStyle(GhostButtonStyle(height: 40, fullWidth: false))
            .disabled(model.busy != nil)
        }
    }

    private func card(@ViewBuilder _ content: () -> some View) -> some View {
        content()
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
    }

    private func poster(_ path: String?, title: String, width: CGFloat, height: CGFloat) -> some View {
        Artwork(url: services.library.imageURL(path, size: .w185), seed: title, maxPixel: 200)
            .frame(width: width, height: height)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .accessibilityHidden(true)
    }

    /// "2 h ago" kept on one line.
    private func unbroken(_ s: String) -> String { s.replacingOccurrences(of: " ", with: "\u{00A0}") }

    private func bold(_ s: String) -> AttributedString {
        var a = AttributedString(s)
        a.foregroundColor = Palette.text
        a.inlinePresentationIntent = .stronglyEmphasized
        return a
    }

    private func emphasis(_ s: String) -> AttributedString {
        var a = AttributedString(s)
        a.foregroundColor = Palette.text
        a.inlinePresentationIntent = .emphasized
        return a
    }

    private func plain(_ s: String) -> AttributedString {
        var a = AttributedString(s)
        a.foregroundColor = Palette.textSecondary
        return a
    }

    private func muted(_ s: String) -> AttributedString {
        var a = AttributedString(s)
        a.foregroundColor = Palette.muted
        return a
    }
}
