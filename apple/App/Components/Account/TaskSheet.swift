import SwiftUI
import UIKit
import WMMFeatures
import WMMUI

// The frame every Settings task shares: a sheet with Cancel, the
// task's name, the steps as a bar with "Step 2 of 3", one filled button
// pinned at the bottom, and a last step that says what changed and what
// happens next, closed with Done.

/// The bar of steps under the sheet's title, and its line.
/// The steps are the Kit's (``FlowStep``): "Step 2 of 3 · Check it’s yours ·
/// next: Linked", or the last step's name once done.
struct TaskStepsBar: View {
    let steps: FlowStep

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                ForEach(1...steps.count, id: \.self) { n in
                    Capsule()
                        .fill(n <= steps.number || steps.done.contains(n) ? Palette.gold : Palette.outlineStrong)
                        .frame(height: 4)
                }
            }
            Text(steps.line)
                .font(.footnote)
                .foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(steps.accessibilityLabel)
        .accessibilityIdentifier("task-steps")
    }
}

/// Why a step was refused, above its button: what happened, and that nothing changed.
struct TaskProblem: Equatable {
    let title: String
    let message: String
}

/// A step's way back, at the left of the sheet's bar: "‹ Code".
struct TaskBack {
    let title: String
    let action: () -> Void
}

/// One step of a task in its sheet. `cancel` sits at the left on the first
/// step and at the right once there is a way `back`; the finished step has
/// neither, only its Done. The step's title takes VoiceOver's focus when the
/// step shows, and a refusal takes it when it shows. Each step is a frame of
/// its own inside a ``TaskStepSwitcher``, which slides them.
struct TaskFrame<Content: View, Actions: View>: View {
    let title: String
    var steps: FlowStep?
    var heading: String?
    var lede: String?
    var back: TaskBack?
    var cancel: (() -> Void)?
    var problem: TaskProblem?
    var busy = false
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    @AccessibilityFocusState private var headingFocused: Bool
    @AccessibilityFocusState private var problemFocused: Bool
    @Environment(\.taskGround) private var ground

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let steps { TaskStepsBar(steps: steps) }
                VStack(alignment: .leading, spacing: 16) {
                    if let heading {
                        Text(heading)
                            .font(.title2.weight(.bold))
                            .foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityFocused($headingFocused)
                            .accessibilityIdentifier("task-heading")
                    }
                    if let lede {
                        Text(lede)
                            .font(.subheadline)
                            .foregroundStyle(Palette.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    content
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 10) {
                if let problem {
                    SettingsBanner(kind: .problem, title: problem.title, message: problem.message)
                        .accessibilityFocused($problemFocused)
                        .accessibilityIdentifier("task-problem")
                }
                actions
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 12)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .background(ground)
        }
        .animation(.default, value: problem)
        .background(ground.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden()
        .toolbar {
            if let back {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: back.action) {
                        Label(back.title, systemImage: "chevron.left").labelStyle(.titleAndIcon)
                    }
                    .disabled(busy)
                    .accessibilityLabel("Back to \(back.title)")
                    .accessibilityIdentifier("task-back")
                }
            }
            if let cancel {
                ToolbarItem(placement: back == nil ? .cancellationAction : .topBarTrailing) {
                    Button("Cancel", action: cancel)
                        .keyboardShortcut(.cancelAction)
                        .disabled(busy)
                        .accessibilityIdentifier("task-cancel")
                }
            }
        }
        .task {
            // The step's title takes VoiceOver's focus once it is on screen.
            try? await Task.sleep(for: .milliseconds(400))
            headingFocused = true
        }
        .onChange(of: problem) { _, problem in
            guard problem != nil else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(250))
                problemFocused = true
            }
        }
        .sensoryFeedback(.error, trigger: problem) { _, new in new != nil }
        .sensoryFeedback(.success, trigger: steps?.isLast == true) { _, new in new }
    }

}

/// A task's steps, one at a time: going on slides the next in from the
/// trailing edge, Back slides the earlier one in from the leading edge, and
/// under Reduce Motion they fade.
struct TaskStepSwitcher<Content: View>: View {
    let index: Int
    @ViewBuilder let content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = 0

    var body: some View {
        ZStack {
            content
                .id(index)
                .transition(transition)
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.32), value: index)
        .onChange(of: index, initial: true) { _, index in shown = index }
    }

    /// Read as the new step goes in, while `shown` is still the step before.
    private var transition: AnyTransition {
        if reduceMotion { return .opacity }
        return .asymmetric(insertion: .move(edge: index >= shown ? .trailing : .leading), removal: .opacity)
    }
}

/// The finished step's body (the Kit's ``FinishedWords``): the tick, its
/// title and line, then what changed and what happens next, each in a box,
/// and anything the task adds after them (Link another TV).
struct TaskDone<More: View>: View {
    let words: FinishedWords
    @ViewBuilder var more: More

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 10) {
                TaskDoneMark()
                Text(words.title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if !words.lede.isEmpty {
                    Text(words.lede).font(.subheadline).foregroundStyle(Palette.muted)
                }
            }
            TaskBox(title: FinishedWords.whatChangedHeading) {
                ForEach(words.whatChanged, id: \.self) { Text($0) }
            }
            TaskBox(title: FinishedWords.whatHappensNextHeading) {
                ForEach(words.whatHappensNext, id: \.self) { Text($0) }
                more
            }
        }
    }
}

extension TaskDone where More == EmptyView {
    init(words: FinishedWords) { self.init(words: words, more: { EmptyView() }) }
}

/// A box with an uppercase heading and lines of text.
struct TaskBox<Lines: View>: View {
    let title: String
    @ViewBuilder let lines: Lines

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(title)
            VStack(alignment: .leading, spacing: 6) { lines }
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        .accessibilityElement(children: .combine)
    }
}

/// The gold tick over a finished step's title.
struct TaskDoneMark: View {
    var body: some View {
        Image(systemName: "checkmark")
            .font(.system(size: 26, weight: .bold))
            .foregroundStyle(Palette.ground)
            .frame(width: 56, height: 56)
            .background(Palette.gold, in: Circle())
            .accessibilityHidden(true)
    }
}

private struct TaskGroundKey: EnvironmentKey {
    static let defaultValue = Palette.sheet
}

extension EnvironmentValues {
    /// Behind a task's steps: the sheet's colour, or the page's in the iPad's detail column.
    var taskGround: Color {
        get { self[TaskGroundKey.self] }
        set { self[TaskGroundKey.self] = newValue }
    }
}

extension View {
    /// A task's sheet: full height on iPhone, a form sheet on iPad, in the sheet's colour.
    func taskSheet() -> some View {
        presentationSizing(.form)
            .presentationBackground(Palette.sheet)
            .presentationDragIndicator(.visible)
    }

    /// While `dirty`, a swipe down doesn't close the sheet but runs `ask`
    /// (``SheetDismissAsker``), which asks before throwing away what was typed.
    func asksBeforeDismissing(when dirty: Bool, ask: @escaping () -> Void) -> some View {
        interactiveDismissDisabled(dirty)
            .background(SheetDismissAsker(onAttempt: dirty ? ask : nil).frame(width: 0, height: 0))
    }
}

/// Hears a swipe down on a sheet that may not be swiped away: SwiftUI's
/// `interactiveDismissDisabled` refuses it without saying so, and UIKit tells
/// the presentation controller's delegate. This puts itself in front of
/// SwiftUI's delegate, passes everything on to it, and runs `onAttempt`.
private struct SheetDismissAsker: UIViewControllerRepresentable {
    let onAttempt: (() -> Void)?

    func makeUIViewController(context: Context) -> Controller { Controller() }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.proxy.onAttempt = onAttempt
        controller.install()
    }

    final class Controller: UIViewController {
        let proxy = Proxy()

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            install()
        }

        func install() {
            var top: UIViewController = self
            while let parent = top.parent { top = parent }
            guard let presentation = top.presentationController else { return }
            if presentation.delegate !== proxy {
                proxy.original = presentation.delegate
                presentation.delegate = proxy
            }
        }
    }

    final class Proxy: NSObject, UIAdaptivePresentationControllerDelegate {
        /// SwiftUI's own delegate; UIKit asks the proxy on the main thread.
        nonisolated(unsafe) weak var original: (any UIAdaptivePresentationControllerDelegate)?
        var onAttempt: (() -> Void)?

        override func responds(to selector: Selector!) -> Bool {
            super.responds(to: selector) || (original?.responds(to: selector) ?? false)
        }

        override func forwardingTarget(for selector: Selector!) -> Any? {
            original?.responds(to: selector) == true ? original : nil
        }

        func presentationControllerDidAttemptToDismiss(_ presentationController: UIPresentationController) {
            original?.presentationControllerDidAttemptToDismiss?(presentationController)
            onAttempt?()
        }
    }
}
