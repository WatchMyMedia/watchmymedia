import Foundation
import Testing
@testable import WMMCast
import WMMModel

@Suite struct CastProtocolTests {
    @Test func mirrorsExistingNamespaceAndCommands() throws {
        #expect(CastProtocol.namespace == "urn:x-cast:media.watchmy.cast")
        #expect(CastProtocol.contentID(fileID: 7) == "wmm:file:7")
        let object = try #require(try JSONSerialization.jsonObject(with: CastCommand.subtitle(nil).data()) as? [String: Any])
        #expect(object["type"] as? String == "subtitle")
        #expect(object["id"] is NSNull)
    }

    @Test func rejectsOtherProtocolsAndIgnoresFutureSegmentKinds() throws {
        #expect(CastProtocol.decode(Data(#"{"type":"state","v":2,"phase":"playing"}"#.utf8)) == nil)
        #expect(CastProtocol.decode(Data(#"{"type":"state","v":1,"phase":"new-state"}"#.utf8)) == nil)
        let state = try #require(CastProtocol.decode(Data(#"{"type":"state","v":1,"phase":"playing","segments":[{"kind":"sponsor","startMs":1},{"kind":"intro","startMs":2,"endMs":4}],"futureField":true}"#.utf8)))
        let player = state.playerState(status: nil, request: nil, apiLevel: 18)
        #expect(player.segments.map(\.kind) == [.intro])
    }

    @Test func receiverNumbersCannotOverflowPlayerArithmetic() throws {
        let state = try #require(CastProtocol.decode(Data("{\"type\":\"state\",\"v\":1,\"phase\":\"playing\",\"offsetMs\":\(Int.max),\"durationMs\":\(Int.max)}".utf8)))
        let media = CastMediaStatus(contentID: "wmm:file:1", status: .playing, positionMs: Int.max)
        let player = state.playerState(status: media, request: nil, apiLevel: 18)
        #expect(player.positionMs == 7 * 24 * 3_600_000)
        #expect(player.durationMs == player.positionMs)
        #expect(CastProtocol.decode(Data(repeating: 32, count: 131_073)) == nil)
    }
}
