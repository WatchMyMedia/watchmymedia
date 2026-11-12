// swift-tools-version: 5.9
// The macOS menu bar app. It is a thin shell: it runs the Go server from
// the app bundle's Helpers folder (next to the bundled ffmpeg) and shows
// its state in the menu bar. Build the bundle with macos/build-app.sh,
// which also copies Resources/ and the Sparkle framework into it.
import PackageDescription

let package = Package(
    name: "WatchMyMedia",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.0"),
    ],
    targets: [
        .executableTarget(
            name: "WatchMyMedia",
            dependencies: [.product(name: "Sparkle", package: "Sparkle")],
            path: "Sources/WatchMyMedia",
            linkerSettings: [
                .linkedFramework("ServiceManagement"),
                .linkedFramework("IOKit"),
                // Sparkle.framework lives in Contents/Frameworks of the bundle.
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"]),
            ]
        ),
        .testTarget(name: "WatchMyMediaTests", dependencies: ["WatchMyMedia"]),
    ]
)
