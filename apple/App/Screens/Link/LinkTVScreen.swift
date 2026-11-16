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
/// closes it. A code nobody is waiting on, too many wrong codes and a server
/// that can't be reached each say that nothing was linked. The steps and
/// words are the Kit's (`LinkTVModel`, `FlowSteps.swift`); the model is
/// ``AppFlow``'s, so a resized window keeps the step.
struct LinkTVSheet: View {
    let model: LinkTVModel
    let close: () -> Void
    @State private var askingToStop = false

    var body: some View {
        NavigationStack {
            LinkTVSteps(model: model, close: close)
        }
        .taskSheet()
        .asksBeforeDismissing(when: typed) { askingToStop = true }
        .discardDialog(SettingsWords.discardTyped(stays: "Nothing is linked yet. The TV keeps showing its code."), isPresented: $askingToStop, discard: close)
    }

    /// A code is typed and the TV isn't linked yet: swiping the sheet away asks first.
    private var typed: Bool {
        if case .approved = model.step { return false }
        return !model.code.isEmpty || model.busy
    }
}

/// The step showing, in the task's frame.
private struct LinkTVSteps: View {
    let model: LinkTVModel
    let close: () -> Void
    @FocusState private var codeFocused: Bool

    var body: some View {
        TaskStepSwitcher(index: model.flowStep.number) {
            switch model.step {
            case .enter: enter
            case .found(let device): check(device)
            case .approved: linked
            }
        }
        .task {
            await model.start()
            if model.step == .enter { codeFocused = true }
            // `-linkTVApprove` (DEBUG) approves at once, to screenshot the last step.
            if ScreenshotLaunch.has("-linkTVApprove") { await model.approve() }
        }
    }

    private var title: String { SettingsWords.linkATV }

    // MARK: Step 1, Enter the code

    private var enter: some View {
        TaskFrame(title: title, steps: model.flowStep, heading: model.title, lede: model.lede,
                  cancel: close, problem: problem, busy: model.busy) {
            CodeField(code: model.code, onChange: model.setCode, onSubmit: lookUp, focused: $codeFocused)
                .disabled(model.busy)
                .accessibilityIdentifier("link-code")
            GroupFootnote(LinkTVModel.codeNote)
        } actions: {
            Button(action: lookUp) { BusyLabel(title: model.primaryTitle, busy: model.busy) }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                // Off until six characters are in, and for a minute after too many wrong codes.
                .disabled(!model.canCheck)
                .accessibilityHint(model.primaryOffReason ?? "")
                .accessibilityIdentifier("link-check")
            if let reason = model.primaryOffReason {
                Text(reason).font(.footnote).foregroundStyle(Palette.faint)
            }
        }
    }

    // MARK: Step 2, Check it's yours

    private func check(_ device: PendingDevice) -> some View {
        TaskFrame(title: title, steps: model.flowStep, heading: model.title, lede: model.lede,
                  back: TaskBack(title: LinkTVModel.backToCodeTitle, action: backToCode), cancel: close,
                  problem: problem, busy: model.busy) {
            CodeBoxes(code: device.code, size: .large, dim: true, boxBackground: Palette.surface)
                .accessibilityLabel(model.codeAccessibilityLabel)
            SettingsSection(title: LinkTVModel.thisTV) {
                ForEach(model.deviceRows(), id: \.label) { row in FactRow(label: row.label, value: row.value) }
            }
            Text(model.approveNote)
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        } actions: {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    notMine.frame(maxWidth: 200)
                    approve
                }
                .frame(minWidth: 480)
                VStack(spacing: 10) {
                    approve
                    notMine
                }
            }
        }
    }

    private var approve: some View {
        Button { Task { await model.approve() } } label: { BusyLabel(title: model.primaryTitle, busy: model.busy) }
            .buttonStyle(PrimaryButtonStyle())
            .keyboardShortcut(.defaultAction)
            .disabled(model.busy)
            .accessibilityIdentifier("link-approve")
    }

    private var notMine: some View {
        Button(LinkTVModel.notMyTV, action: close)
            .buttonStyle(GhostButtonStyle(height: 50))
            .disabled(model.busy)
            .accessibilityIdentifier("link-not-mine")
    }

    // MARK: Step 3, Linked

    @ViewBuilder private var linked: some View {
        TaskFrame(title: title, steps: model.flowStep) {
            if let words = model.finished {
                TaskDone(words: words) {
                    Button(LinkTVModel.linkAnother) { model.startAgain(); codeFocused = true }
                        .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                        .accessibilityIdentifier("link-another")
                }
            }
        } actions: {
            Button(model.primaryTitle, action: close)
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("link-done")
        }
    }

    // MARK: Actions

    /// Why the last step was refused, ending in "Nothing was linked."
    private var problem: TaskProblem? {
        model.problem.map { TaskProblem(title: $0.title, message: $0.body) }
    }

    private func lookUp() {
        codeFocused = false
        Task { await model.lookUp() }
    }

    private func backToCode() {
        model.backToCode()
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
