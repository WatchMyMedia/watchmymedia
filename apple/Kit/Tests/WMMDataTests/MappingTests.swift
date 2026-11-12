import Foundation
import Testing
import WMMAPI
@testable import WMMData
import WMMModel

@Suite struct MappingTests {
    @Test func libraryIndexesAndCountsRemainOptional() throws {
        let older = Data(#"{"items":[],"facets":{"genres":[],"decades":[]},"limit":100,"offset":0,"total":0}"#.utf8)
        let oldPage = try JSONDecoder().decode(Components.Schemas.ItemList.self, from: older).model
        #expect(oldPage.letters == nil)
        #expect(oldPage.genreCounts == nil)
        let indexed = Data(#"{"items":[],"facets":{"genres":[],"decades":[]},"limit":100,"offset":200,"total":500,"letters":[{"letter":"A","offset":0},{"letter":"Z","offset":420}],"genreCounts":{"Drama":120,"Comedy":0}}"#.utf8)
        let page = try JSONDecoder().decode(Components.Schemas.ItemList.self, from: indexed).model
        #expect(page.offset == 200)
        #expect(page.letters == [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "Z", offset: 420)])
        #expect(page.genreCounts == ["Drama": 120, "Comedy": 0])
    }

    @Test func emptyLibraryCountsArePresentRatherThanUnknown() throws {
        let data = Data(#"{"items":[],"facets":{"genres":[],"decades":[]},"limit":100,"offset":0,"total":0,"genreCounts":{}}"#.utf8)
        let page = try JSONDecoder().decode(Components.Schemas.ItemList.self, from: data).model
        #expect(page.genreCounts == [:])
    }

    @Test func audioLabelsNameTheLanguageCodecAndLayout() {
        let truehd = Components.Schemas.AudioStream(channels: 8, codec: "truehd", _default: true, index: 1, language: "eng")
        #expect(truehd.option == AudioOption(index: 1, label: "English · TrueHD 7.1 (Default)", language: "eng", isDefault: true))
        #expect(truehd.summary == "TrueHD 7.1 · English")
        let commentary = Components.Schemas.AudioStream(channels: 6, codec: "ac3", _default: false, index: 2, language: "ger", title: "Commentary")
        #expect(commentary.option.label == "German · AC3 5.1 · Commentary")
        let bare = Components.Schemas.AudioStream(channels: 2, codec: "aac", _default: false, index: 3)
        #expect(bare.option.label == "AAC Stereo")
        #expect(bare.option.language == nil)
        #expect(bare.summary == "AAC Stereo")
    }

    @Test func subtitleStreamsSayForcedAndBurnIn() {
        let forced = Components.Schemas.SubtitleStream(codec: "subrip", _default: false, forced: true, image: false, index: 3, language: "spa").option
        #expect(forced == SubtitleOption(id: "s3", label: "Spanish (Forced)", language: "spa", forced: true, format: "SRT"))
        let sdh = Components.Schemas.SubtitleStream(codec: "subrip", _default: false, forced: false, image: false, index: 5, language: "en", title: "SDH").option
        #expect(sdh.label == "English · SDH")
        let signs = Components.Schemas.SubtitleStream(codec: "hdmv_pgs_subtitle", _default: false, forced: false, image: true, index: 4, title: "Signs").option
        #expect(signs.label == "Signs")
        #expect(signs.burnInOnly)
        #expect(signs.format == "PGS")
        #expect(signs.detail == "PGS · burned in, restarts playback")
    }

    @Test func unmatchedItemsCarryTheirFileNameAndCount() throws {
        let item = try #require(Components.Schemas.BrowseItem(
            addedAt: .now, fileCount: 1, fileName: "Glass Tide (2023)/Glass Tide (2023).mkv", genres: [], hdr: false, id: 1, isNew: true,
            kind: .unmatched, libraryId: 1, libraryType: .movie, overview: "", quality: ._empty_, subtitle: "Not looked up yet",
            title: "Glass Tide (2023).mkv", totalSize: 12_798_425, watched: false
        ).model)
        #expect(item.kind == .unmatched)
        #expect(item.id == 1)
        #expect(item.fileName == "Glass Tide (2023)/Glass Tide (2023).mkv")
        #expect(item.fileCount == 1)
        #expect(item.subtitle == "Not looked up yet")
        #expect(item.quality == nil)
        #expect(item.watchTarget == nil)
        #expect(item.unmatchedPlayRequest?.fileID == 1)

        let folder = try #require(Components.Schemas.BrowseItem(
            addedAt: .now, fileCount: 8, fileName: "Pale Water", genres: [], hdr: false, id: 40, isNew: false,
            kind: .unmatched, libraryId: 2, overview: "", title: "Pale Water", totalSize: 1, watched: false
        ).model)
        #expect(folder.fileCount == 8)
        #expect(folder.unmatchedPlayRequest == nil, "a folder needs matching first")
    }

    @Test func aRecentlyAddedCardForOneEpisodeNamesIt() throws {
        let one = try #require(Components.Schemas.BrowseItem(
            addedAt: .now, episodeId: 9031, genres: [], hdr: false, id: 9, isNew: true, kind: .show, libraryId: 2, overview: "",
            seasonNumber: 0, subtitle: "S00E01", title: "Harbor Lights", totalSize: 1, watched: false
        ).model)
        #expect(one.episodeID == 9031)
        #expect(one.seasonNumber == 0, "specials are season 0")
        let older = try #require(Components.Schemas.BrowseItem(
            addedAt: .now, genres: [], hdr: false, id: 9, isNew: true, kind: .show, libraryId: 2, overview: "",
            subtitle: "S01E02", title: "Harbor Lights", totalSize: 1, watched: false
        ).model)
        #expect(older.episodeID == nil, "an older server names no episode")
        #expect(older.seasonNumber == nil)
    }

    @Test func browseItemsDropBlanksAndZeros() throws {
        let item = try #require(Components.Schemas.BrowseItem(
            addedAt: .now, backdropPath: "", genres: ["Drama"], hdr: true, id: 7, isNew: false, kind: .movie, libraryId: 1, overview: "o",
            posterPath: " ", progress: 0, quality: ._4k, rating: 0, runtimeMin: 0, subtitle: "", title: "T", totalSize: 1, watched: true, year: 0
        ).model)
        #expect(item.year == nil)
        #expect(item.rating == nil)
        #expect(item.runtimeMin == nil)
        #expect(item.progress == nil)
        #expect(item.posterPath == nil)
        #expect(item.backdropPath == nil)
        #expect(item.subtitle == nil)
        #expect(item.quality == "4K")
        #expect(item.hdr)
        #expect(item.watched)
        #expect(item.fileName == nil)
        #expect(item.fileCount == nil)
    }

    @Test func containersHDRAndFileNames() {
        #expect(Mapping.containerName("matroska,webm") == "MKV")
        #expect(Mapping.containerName("mov,mp4,m4a,3gp,3g2,mj2") == "MP4")
        #expect(Mapping.containerName("mpegts") == "TS")
        #expect(Mapping.containerName("avi") == "AVI")
        #expect(Mapping.hdrName(Components.Schemas.MediaFile.HdrPayload.dolbyvision) == "Dolby Vision")
        #expect(Mapping.hdrName(Components.Schemas.MediaFile.HdrPayload.hdr10) == "HDR10")
        #expect(Mapping.hdrName(Components.Schemas.MediaFile.HdrPayload.hlg) == "HLG")
        #expect(Mapping.hdrName(Components.Schemas.MediaFile.HdrPayload._empty_) == nil)
        #expect(Mapping.hdrName(Components.Schemas.MediaFile.HdrPayload?.none) == nil)
        #expect(Mapping.fileName("Show (2019)/Season 01/Show - S01E01.mkv") == "Show - S01E01.mkv")
        #expect(Mapping.fileName("plain.mkv") == "plain.mkv")
    }

    @Test func preferencesKeepTheDefaultsForWhatTheServerDoesNotSay() {
        let prefs = Components.Schemas.Preferences(autoplayNext: false, skip: .init(additionalProperties: ["intro": "auto", "sponsor": "auto", "recap": "often"])).model
        #expect(prefs.skipMode(.intro) == .auto)
        #expect(prefs.skipMode(.recap) == .ask)
        #expect(prefs.skipMode(.credits) == .ask)
        #expect(prefs.skipMode(.preview) == .none)
        #expect(!prefs.autoplayNext)
        #expect(prefs.skip.count == SegmentKind.allCases.count)
    }

    @Test func pendingDevicesAndUsers() {
        let expiry = Date(timeIntervalSince1970: 1_793_294_420)
        let device = Components.Schemas.PairingInfo(approved: false, code: "BPEULY", expiresAt: expiry, ip: "", model: "Apple TV", name: "Den", platform: "tvos", product: nil, version: "1.0").pendingDevice
        #expect(device == PendingDevice(code: "BPEULY", name: "Den", platform: "tvos", version: "1.0", approved: false, model: "Apple TV", expiresAt: expiry))
        #expect(Components.Schemas.User(id: 3, isAdmin: true, name: "sam").model == User(id: 3, name: "sam", isAdmin: true))
    }

    @Test func theClientIsCutToTheServersLimits() {
        let long = String(repeating: "x", count: 100)
        let client = ClientInfo(deviceID: long, name: long, product: long, platform: long, version: long, model: "")
        let api = client.api
        #expect(api.deviceId.count == 64)
        #expect(api.name?.count == 64)
        #expect(api.product?.count == 64)
        #expect(api.platform?.count == 32)
        #expect(api.version?.count == 32)
        #expect(api.model == nil, "no model: none sent")
    }

    @Test func theVersionIsTheMarketingVersionAndTheBuild() {
        #expect(ClientIdentity.version(info: ["CFBundleShortVersionString": "1.0", "CFBundleVersion": "12"]) == "1.0 (12)")
        #expect(ClientIdentity.version(info: ["CFBundleShortVersionString": "1.0.0", "CFBundleVersion": "1.0.0"]) == "1.0.0")
        #expect(ClientIdentity.version(info: ["CFBundleShortVersionString": "1.0"]) == "1.0")
        #expect(ClientIdentity.version(info: [:]) == "0.0.0-dev")
        #expect(ClientIdentity.cleanName("  Den iPad \n") == "Den iPad")
        #expect(ClientIdentity.cleanName("   ") == nil)
        #expect(ClientIdentity.cleanName(String(repeating: "n", count: 80))?.count == 64)
    }

    @MainActor
    @Test func theLiveClientDescribesThisApp() {
        let client = ClientIdentity.client(deviceID: "dev-1", name: "Phone")
        #expect(client.deviceID == "dev-1")
        #expect(client.name == "Phone")
        #expect(["WatchMyMedia for iOS", "WatchMyMedia for Apple TV"].contains(client.product))
        #expect(["ios", "tvos"].contains(client.platform))
        #expect(!client.model.isEmpty)
        #expect(!ClientIdentity.defaultName.isEmpty)
        #expect(ClientIdentity.userAgent().hasPrefix("WatchMyMedia/"))
    }
}
