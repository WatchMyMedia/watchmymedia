import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// A QR code's modules, row by row, without the quiet zone: what Apple TV
/// shows for the server's link page and the privacy policy, drawn crisply at
/// any size by ``QRCodeView``.
public struct QRMatrix: Hashable, Sendable {
    /// Modules per side (21 for the smallest code, 4 more for each version).
    public let size: Int
    /// `size × size` modules, row by row; true is dark.
    public let modules: [Bool]

    /// Nil when `text` cannot be encoded (it is too long for a QR code).
    public init?(_ text: String) {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let image = filter.outputImage else { return nil }
        let extent = image.extent.integral
        let width = Int(extent.width), height = Int(extent.height)
        guard width > 0, width == height else { return nil }
        var pixels = [UInt8](repeating: 255, count: width * height)
        let context = CIContext(options: [.useSoftwareRenderer: true, .workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
        context.render(image, toBitmap: &pixels, rowBytes: width, bounds: extent, format: .L8, colorSpace: nil)
        let dark = pixels.map { $0 < 128 }
        // The generator leaves a light margin; the view draws its own.
        let rows = (0..<height).filter { y in (0..<width).contains { dark[y * width + $0] } }
        let columns = (0..<width).filter { x in (0..<height).contains { dark[$0 * width + x] } }
        guard let top = rows.first, let bottom = rows.last, let left = columns.first, let right = columns.last,
              bottom - top == right - left else { return nil }
        let size = bottom - top + 1
        var modules: [Bool] = []
        modules.reserveCapacity(size * size)
        for y in top...bottom {
            for x in left...right { modules.append(dark[y * width + x]) }
        }
        self.size = size
        self.modules = modules
    }

    public subscript(x: Int, y: Int) -> Bool { modules[y * size + x] }
}

/// A QR code on a white card with its quiet zone, as the Apple TV sign-in and
/// privacy screens show it (TvMain.dc.html). Scanning it opens `text`.
public struct QRCodeView: View {
    let matrix: QRMatrix?

    public init(_ text: String) { matrix = QRMatrix(text) }

    public var body: some View {
        Canvas { context, canvas in
            context.fill(Path(CGRect(origin: .zero, size: canvas)), with: .color(.white))
            guard let matrix else { return }
            // Four modules of quiet zone around the code.
            let module = min(canvas.width, canvas.height) / CGFloat(matrix.size + 8)
            let origin = CGPoint(x: (canvas.width - module * CGFloat(matrix.size)) / 2, y: (canvas.height - module * CGFloat(matrix.size)) / 2)
            var dark = Path()
            for y in 0..<matrix.size {
                for x in 0..<matrix.size where matrix[x, y] {
                    // A hair larger than a module, so no seams show between neighbours.
                    dark.addRect(CGRect(x: origin.x + CGFloat(x) * module, y: origin.y + CGFloat(y) * module, width: module + 0.5, height: module + 0.5))
                }
            }
            context.fill(dark, with: .color(Palette.ground))
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement()
        .accessibilityLabel("QR code")
    }
}
