import Testing
@testable import WMMUI

@Suite struct QRCodeTests {
    @Test func encodesALinkWithItsFinderPatterns() throws {
        let matrix = try #require(QRMatrix("http://192.168.1.20:27587/link?code=K7M4PQ"))
        #expect(matrix.size >= 21)
        #expect((matrix.size - 17) % 4 == 0, "a QR code is 17 + 4 × version modules a side")
        #expect(matrix.modules.count == matrix.size * matrix.size)
        // The three finder patterns: a dark ring, a light ring, a dark centre.
        for (ox, oy) in [(0, 0), (matrix.size - 7, 0), (0, matrix.size - 7)] {
            for i in 0..<7 {
                #expect(matrix[ox + i, oy] && matrix[ox + i, oy + 6] && matrix[ox, oy + i] && matrix[ox + 6, oy + i])
            }
            for i in 1..<6 { #expect(!matrix[ox + i, oy + 1] && !matrix[ox + 1, oy + i]) }
            #expect(matrix[ox + 3, oy + 3])
        }
        // The fourth corner has none.
        #expect(!(0..<7).allSatisfy { matrix[matrix.size - 1 - $0, matrix.size - 1] })
    }

    @Test func longerTextNeedsALargerCode() throws {
        let short = try #require(QRMatrix("https://watchmy.media/privacy"))
        let long = try #require(QRMatrix("https://watchmy.media/privacy?" + String(repeating: "a", count: 200)))
        #expect(long.size > short.size)
    }
}
