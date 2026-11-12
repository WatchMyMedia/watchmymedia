import SwiftUI
import WMMFakes
import WMMFeatures
import WMMModel
import WMMUI

/// Link a TV (LinkTv.dc.html): type the code the TV shows (or arrive with it
/// from the TV's QR code), check which TV it is, approve it.
struct LinkTVScreen: View {
    var code: String?
    @Environment(AppServices.self) private var services

    var body: some View {
        LinkTVContent(model: LinkTVModel(auth: services.auth, code: code))
    }
}

private struct LinkTVContent: View {
    @State private var model: LinkTVModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var codeFocused: Bool

    init(model: LinkTVModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Enter the code on the TV’s sign-in screen. Scanning the TV’s QR code opens this screen with the code filled in.")
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .fixedSize(horizontal: false, vertical: true)

                    switch model.step {
                    case .enter: enter
                    case .found(let device): found(device)
                    case .approved(let device): approved(device)
                    }

                    Spacer(minLength: 16)

                    Text("Codes last 10 minutes. A wrong code counts like a failed sign-in.")
                        .font(.footnote)
                        .foregroundStyle(Palette.faint)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                        .padding(.bottom, 8)
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .groundBackground()
        .navigationTitle("Link a TV")
        .navigationBarTitleDisplayMode(.large)
        .toolbar(.hidden, for: .tabBar)
        .task {
            await model.start()
            if model.step == .enter { codeFocused = true }
            // `-linkTVApprove` (DEBUG) approves at once, to screenshot the done step.
            if ScreenshotLaunch.has("-linkTVApprove") { await model.approve() }
        }
    }

    // MARK: Steps

    private var enter: some View {
        VStack(spacing: 16) {
            CodeField(code: model.code, onChange: model.setCode, onSubmit: lookUp, focused: $codeFocused)
                .disabled(model.busy)
            if let error = model.error { ErrorLine(message: error) }
            Button(action: lookUp) { BusyLabel(title: "Continue", busy: model.busy) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.busy || !model.complete)
        }
    }

    private func found(_ device: PendingDevice) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            CodeBoxes(code: device.code, size: .large, dim: true, boxBackground: Palette.surfaceRaised)
            VStack(alignment: .leading, spacing: 6) {
                GroupHeading("This TV").padding(.horizontal, 16)
                RoundedGroup {
                    detailRow("Name", device.name)
                    if let app = device.appLabel { detailRow("App", app) }
                    detailRow("Address", device.ip ?? "Unknown")
                    if let asked = device.askedAt { detailRow("Asked", formatAgo(asked)) }
                }
            }
            Text("Approve signs this TV in as \(Text(model.userName).bold()). Anyone with its remote can watch as you until you sign it out under Signed-in devices.")
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let error = model.error { ErrorLine(message: error) }
            Button { Task { await model.approve() } } label: { BusyLabel(title: "Approve", busy: model.busy) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.busy)
            Button("Not this TV") { dismiss() }
                .buttonStyle(GhostButtonStyle(height: 50))
                .disabled(model.busy)
        }
    }

    private func approved(_ device: PendingDevice) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(Palette.ground)
                .frame(width: 72, height: 72)
                .background(Palette.gold, in: Circle())
                .accessibilityHidden(true)
            Text("\(device.name) is signed in")
                .font(.title2.weight(.bold))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
            Text("It shows Home in a moment, signed in as \(model.userName).")
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
            Button("Done") { dismiss() }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: 10) {
            Text(label).foregroundStyle(Palette.muted)
            Spacer(minLength: 12)
            Text(value)
                .fontWeight(.medium)
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    private func lookUp() {
        codeFocused = false
        Task { await model.lookUp() }
    }
}

#Preview {
    NavigationStack {
        LinkTVScreen(code: "K7M4PQ")
    }
    .environment(AppServices.fake())
    .preferredColorScheme(.dark)
}
