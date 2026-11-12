// swift-tools-version: 6.0
// Tools for the Apple apps, kept out of the app's own dependencies: the
// OpenAPI generator that writes Kit/Sources/WMMAPI from ../api/openapi.json.
// Run through `make apple-api`, not by hand.
import PackageDescription

let package = Package(
    name: "Tools",
    platforms: [.macOS(.v15)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-generator", from: "1.6.0"),
    ],
    targets: []
)
