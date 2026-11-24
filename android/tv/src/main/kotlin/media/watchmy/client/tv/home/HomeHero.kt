package media.watchmy.client.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.episodeCards
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.playRequest
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.ui.open
import media.watchmy.client.ui.imageFor
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** "S03 E07 · The Long Dark · 52 min · TV-14". The picture and sound are said on the title's page, never on Home. */
@Composable
private fun HeroFacts(hero: Hero) {
    val style = inter(24.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        var before = false
        if (hero.facts.isNotBlank()) {
            Text(hero.facts, style = style, color = TextBright, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            before = true
        }
        hero.contentRating?.let {
            if (before) Text("·", style = style, color = WmmColors.TextFaint)
            Text(it, style = style, color = TextBright, maxLines = 1)
        }
    }
}

@Composable
internal fun HeroSection(
    hero: Hero,
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenEpisode: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(476.dp).bringWholeIntoView()) {
        // The backdrop runs on behind the first row (620 tall from the top, starting 560 in).
        Backdrop(
            url = library.imageUrl(hero.backdropPath, ImageSize.W1280),
            seed = hero.title,
            modifier = Modifier
                .offset(x = 560.dp)
                .width(1360.dp)
                .overflowHeight(620.dp, reported = 476.dp),
            leftFade = listOf(0f to 1f, 0.22f to 0.85f, 0.6f to 0f),
            bottomFadeFrom = 0.55f,
            glowX = 0.7f,
            glowY = 0.3f,
        )
        Column(Modifier.padding(start = ContentStart, top = 112.dp).width(900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Kicker(hero.kicker, fontSize = 22.sp)
            Text(
                hero.title,
                style = inter(64.sp, FontWeight.ExtraBold, lineHeight = 80.sp, tracking = -0.025f),
                color = WmmColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HeroFacts(hero)
            Text(
                hero.overview,
                style = inter(22.sp, lineHeight = 33.sp),
                color = WmmColors.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Measured after the buttons: with larger text the overview gives up its lines, not the buttons their height.
                modifier = Modifier.widthIn(max = 760.dp).weight(1f, fill = false),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val play = hero.playRequest(home.episodeCards)
                if (play != null) {
                    TvButton(
                        "Play",
                        { onPlay(play) },
                        Modifier.focusMemory(memory, "hero:play"),
                        style = TvButtonStyle.Primary,
                        icon = WmmIcons.Play,
                    )
                }
                val open: (() -> Unit)? = when {
                    onOpenEpisode != null -> onOpenEpisode
                    hero.showId != null -> { { onOpenShow(hero.showId!!) } }
                    hero.movieId != null -> { { onOpenMovie(hero.movieId!!) } }
                    else -> null
                }
                if (open != null) {
                    TvButton(
                        when {
                            onOpenEpisode != null -> "Go to episode"
                            hero.kind == Hero.Kind.Movie -> "More info"
                            else -> "Go to show"
                        },
                        open,
                        Modifier.focusMemory(memory, "hero:open"),
                        style = if (play == null) TvButtonStyle.Primary else TvButtonStyle.Ghost,
                    )
                }
            }
        }
    }
}

/**
 * Home's lead on servers without rows, when something is in progress: the
 * words and buttons arranged Home's lead has ([LeadPanel]), over the picture
 * as the server's pick lays it out, above Continue watching's other titles.
 */
@Composable
internal fun FixedResumeLead(lead: HomeLead.Resume, home: HomeData, library: LibraryRepository, memory: FocusMemory, actions: media.watchmy.client.tv.rows.TvRowActions) {
    val item = lead.item
    Box(Modifier.fillMaxWidth().height(600.dp).bringWholeIntoView()) {
        Backdrop(
            url = library.imageFor(item.where, item.stillPath ?: item.backdropPath, ImageSize.W1280),
            seed = item.title,
            modifier = Modifier
                .offset(x = 560.dp)
                .width(1360.dp)
                .overflowHeight(720.dp, reported = 600.dp),
            leftFade = listOf(0f to 1f, 0.22f to 0.85f, 0.6f to 0f),
            bottomFadeFrom = 0.55f,
            glowX = 0.7f,
            glowY = 0.3f,
        )
        LeadPanel(lead, home, memory, actions, Modifier.padding(start = ContentStart, top = 112.dp).width(1100.dp))
    }
}
