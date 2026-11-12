import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

@Suite struct WebVTTTests {
    @Test func outOfRangeTimestampsAreDiscardedWithoutOverflow() {
        for value in ["\(Int.max):00:00.000", "\(Int.max):00.000", "2562047788016:00:00.000", "00:01.-1"] {
            #expect(WebVTT.timestamp(value) == nil)
            #expect(WebVTT.parse("WEBVTT\n\n00:00.000 --> \(value)\nInvalid cue\n").isEmpty)
        }
        #expect(WebVTT.timestamp("100:00:00.999") == 360_000_999)
        #expect(WebVTT.timestamp("999:59.999") == 59_999_999)
    }

    @Test func timestampsWithAndWithoutHours() {
        let cues = WebVTT.parse("""
        WEBVTT

        00:00:01.000 --> 00:00:04.500
        With hours

        01:05.250 --> 01:07.000
        Without hours

        01:02:03.004 --> 01:02:04.000
        Past the hour
        """)
        #expect(cues == [
            SubtitleCue(startMs: 1_000, endMs: 4_500, text: "With hours"),
            SubtitleCue(startMs: 65_250, endMs: 67_000, text: "Without hours"),
            SubtitleCue(startMs: 3_723_004, endMs: 3_724_000, text: "Past the hour"),
        ])
    }

    @Test func cueSettingsAndIdentifiersAreSetAside() {
        let cues = WebVTT.parse("""
        WEBVTT - Harbor Lights S03E07

        intro-1
        00:00:01.000 --> 00:00:02.000 align:start position:10% line:0
        Settings after the timestamp

        2
        00:00:03.000 --> 00:00:04.000
        Numbered
        """)
        #expect(cues.map(\.text) == ["Settings after the timestamp", "Numbered"])
        #expect(cues.first?.endMs == 2_000)
    }

    @Test func multiLineCuesKeepTheirLines() {
        let cues = WebVTT.parse("WEBVTT\n\n00:01.000 --> 00:03.000\nThe channel markers\nwere moved.\n")
        #expect(cues.first?.text == "The channel markers\nwere moved.")
    }

    @Test func tagsAreStrippedAndEntitiesDecoded() {
        let cues = WebVTT.parse("""
        WEBVTT

        00:01.000 --> 00:02.000
        <i>Italic</i> and <b>bold</b>, <c.yellow>a class</c>

        00:03.000 --> 00:04.000
        <v Sam>Fish &amp; chips &lt;3&gt; &quot;now&quot; &#39;ok&#39; &#x2014; done&nbsp;!</v>

        00:05.000 --> 00:06.000
        Karaoke <00:05.500>timing and 3 < 4 & 5 > 2
        """)
        #expect(cues.map(\.text) == [
            "Italic and bold, a class",
            "Fish & chips <3> \"now\" 'ok' \u{2014} done\u{00A0}!",
            "Karaoke timing and 3 < 4 & 5 > 2",
        ])
    }

    @Test func aByteOrderMarkAndWindowsLineEndsAreFine() {
        let cues = WebVTT.parse("\u{FEFF}WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nHello\r\nthere\r\n")
        #expect(cues == [SubtitleCue(startMs: 1_000, endMs: 2_000, text: "Hello\nthere")])
    }

    @Test func blankNoteStyleAndRegionBlocksAreSkipped() {
        let cues = WebVTT.parse("""
        WEBVTT
        Kind: captions


        NOTE This file was made by hand
        and the note goes on --> here

        STYLE
        ::cue { color: yellow }

        REGION
        id:fred width:40%



        00:01.000 --> 00:02.000
        One

        NOTE
        00:03.000 --> 00:04.000
        Hidden in a note

        00:05.000 --> 00:06.000
        <i></i>

        00:07.000 --> 00:08.000
        Two
        """)
        #expect(cues.map(\.text) == ["One", "Two"])
    }

    @Test func brokenCuesAreLeftOutAndTheRestSorted() {
        let cues = WebVTT.parse("""
        WEBVTT

        00:09.000 --> 00:10.000
        Late

        nonsense --> 00:02.000
        Bad start

        00:05.000 --> 00:04.000
        Ends before it starts

        00:03,500 --> 00:04,000
        SRT commas

        00:01.000 --> 00:02.000
        Early
        """)
        #expect(cues.map(\.text) == ["Early", "SRT commas", "Late"])
        #expect(cues[1].startMs == 3_500)
    }

    @Test func timestamps() {
        #expect(WebVTT.timestamp("00:00:00.000") == 0)
        #expect(WebVTT.timestamp(" 10:00.5 ") == 600_500)
        #expect(WebVTT.timestamp("100:00:00.000") == 360_000_000)
        #expect(WebVTT.timestamp("00:60.000") == nil)
        #expect(WebVTT.timestamp("1:2:3:4") == nil)
        #expect(WebVTT.timestamp("12.000") == nil)
    }

    @Test func theCuesOnScreenAtATime() {
        let track = CueTrack(cues: WebVTT.parse("""
        WEBVTT

        00:01.000 --> 00:10.000
        Long

        00:02.000 --> 00:03.000
        Overlapping

        00:05.000 --> 00:06.000
        Later
        """))
        #expect(track.active(at: 500).isEmpty)
        #expect(track.active(at: 2_500).map(\.text) == ["Long", "Overlapping"])
        #expect(track.active(at: 3_000).map(\.text) == ["Long"])
        #expect(track.active(at: 5_000).map(\.text) == ["Long", "Later"])
        #expect(track.active(at: 10_000).isEmpty)
    }
}
