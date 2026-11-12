import UIKit
import WMMFeatures
import WMMModel

extension SettingsModel.DeviceFacts {
    /// This device and this build, as This iPhone and About show them.
    @MainActor
    static func current(client: ClientInfo) -> Self {
        let device = UIDevice.current
        return Self(
            model: client.model.isEmpty ? device.model : client.model,
            system: "\(device.systemName) \(device.systemVersion)",
            // Not `client.version`, which is "1.0 (1)" already.
            info: Bundle.main.infoDictionary ?? [:]
        )
    }
}

/// Launch arguments that reach a state for a screenshot on a simulator, where
/// nothing can tap (DEBUG builds only): `-settingsPage <page>`, `-signInPairing`,
/// `-chooseServer`, `-localNetworkDenied`, `-linkTVApprove`, `-editDeviceName`,
/// `-search <query>`.
enum ScreenshotLaunch {
    /// The value after `flag`, as `-settingsPage playback`.
    static func value(after flag: String) -> String? {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        guard let i = arguments.firstIndex(of: flag), arguments.indices.contains(i + 1) else { return nil }
        return arguments[i + 1]
        #else
        return nil
        #endif
    }

    /// Whether `flag` was passed.
    static func has(_ flag: String) -> Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains(flag)
        #else
        return false
        #endif
    }
}
