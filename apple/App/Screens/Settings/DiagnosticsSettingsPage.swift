import OSLog
import SwiftUI
import UIKit
import WMMData
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › About's Help group (Diagnostics.dc.html, Tweak screen
/// "about"): the Diagnostics row, how many system reports are kept, and the
/// note that nothing is sent. On iPad it pushes inside Settings' detail column.
struct DiagnosticsAboutSection: View {
    let settings: SettingsModel
    @State private var count: String?

    var body: some View {
        Section {
            NavigationLink {
                DiagnosticsSettingsPage(settings: settings)
            } label: {
                LabeledContent {
                    if let count { Text(count).foregroundStyle(Palette.muted) }
                } label: {
                    Text("Diagnostics").font(.body.weight(.medium)).foregroundStyle(Palette.text)
                }
            }
            .accountRow()
            .accessibilityIdentifier("settings-diagnostics")
        } header: {
            GroupHeading("Help")
        } footer: {
            GroupFootnote("Shows what the app can copy for a bug report. Nothing is sent.")
        }
        .task {
            DiagnosticsSamples.recordIfAsked()
            count = DiagnosticsPage.countLabel(DiagnosticsStore.shared.latest())
        }
    }
}

/// `-diagnosticsReports` (DEBUG) keeps a fictional hang, launch and exits
/// report, as iOS would hand them over on a device, for screenshots and UI
/// tests: the simulator gets none from MetricKit. `-diagnosticsLog` writes a
/// log line with the fake server's name and address, which the page must not show.
@MainActor
enum DiagnosticsSamples {
    private static var recorded = false

    static func recordIfAsked() {
        #if DEBUG
        guard !recorded else { return }
        recorded = true
        if ScreenshotLaunch.has("-diagnosticsReports") {
            let day = Date().addingTimeInterval(-86_400)
            DiagnosticsStore.shared.record([
                DiagnosticSummary(kind: .hang, receivedAt: day, lines: ["Hang 2.4 sec", "WatchMyMedia +0x1a2b3c"]),
                DiagnosticSummary(kind: .launch, receivedAt: day, lines: ["Launch took 1.9 sec"]),
                DiagnosticSummary(kind: .exits, receivedAt: day.addingTimeInterval(-86_400), lines: ["1 for memory"]),
            ])
        } else if ScreenshotLaunch.has("-diagnosticsNoReports") {
            DiagnosticsStore.shared.clear()
        }
        if ScreenshotLaunch.has("-diagnosticsLog") {
            Logger(subsystem: Diagnostics.subsystem, category: "sample")
                .error("could not reach Living room server at http://192.168.1.20:27587/api/play?token=abc123secret456def789")
        }
        #endif
    }
}

/// Settings › About › Diagnostics (Diagnostics.dc.html, Tweak screen
/// "page"; PLAN-APPLE-REVIEW.md §6.4): everything Copy puts on the clipboard,
/// in groups: the app, this device and the server; iOS's last reports; and
/// the app's recent log. Copy turns into Copied with a short Copied note. No
/// Share: nothing leaves unless the person pastes it.
struct DiagnosticsSettingsPage: View {
    let settings: SettingsModel
    @Environment(AppServices.self) private var services
    @State private var model: DiagnosticsModel?

    var body: some View {
        // A scroll of rounded groups, as the Settings root draws them, so the
        // text and the Copy button sit on the ground above the groups.
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("This is everything Copy puts on the clipboard, as plain text. Paste it into a bug report or a message yourself; the app sends nothing. No account, server address or titles are included.")
                    .font(.subheadline)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let model {
                    Button { model.copy() } label: {
                        Label(model.copyLabel, systemImage: model.copied ? "checkmark" : "doc.on.doc")
                    }
                    .buttonStyle(CopyButtonStyle(done: model.copied))
                    .disabled(model.page == nil)
                    .accessibilityIdentifier("diagnostics-copy")
                }
                if let page = model?.page {
                    group("App and server") {
                        fact("App", page.app)
                        fact(page.deviceLabel, page.device)
                        fact("Server", page.server)
                    }
                    group("Reports from iOS", note: page.reportsNote) {
                        if page.reports.isEmpty {
                            Text("No reports yet").font(.body.weight(.medium)).foregroundStyle(Palette.text)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.horizontal, 14)
                        }
                        ForEach(page.reports) { fact($0.label, $0.value) }
                    }
                    group("Recent log · last \(DiagnosticsReport.maxLogLines) lines") {
                        Text(page.log.isEmpty ? "Nothing logged since the app opened." : page.log.joined(separator: "\n"))
                            .font(.system(size: 11, design: .monospaced))
                            .lineSpacing(3)
                            .foregroundStyle(Palette.textSecondary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .accessibilityIdentifier("diagnostics-log")
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 24)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .groundBackground()
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if model?.showsCopiedNote == true { CopiedNote().transition(.opacity) }
        }
        .animation(.easeInOut(duration: 0.2), value: model?.showsCopiedNote)
        .task {
            DiagnosticsSamples.recordIfAsked()
            if model == nil { model = makeModel() }
            await model?.load()
        }
        .onDisappear { model?.dispose() }
    }

    private func makeModel() -> DiagnosticsModel {
        let servers = services.servers
        let current = servers.current?.server
        let saved = servers.saved
        // Names the rules can't know: this device's, the account's, servers
        // found on the network, and the titles kept on this device.
        var names = [settings.deviceName, settings.user?.name].compactMap { $0 }
        for found in servers.discovered {
            names.append(found.name)
            if let host = found.baseURL.host() { names.append(host) }
        }
        for download in services.downloads.downloads {
            names.append(download.item.title)
            if let show = download.item.showTitle { names.append(show) }
        }
        let family = settings.facts.family
        return DiagnosticsModel(
            make: { [names] in
                await Task.detached(priority: .userInitiated) {
                    DiagnosticsPage(report: Diagnostics.report(server: current),
                                    redactor: Diagnostics.redactor(servers: saved, current: current, names: names),
                                    deviceLabel: family)
                }.value
            },
            pasteboard: { UIPasteboard.general.string = $0 }
        )
    }

    private func group(_ title: String, note: String? = nil, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(title).padding(.horizontal, 16)
            RoundedGroup(content: rows)
            if let note { GroupFootnote(note).padding(.horizontal, 16).padding(.top, 2) }
        }
    }

    /// "App   1.9.0 (10500012)": the label, and the value at the right (under it when long).
    private func fact(_ label: String, _ value: String) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(label).foregroundStyle(Palette.text)
                Spacer(minLength: 8)
                Text(value).font(.subheadline).foregroundStyle(Palette.muted).lineLimit(1)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(label).foregroundStyle(Palette.text)
                Text(value).font(.subheadline).foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .frame(minHeight: 44)
    }
}

/// The gold Copy button; once copied, a faint gold one that says Copied.
/// Its text and height follow the system's text size.
private struct CopyButtonStyle: ButtonStyle {
    let done: Bool
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 50

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(done ? Palette.gold : Palette.ground)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(done ? Palette.gold.opacity(0.16) : Palette.gold, in: RoundedRectangle(cornerRadius: 12))
            .opacity(configuration.isPressed ? 0.8 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 12))
    }
}

/// iOS shows nothing when an app copies, so a short Copied note does.
private struct CopiedNote: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "checkmark")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(Palette.gold)
            Text("Copied").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
        }
        .frame(width: 150)
        .padding(.top, 18)
        .padding(.bottom, 16)
        .background(Color(hex: 0x2C2C30).opacity(0.94), in: RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.6), radius: 25, y: 20)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("diagnostics-copied")
        .onAppear { AccessibilityNotification.Announcement("Copied").post() }
    }
}
