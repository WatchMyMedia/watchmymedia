package media.watchmy.client.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.REMOVE_FROM_CONTINUE
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.episodeCards
import media.watchmy.client.model.playRequest
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.ui.InPlace
import media.watchmy.client.ui.imageFor
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat

/**
 * What Home's first screen leads with, on its own, before the rows: the
 * title the person is most likely to go back to, from Continue watching
 * (the server orders that row by it), or the server's pick when nothing is
 * in progress. Every app leads Home this way.
 */
@Immutable
internal sealed interface HomeLead {
    /** The focus key of the main button, where Home's focus starts. */
    val key: String

    data class Resume(val item: ContinueItem) : HomeLead {
        override val key: String get() = LEAD_RESUME
    }

    data class Pick(val hero: Hero) : HomeLead {
        override val key: String get() = if (hero.fileId != null) "hero:play" else "hero:open"
    }
}

internal const val LEAD_RESUME = "lead:resume"
internal const val LEAD_OPEN = "lead:open"
internal const val LEAD_REMOVE = "lead:remove"

/** Home's lead and the rows that follow it ([homeLead]). */
@Immutable
internal data class LeadAndRows(val lead: HomeLead?, val rows: List<ScreenRow>)

/**
 * The lead for arranged Home's [rows] (those drawn): Continue watching's
 * first title, wherever the person put that row, which then leaves the row
 * (and the row goes when it held only that one); else [hero], the server's
 * pick; else nothing, and Home opens on its first row.
 */
internal fun homeLead(hero: Hero?, rows: List<ScreenRow>): LeadAndRows {
    val continuing = rows.firstOrNull { it.style == RowStyle.Continue && it.continueItems.isNotEmpty() }
        ?: return LeadAndRows(hero?.let(HomeLead::Pick), rows)
    val rest = continuing.continueItems.drop(1)
    val after = if (rest.isEmpty()) rows.filterNot { it === continuing } else rows.map { if (it === continuing) it.copy(continueItems = rest) else it }
    return LeadAndRows(HomeLead.Resume(continuing.continueItems.first()), after)
}

/** The lead for Home from servers without rows: Continue watching's first title, else the server's pick. */
internal fun HomeData.fixedLead(): HomeLead? = continueWatching.firstOrNull()?.let(HomeLead::Resume) ?: hero?.let(HomeLead::Pick)

/** Where the lead's words start from the top, so it sits in the middle of the screen with the next row's name below. */
internal val LeadTop = 286.dp

/**
 * Home's first screen: the lead's picture filling the screen, faded from the
 * left and the bottom, and over it what the lead is ([LeadPanel]). The
 * caller draws the next row's name along the foot.
 */
@Composable
internal fun LeadScreen(
    lead: HomeLead,
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    actions: TvRowActions,
    modifier: Modifier = Modifier,
    buttons: Modifier = Modifier,
    onRemoved: () -> Unit = {},
) {
    val picture = when (lead) {
        is HomeLead.Resume -> lead.item.let { library.imageFor(it.where, it.stillPath ?: it.backdropPath, ImageSize.W1280) }
        is HomeLead.Pick -> library.imageUrl(lead.hero.backdropPath, ImageSize.W1280)
    }
    val seed = when (lead) {
        is HomeLead.Resume -> lead.item.title
        is HomeLead.Pick -> lead.hero.title
    }
    Box(modifier.fillMaxSize()) {
        RowBackdrop(picture, seed)
        LeadPanel(lead, home, memory, actions, Modifier.padding(start = ContentStart, top = LeadTop).width(1100.dp), buttons, onRemoved)
    }
}

/**
 * The lead's words and buttons. Resuming: "Continue watching", the title,
 * the episode, "29 min left · ends 8:41 PM", how far in, two lines about
 * it, then Resume, Go to episode (or Go to movie) and, where the server
 * can, Remove from Continue watching. The server's pick: its kicker, title,
 * facts and two lines, then Play and Go to episode, Go to show or More info.
 */
@Composable
internal fun LeadPanel(
    lead: HomeLead,
    home: HomeData,
    memory: FocusMemory,
    actions: TvRowActions,
    modifier: Modifier = Modifier,
    buttons: Modifier = Modifier,
    onRemoved: () -> Unit = {},
) {
    Column(modifier) {
        when (lead) {
            is HomeLead.Resume -> ResumeLead(lead.item, memory, actions, onRemoved, buttons)
            is HomeLead.Pick -> PickLead(lead.hero, home, memory, actions, buttons)
        }
    }
}

/** [modifier] is the buttons' row's, which takes the page's ▲ ▼. */
@Composable
private fun ResumeLead(item: ContinueItem, memory: FocusMemory, actions: TvRowActions, onRemoved: () -> Unit, modifier: Modifier = Modifier) {
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    val focus = remember(item, now, format) { continueFocus(item, now, format) }
    var removeFocused by remember { mutableStateOf(false) }
    HomeFocusPanel(focus)
    Row(modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        TvButton("Resume", { actions.onPlay(item.playRequest()) }, Modifier.focusMemory(memory, LEAD_RESUME), style = TvButtonStyle.Primary, icon = WmmIcons.Play, growFrom = TransformOrigin(0f, 0.5f))
        TvButton(if (item.movieId != null && item.showId == null) "Go to movie" else "Go to episode", { item.openLeadPage(actions) }, Modifier.focusMemory(memory, LEAD_OPEN))
        actions.onHideContinue?.let { hide ->
            TvButton(
                REMOVE_FROM_CONTINUE,
                {
                    hide(item)
                    onRemoved()
                },
                Modifier.focusMemory(memory, LEAD_REMOVE).onFocusChanged { removeFocused = it.isFocused },
            )
        }
    }
    // What Remove does, while it has the focus: the place is kept, and the next title takes the card.
    // Nothing is below it, so nothing moves as it comes and goes.
    if (removeFocused) Text(LEAD_REMOVE_NOTE, style = TvType.Second, color = TvPalette.Muted, maxLines = 1, modifier = Modifier.padding(top = 18.dp))
}

/** What Remove from Continue watching does, under the buttons while it has the focus. */
internal const val LEAD_REMOVE_NOTE = "Your place in it is kept. The next title you were watching takes this card."

/** [modifier] is the buttons' row's, as [ResumeLead]'s. */
@Composable
private fun PickLead(hero: Hero, home: HomeData, memory: FocusMemory, actions: TvRowActions, modifier: Modifier = Modifier) {
    val focus = remember(hero) {
        HomeFocus(
            kicker = hero.kicker,
            title = hero.title,
            facts = listOfNotNull(hero.facts.takeIf { it.isNotBlank() }?.let(::HomeFact), hero.contentRating?.let { HomeFact(it, HomeFact.Kind.Tag) }),
            overview = hero.overview,
        )
    }
    HomeFocusPanel(focus)
    Row(modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        val play = hero.playRequest(home.episodeCards)
        if (play != null) {
            TvButton("Play", { actions.onPlay(play) }, Modifier.focusMemory(memory, "hero:play"), style = TvButtonStyle.Primary, icon = WmmIcons.Play, growFrom = TransformOrigin(0f, 0.5f))
        }
        // An episode's season comes from its card on Home; without one the hero goes to the show.
        val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.episodeCards.firstOrNull { it.episodeId == id } }
        val open: (() -> Unit)? = when {
            episode != null -> { { actions.onOpenEpisode(episode.showId, episode.season, episode.episodeId) } }
            hero.showId != null -> { { actions.onOpenShow(hero.showId!!) } }
            hero.movieId != null -> { { actions.onOpenMovie(hero.movieId!!) } }
            else -> null
        }
        if (open != null) {
            TvButton(
                when {
                    episode != null -> "Go to episode"
                    hero.kind == Hero.Kind.Movie -> "More info"
                    else -> "Go to show"
                },
                open,
                Modifier.focusMemory(memory, "hero:open"),
                style = if (play == null) TvButtonStyle.Primary else TvButtonStyle.Ghost,
                growFrom = if (play == null) TransformOrigin(0f, 0.5f) else TransformOrigin.Center,
            )
        }
    }
}

/** The lead's page: the episode on its show's page, or the movie's, from the server it is on. */
private fun ContinueItem.openLeadPage(actions: TvRowActions) {
    val show = showId
    val movie = movieId
    val page = when {
        show != null -> DeepLink.Show(show, season, episodeId)
        movie != null -> DeepLink.Movie(movie)
        else -> return
    }
    if (InPlace.open(where.serverId, page)) return
    val s = season
    val e = episodeId
    when {
        show != null && s != null && e != null -> actions.onOpenEpisode(show, s, e)
        show != null -> actions.onOpenShow(show)
        movie != null -> actions.onOpenMovie(movie)
    }
}
