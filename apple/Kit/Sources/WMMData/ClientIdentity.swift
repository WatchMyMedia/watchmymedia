import Foundation
import WMMAPI
import WMMModel
#if canImport(UIKit)
import UIKit
#endif

/// How this app describes itself to the server, as `DataServices.clientInfo`
/// does on Android: the product and platform, the app's version, the device's
/// model, and the name people see (editable in Settings).
enum ClientIdentity {
    /// Longest name the server keeps.
    static let maxName = 64

    #if os(tvOS)
    static let product = "WatchMyMedia for Apple TV"
    static let platform = "tvos"
    #else
    static let product = "WatchMyMedia for iOS"
    static let platform = "ios"
    #endif

    @MainActor
    static func client(deviceID: String, name: String, bundle: Bundle = .main) -> ClientInfo {
        ClientInfo(deviceID: deviceID, name: name, product: product, platform: platform, version: version(bundle), model: model)
    }

    /// "1.0 (12)": the marketing version and the build, or "1.0" when the build adds nothing.
    static func version(_ bundle: Bundle = .main) -> String {
        version(info: bundle.infoDictionary ?? [:])
    }

    static func version(info: [String: Any]) -> String {
        let short = (info["CFBundleShortVersionString"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let build = (info["CFBundleVersion"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        switch (short, build) {
        case let (s?, b?) where s != b: return "\(s) (\(b))"
        case let (s?, _): return s
        case let (nil, b?): return b
        case (nil, nil): return "0.0.0-dev"
        }
    }

    /// "iPhone", "iPad", "Apple TV"; the generic model, since the device's own
    /// name needs an entitlement.
    @MainActor
    static var model: String {
        #if canImport(UIKit)
        UIDevice.current.model
        #else
        "Mac"
        #endif
    }

    /// The first name this device goes by: its model.
    @MainActor
    static var defaultName: String {
        let m = model.trimmingCharacters(in: .whitespaces)
        return String((m.isEmpty ? platform == "tvos" ? "Apple TV" : "iPhone" : m).prefix(maxName))
    }

    /// "WatchMyMedia/1.0 (ios; iOS 18.2)".
    static func userAgent(_ bundle: Bundle = .main) -> String {
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let short = (bundle.infoDictionary?["CFBundleShortVersionString"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "0.0.0-dev"
        return "WatchMyMedia/\(short) (\(platform); \(systemName) \(os.majorVersion).\(os.minorVersion))"
    }

    private static var systemName: String {
        #if os(tvOS)
        "tvOS"
        #elseif os(iOS)
        "iOS"
        #else
        "macOS"
        #endif
    }

    /// A name as the server keeps it: trimmed, at most ``maxName`` characters; nil when blank.
    static func cleanName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(maxName))
    }
}

extension ClientInfo {
    /// The `client` object of sign-in, pairing, playback and downloads, cut to the server's limits.
    var api: Components.Schemas.Client {
        Components.Schemas.Client(
            deviceId: String(deviceID.prefix(64)),
            model: Mapping.nonBlank(model).map { String($0.prefix(64)) },
            name: String(name.prefix(ClientIdentity.maxName)),
            platform: String(platform.prefix(32)),
            product: String(product.prefix(64)),
            version: String(version.prefix(32))
        )
    }
}
