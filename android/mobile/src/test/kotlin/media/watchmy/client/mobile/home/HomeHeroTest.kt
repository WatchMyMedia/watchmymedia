package media.watchmy.client.mobile.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.Hero
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's one hero (PLAN-ANDROID-REVIEW §5.4), as the phone, the tablet and
 * the paged tablet Home draw it: Play plays it, and the second button opens
 * the episode, the movie or the show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HomeHeroTest {
    @get:Rule val compose = createComposeRule()
    private val library = FakeAppServices().library
    private val opened = mutableListOf<String>()
    private val played = mutableListOf<PlayRequest>()

    private val episodeHero = Hero(Hero.Kind.Episode, "New episode · Harbor Lights", "Harbor Lights", "S03E07 · 47 min", "", null, null, showId = 100, movieId = null, episodeId = 7, fileId = 9101)
    private val card = EpisodeCard(7, 100, "Harbor Lights", 3, 7, "The Long Dark", "", null, null, null, null, hasFile = true, fileId = 9101, isNew = true)

    private fun show(hero: Hero, episodes: List<EpisodeCard>) = compose.setContent {
        WatchMyMediaPhoneTheme {
            HomeHero(
                hero, episodes, emptyList(), library,
                onPlay = { played += it },
                onOpenShow = { opened += "show $it" },
                onOpenMovie = { opened += "movie $it" },
                onOpenEpisode = { show, season, episode -> opened += "episode $show/$season/$episode" },
            )
        }
    }

    @Test fun `an episode with its card opens the episode, in its season, and plays it`() {
        show(episodeHero, listOf(card))
        compose.onNodeWithText("Go to episode").performClick()
        compose.onNodeWithText("Play").performClick()
        assertEquals(listOf("episode 100/3/7"), opened)
        assertEquals("S03 E07 · The Long Dark", played.single().subtitle)
    }

    @Test fun `an episode without its card opens the show`() {
        show(episodeHero, emptyList())
        compose.onNodeWithText("Go to show").performClick()
        assertEquals(listOf("show 100"), opened)
    }

    @Test fun `a movie opens the movie`() {
        show(episodeHero.copy(kind = Hero.Kind.Movie, showId = null, movieId = 42, episodeId = null), emptyList())
        compose.onNodeWithText("Go to movie").performClick()
        assertEquals(listOf("movie 42"), opened)
    }
}
