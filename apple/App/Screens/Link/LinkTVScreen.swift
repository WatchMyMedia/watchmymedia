import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// Link a TV (Settings2LinkTvCode, Settings2LinkTvCheck, Settings2LinkTvDone,
/// Pad2Settings2LinkTv; PLAN-APPLE-SETTINGS.md §5.5): the web's three steps
/// in a sheet: Enter the code, Check it's yours, Linked. The first button is
/// off until six characters are in and says so; the second names the TV;
/// the last screen has no way back, since the TV is signed in, and Done
/// closes it. The model is ``AppFlow``'s, so a resized window keeps the step.
struct LinkTVSheet: View {
    @State private var model: LinkTVModel
    let close: () -> Void
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @State private var askingToStop = false

    init(model: LinkTVModel, close: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.close = close
    }

    var body: some View {
        NavigationStack {
            LinkTVSteps(model: model, close: close, another: linkAnother)
        }
        .taskSheet()
        .asksBeforeDismissing(when: typed) { askingToStop = true }
        .confirmationDialog("Stop linking this TV?", isPresented: $askingToStop, titleVisibility: .visible) {
            Button("Stop linking", role: .destructive, action: close)
            Button("Keep linking", role: .cancel) {}
        } message: {
            Text("Nothing is linked yet. The TV keeps showing its code.")
        }
    }

    /// A code is typed and the TV isn't linked yet: swiping the sheet away asks first.
    private var typed: Bool {
        if case .approved = model.step { return false }
        return !model.code.isEmpty
    }

    /// Link another TV, from the last step: the first step again, empty.
    private func linkAnother() {
        flow.forgetLinkTV()
        model = flow.linkTVModel(auth: services.auth, code: nil)
    }
}

/// The step showing, in the task's frame.
private struct LinkTVSteps: View {
    let model: LinkTVModel
    let close: () -> Void
    let another: () -> Void
    @FocusState private var codeFocused: Bool

    private static let names = ["Enter the code", "Check it’s yours", "Linked"]

    private var index: Int {
        switch model.step {
        case .enter: 0
        case .found: 1
        case .approved: 2
        }
    }

    var body: some View {
        TaskStepSwitcher(index: index) {
            switch model.step {
            case .enter: enter
            case .found(let device): check(device)
            case .approved(let device): linked(device)
            }
        }
        .task {
            await model.start()
            if model.step == .enter { codeFocused = true }
            // `-linkTVApprove` (DEBUG) approves at once, to screenshot the last step.
            if ScreenshotLaunch.has("-linkTVApprove") { await model.approve() }
        }
    }

    // MARK: Step 1, Enter the code

    private var enter: some View {
        TaskFrame(title: "Link a TV", steps: TaskSteps(names: Self.names, index: 0),
                  heading: "Enter the code on the TV",
                  lede: "The TV shows a six-character code on its sign-in screen. Scanning the TV’s QR code opens this step with the code filled in.",
                  cancel: close, problem: problem, busy: model.busy) {
            CodeField(code: model.code, onChange: model.setCode, onSubmit: lookUp, focused: $codeFocused)
                .disabled(model.busy)
                .accessibilityIdentifier("link-code")
            GroupFootnote("Codes last 10 minutes. A wrong code counts like a failed sign-in.")
        } actions: {
            Button(action: lookUp) { BusyLabel(title: "Check this code", busy: model.busy) }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .disabled(model.busy || !model.complete)
                .accessibilityIdentifier("link-check")
            if !model.complete {
                Text("Check is off until all six characters are in.")
                    .font(.footnote)
                    .foregroundStyle(Palette.faint)
            }
        }
    }

    // MARK: Step 2, Check it's yours

    private func check(_ device: PendingDevice) -> some View {
        TaskFrame(title: "Link a TV", steps: TaskSteps(names: Self.names, index: 1),
                  heading: "Is this your TV?", lede: "Approve only a TV that is showing this code right now.",
                  back: TaskBack(title: "Code", action: backToCode), cancel: close, problem: problem, busy: model.busy) {
            CodeBoxes(code: device.code, size: .large, dim: true, boxBackground: Palette.surface)
            SettingsSection(title: "This TV") {
                FactRow(label: "Name", value: device.name)
                if let app = device.appLabel { FactRow(label: "App", value: app) }
                FactRow(label: "Address", value: device.ip ?? "Unknown")
                if let asked = device.askedAt { FactRow(label: "Asked", value: formatAgo(asked)) }
            }
            Text("Approving signs this TV in as \(Text(model.userName).bold()). Anyone with its remote can watch as you until you sign it out under Signed-in devices.")
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        } actions: {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    notMine.frame(maxWidth: 200)
                    approve(device)
                }
                .frame(minWidth: 480)
                VStack(spacing: 10) {
                    approve(device)
                    notMine
                }
            }
        }
    }

    private func approve(_ device: PendingDevice) -> some View {
        Button { Task { await model.approve() } } label: { BusyLabel(title: "Approve \(device.name)", busy: model.busy) }
            .buttonStyle(PrimaryButtonStyle())
            .keyboardShortcut(.defaultAction)
            .disabled(model.busy)
            .accessibilityIdentifier("link-approve")
    }

    private var notMine: some View {
        Button("Not my TV", action: close)
            .buttonStyle(GhostButtonStyle(height: 50))
            .disabled(model.busy)
            .accessibilityIdentifier("link-not-mine")
    }

    // MARK: Step 3, Linked

    private func linked(_ device: PendingDevice) -> some View {
        TaskFrame(title: "Link a TV", steps: TaskSteps(names: Self.names, index: 2)) {
            VStack(alignment: .leading, spacing: 10) {
                TaskDoneMark()
                Text("\(device.name) is signed in")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("It shows Home in a moment, signed in as \(model.userName).")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
            }
            TaskDone(changed: ["\(device.name) is signed in as \(model.userName).", "It is listed under Signed-in devices."]) {
                Text("It stays signed in until you sign it out there, or change your password.")
                Button("Link another TV", action: another)
                    .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                    .accessibilityIdentifier("link-another")
            }
        } actions: {
            Button("Done", action: close)
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("link-done")
        }
    }

    // MARK: Actions

    /// Why the last step was refused: nothing was linked, and why.
    private var problem: TaskProblem? {
        model.error.map { TaskProblem(title: "Nothing was linked", message: $0) }
    }

    private func lookUp() {
        codeFocused = false
        Task { await model.lookUp() }
    }

    /// Back to the code, as typed: a change of code is what takes the model back a step.
    private func backToCode() {
        let code = model.code
        model.setCode("")
        model.setCode(code)
        codeFocused = true
    }
}

#if DEBUG
#Preview {
    Color.clear.sheet(isPresented: .constant(true)) {
        LinkTVSheet(model: LinkTVModel(auth: AppServices.fake().auth, code: "K7M4PQ"), close: {})
    }
    .environment(AppServices.fake())
    .environment(AppFlow())
    .preferredColorScheme(.dark)
}
#endif
