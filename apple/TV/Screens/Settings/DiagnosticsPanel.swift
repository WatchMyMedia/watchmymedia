import SwiftUI
import WMMData
import WMMFeatures
import WMMModel
import WMMUI

/// Settings' Help group (TvDiagnostics.dc.html, PLAN-APPLE-REVIEW.md §6.4):
/// the Diagnostics row, which opens the redacted diagnostics as a QR code to
/// scan with a phone (Apple TV has no clipboard to paste from), beside a
/// summary of what it holds, with Show as text. Nothing is sent.
struct DiagnosticsGroup: View {
    @Environment(AppServices.self) private var services
    @State private var shown: DiagnosticsShown?
    @State private var reports = 0

    var body: some View {
        SettingsGroup("Help") {
            Button(action: open) {
                SettingsRow(title: "Diagnostics", detail: "Show what the app can give you for a bug report") {
                    HStack(spacing: 8) {
                        if reports > 0 { Text(plural(reports, "report")) }
                        Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                    }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("settings-diagnostics")
            Text("Nothing is sent from this Apple TV. No account, server address or titles are included.")
                .font(.system(size: 16))
                .foregroundStyle(Palette.faint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 18)
                .padding(.top, 4)
        }
        .onAppear { reports = DiagnosticsStore.shared.latest().count }
        .fullScreenCover(item: $shown) { shown in
            DiagnosticsPanel(code: shown.code) { self.shown = nil }
        }
        #if DEBUG
        .task {
            if ProcessInfo.processInfo.arguments.contains("-diagnostics") { open() }
        }
        #endif
    }

    /// Makes the text (reading the log takes a moment), then shows it.
    private func open() {
        let server = services.servers.current?.server
        let saved = services.servers.saved
        // Nothing that names the account or this Apple TV goes with it.
        let names = [services.auth.deviceName, services.auth.user?.name].compactMap { $0 }
        #if DEBUG
        // `-diagnosticsLog`: made-up log lines, enough to fill the code and turn pages.
        let sample = ProcessInfo.processInfo.arguments.contains("-diagnosticsLog")
        #else
        let sample = false
        #endif
        Task {
            let code = await Task.detached(priority: .userInitiated) {
                var report = Diagnostics.report(server: server)
                if sample { report.logLines += Self.sampleLog }
                return DiagnosticsCode(report: report, redactor: Diagnostics.redactor(servers: saved, current: server, names: names))
            }.value
            shown = DiagnosticsShown(code: code)
        }
    }
}

extension DiagnosticsGroup {
    /// Sixty made-up lines, an address among them, for `-diagnosticsLog`.
    nonisolated static var sampleLog: [String] {
        (0..<60).map { i in
            String(format: "21:%02d:%02d info [%@] %@", 14 + i / 30, (i * 7) % 60,
                   ["live", "guide", "player", "events"][i % 4],
                   ["tuned 4.2 in 1.8 s", "loaded 12 channels, 0.62 s", "stall 2.1 s, switched to 1080p from http://192.168.1.20:27587/hls", "reconnected after 4 s"][i % 4])
        }
    }
}

private struct DiagnosticsShown: Identifiable {
    let id = UUID()
    let code: DiagnosticsCode
}

/// The code over the dimmed Settings (the board's `qr` screen), or the whole
/// text in pages (`text`). Done closes either; Menu on the text goes back
/// to the code.
private struct DiagnosticsPanel: View {
    let code: DiagnosticsCode
    let onClose: () -> Void
    @State private var asText = false

    var body: some View {
        if asText {
            DiagnosticsText(code: code, onBack: { asText = false }, onClose: onClose)
        } else {
            codePanel
        }
    }

    @FocusState private var doneFocused: Bool

    private var codePanel: some View {
        HStack(alignment: .top, spacing: 56) {
            VStack(spacing: 16) {
                QRCodeView(code.text)
                    .frame(width: 420, height: 420)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .accessibilityLabel("QR code holding the diagnostics as text")
                    .accessibilityIdentifier("diagnostics-code")
                Text(code.sizeLine)
                    .font(.system(size: 17))
                    .foregroundStyle(Palette.muted)
            }
            .fixedSize()
            VStack(alignment: .leading, spacing: 18) {
                Text("Diagnostics")
                    .font(.system(size: 42, weight: .heavy))
                    .tracking(-0.84)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("Scan the code with your iPhone’s camera to copy this text, then paste it into a bug report or a message. It goes from the screen to your phone; nothing is sent.")
                    .font(.system(size: 23))
                    .lineSpacing(10)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(code.summary())
                    .font(.system(size: 18, design: .monospaced))
                    .lineSpacing(10)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.vertical, 20)
                    .padding(.horizontal, 24)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityIdentifier("diagnostics-summary")
                HStack(spacing: 16) {
                    Button("Done", action: onClose)
                        .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                        .focused($doneFocused)
                        .accessibilityIdentifier("diagnostics-done")
                    Button("Show as text") { asText = true }
                        .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                        .accessibilityIdentifier("diagnostics-show-text")
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(56)
        .frame(width: 1500)
        .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 32))
        .shadow(color: .black.opacity(0.7), radius: 60, y: 40)
        .padding(.top, 150)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(hex: 0x080809).opacity(0.80))
        .ignoresSafeArea()
        .onExitCommand(perform: onClose)
        .defaultFocus($doneFocused, true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("diagnostics")
    }
}

/// Show as text: the whole text, a page at a time, to read out or
/// photograph. Down and Up turn the pages; Done stays focused.
private struct DiagnosticsText: View {
    let code: DiagnosticsCode
    let onBack: () -> Void
    let onClose: () -> Void
    @State private var page = 0
    @FocusState private var doneFocused: Bool

    /// What a page holds at 24-point monospaced text across the board's box.
    private static let columns = 100
    private static let rows = 16

    var body: some View {
        let pages = code.pages(columns: Self.columns, rows: Self.rows)
        let current = min(page, pages.count - 1)
        VStack(alignment: .leading, spacing: 24) {
            HStack(alignment: .firstTextBaseline) {
                Text("Diagnostics")
                    .font(.system(size: 42, weight: .heavy))
                    .tracking(-0.84)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(DiagnosticsCode.pageLine(current, of: pages.count))
                    .font(.system(size: 20))
                    .foregroundStyle(Palette.muted)
                    .accessibilityIdentifier("diagnostics-page")
            }
            Text("To read out or photograph. Nothing is sent from this Apple TV.")
                .font(.system(size: 22))
                .foregroundStyle(Palette.textSecondary)
            Text(pages[current])
                .font(.system(size: 24, design: .monospaced))
                .lineSpacing(15)
                .foregroundStyle(Palette.textSecondary)
                .padding(.vertical, 28)
                .padding(.horizontal, 34)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
                .clipped()
                .accessibilityIdentifier("diagnostics-text")
            Button("Done", action: onClose)
                .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                .focused($doneFocused)
                .accessibilityIdentifier("diagnostics-text-done")
        }
        .padding(.top, 70)
        .padding(.horizontal, 140)
        .padding(.bottom, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(hex: 0x121214))
        .ignoresSafeArea()
        .onMoveCommand { direction in
            switch direction {
            case .down: page = min(current + 1, pages.count - 1)
            case .up: page = max(current - 1, 0)
            default: break
            }
        }
        .onExitCommand(perform: onBack)
        .defaultFocus($doneFocused, true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("diagnostics-text-page")
    }
}
