package media.watchmy.client.presentation

import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.ShowDetail

/** The fake library, with marks recorded (and made) and a switch that makes loading and marking fail. */
class TestLibrary(private val fake: LibraryRepository = FakeLibraryRepository()) : LibraryRepository by fake {
    /** Thrown by every load and mark while set. */
    var failure: Exception? = null
    val watchCalls = mutableListOf<String>()

    private fun maybeFail() {
        failure?.let { throw it }
    }

    override suspend fun show(id: Long): ShowDetail = maybeFail().let { fake.show(id) }

    override suspend fun season(showId: Long, number: Int): SeasonDetail = maybeFail().let { fake.season(showId, number) }

    override suspend fun movie(id: Long): MovieDetail = maybeFail().let { fake.movie(id) }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        maybeFail()
        watchCalls += "$kind $id $watched"
        fake.setWatched(kind, id, watched)
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) {
        maybeFail()
        watchCalls += "season $showId $season $watched"
        fake.setSeasonWatched(showId, season, watched)
    }

    // Delegation would send this to the fake's own default, past the recording above.
    override suspend fun setShowWatched(showId: Long, watched: Boolean) {
        maybeFail()
        watchCalls += "show $showId $watched"
        fake.setShowWatched(showId, watched)
    }
}
