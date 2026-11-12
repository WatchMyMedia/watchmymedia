import Foundation
import Testing
@testable import WMMModel

@Suite struct TrailersTests {
    @Test func theServersFieldsMakeATrailerOfEachKnownSource() throws {
        let file = try #require(MovieTrailer(source: "file", name: "Trailer", fileID: 77, key: nil, durationMs: 151_000))
        #expect(file.fileID == 77)
        #expect(file.youTube == nil)
        #expect(file.durationMs == 151_000)

        let youTube = try #require(MovieTrailer(source: "youtube", name: "Official Trailer", fileID: nil, key: "YE7VzlLtp-4", durationMs: nil))
        #expect(youTube.fileID == nil)
        #expect(youTube.youTube?.key == "YE7VzlLtp-4")
        #expect(youTube.youTube?.name == "Official Trailer")
    }

    @Test func trailersThisAppCantPlayAreSkipped() {
        #expect(MovieTrailer(source: "vimeo", name: "Teaser", fileID: nil, key: "12345678", durationMs: nil) == nil, "a source added later")
        #expect(MovieTrailer(source: "file", name: "Trailer", fileID: nil, key: nil, durationMs: nil) == nil)
        #expect(MovieTrailer(source: "file", name: "Trailer", fileID: 0, key: nil, durationMs: nil) == nil)
        #expect(MovieTrailer(source: "youtube", name: "Trailer", fileID: nil, key: nil, durationMs: nil) == nil)
        for key in ["", "abc", "YE7VzlLtp-4&autoplay=0", "YE7Vz/Ltp-4", "YE7VzlLtp'4", "<script>xyz", "ÿE7VzlLtp-4", String(repeating: "a", count: 33)] {
            #expect(MovieTrailer(source: "youtube", name: "Trailer", fileID: nil, key: key, durationMs: nil) == nil, "\(key)")
        }
    }

    @Test func aTrailerWithNoNameIsATrailer() {
        #expect(MovieTrailer(source: "youtube", name: " ", fileID: nil, key: "YE7VzlLtp-4", durationMs: nil)?.name == "Trailer")
        #expect(MovieTrailer(source: "file", name: "", fileID: 3, key: nil, durationMs: 0)?.name == "Trailer")
        #expect(MovieTrailer(source: "file", name: "", fileID: 3, key: nil, durationMs: 0)?.durationMs == nil)
    }

    @Test func youTubeLinks() throws {
        let trailer = try #require(YouTubeTrailer(name: "Official Trailer", key: "YE7VzlLtp-4"))
        #expect(trailer.watchURL.absoluteString == "https://www.youtube.com/watch?v=YE7VzlLtp-4")
        #expect(trailer.appURL.absoluteString == "youtube://www.youtube.com/watch?v=YE7VzlLtp-4")
    }

    @Test func aTrailerFilePlaysFromItsStartUnderTheMoviesName() throws {
        var movie = MovieDetail(
            id: 21, libraryID: 1, title: "Paper Moons", year: 2021, tagline: nil, overview: "", releaseDate: nil, runtimeMin: nil, genres: [],
            rating: nil, posterPath: "/p.jpg", backdropPath: "/b.jpg", quality: nil, hdr: false, versions: [], cast: [], watch: nil
        )
        #expect(movie.trailer == nil)
        let youTube = try #require(MovieTrailer(source: "youtube", name: "Teaser", fileID: nil, key: "abcdefghijk", durationMs: nil))
        let file = try #require(MovieTrailer(source: "file", name: "Trailer", fileID: 77, key: nil, durationMs: nil))
        movie.trailers = [file, youTube]
        #expect(movie.trailer == file)
        #expect(movie.youTubeTrailers.map(\.key) == ["abcdefghijk"])

        let request = movie.trailerRequest(fileID: 77, name: "Trailer")
        #expect(request.fileID == 77)
        #expect(request.startMs == 0)
        #expect(request.title == "Paper Moons")
        #expect(request.subtitle == "Trailer")
        #expect(request.backdropPath == "/b.jpg")
        #expect(request.posterPath == "/p.jpg")
        #expect(request.showID == nil)
    }
}
