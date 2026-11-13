// swift-tools-version: 6.0
// WatchMyMediaKit: everything the iPhone and Apple TV apps share. The targets mirror the Android modules (android/README.md):
//
//   WMMModel     domain types and the protocols every screen builds against (core:model)
//   WMMAPI       the client generated from api/openapi.json, committed (core:api)
//   WMMData      the real repositories over WMMAPI (core:data)
//   WMMPlayback  the PlaybackController on AVPlayer (core:playback)
//   WMMDownloads offline downloads on iPhone and iPad: the system's background
//                sessions, the offline store, sync and offline plays (core:downloads)
//   WMMFakes     in-memory fakes with the mockups' library (core:fake)
//   WMMUI        brand tokens and shared SwiftUI views (core:ui)
//   WMMFeatures  screen models and navigation, shared by the iPhone and Apple TV
//                screens (the ViewModels android/ keeps in tv/ and mobile/)
//
// Everything but WMMUI's UIKit bits builds for macOS too, so `swift test`
// runs the unit tests on the Mac without a simulator.
import PackageDescription

let package = Package(
    name: "WatchMyMediaKit",
    platforms: [.iOS(.v18), .tvOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "WMMModel", targets: ["WMMModel"]),
        .library(name: "WMMAPI", targets: ["WMMAPI"]),
        .library(name: "WMMData", targets: ["WMMData"]),
        .library(name: "WMMPlayback", targets: ["WMMPlayback"]),
        .library(name: "WMMDownloads", targets: ["WMMDownloads"]),
        .library(name: "WMMFakes", targets: ["WMMFakes"]),
        .library(name: "WMMUI", targets: ["WMMUI"]),
        .library(name: "WMMFeatures", targets: ["WMMFeatures"]),
        .library(name: "WMMCast", targets: ["WMMCast"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-runtime", from: "1.7.0"),
        .package(url: "https://github.com/apple/swift-openapi-urlsession", from: "1.0.2"),
        .package(url: "https://github.com/apple/swift-http-types", from: "1.0.0"),
    ],
    targets: [
        .target(name: "WMMModel"),
        .target(
            name: "WMMAPI",
            dependencies: [.product(name: "OpenAPIRuntime", package: "swift-openapi-runtime")],
            exclude: ["openapi-generator-config.yaml"]
        ),
        .target(
            name: "WMMData",
            dependencies: [
                "WMMModel", "WMMAPI",
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
                .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
                .product(name: "HTTPTypes", package: "swift-http-types"),
            ]
        ),
        .target(
            name: "WMMPlayback", dependencies: ["WMMModel"],
            // AVPlayerItem.externalMetadata is an AVKit category on tvOS. The
            // playback package also loads it without an app UI (native tests).
            linkerSettings: [.linkedFramework("AVKit", .when(platforms: [.tvOS]))]
        ),
        .target(name: "WMMDownloads", dependencies: ["WMMModel"]),
        .target(name: "WMMFakes", dependencies: ["WMMModel"]),
        .target(name: "WMMUI", dependencies: ["WMMModel"]),
        .target(name: "WMMFeatures", dependencies: ["WMMModel"]),
        .target(name: "WMMCast", dependencies: ["WMMModel"]),
        .testTarget(name: "WMMModelTests", dependencies: ["WMMModel"]),
        .testTarget(
            name: "WMMDataTests",
            // WMMFeatures: the screen models, fed by the real ServerEvents (ScreenEventsTests).
            dependencies: ["WMMData", "WMMModel", "WMMAPI", "WMMFeatures", .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime")]
        ),
        .testTarget(name: "WMMPlaybackTests", dependencies: ["WMMPlayback", "WMMModel", "WMMFakes"]),
        .testTarget(name: "WMMDownloadsTests", dependencies: ["WMMDownloads", "WMMModel"]),
        .testTarget(name: "WMMFakesTests", dependencies: ["WMMFakes", "WMMModel"]),
        .testTarget(name: "WMMUITests", dependencies: ["WMMUI", "WMMModel"]),
        .testTarget(name: "WMMFeaturesTests", dependencies: ["WMMFeatures", "WMMFakes", "WMMModel"]),
        .testTarget(name: "WMMCastTests", dependencies: ["WMMCast", "WMMModel", "WMMFakes"]),
    ]
)
