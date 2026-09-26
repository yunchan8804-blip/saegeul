// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "HangulCore",
    platforms: [
        .iOS(.v18),
        .macOS(.v14),
    ],
    products: [
        .library(name: "HangulCore", targets: ["HangulCore"]),
    ],
    targets: [
        .target(name: "HangulCore"),
        .testTarget(
            name: "HangulCoreTests",
            dependencies: ["HangulCore"]
        ),
    ]
)
