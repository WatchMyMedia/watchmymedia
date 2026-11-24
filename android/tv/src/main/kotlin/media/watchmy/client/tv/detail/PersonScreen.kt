package media.watchmy.client.tv.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PersonPage
import media.watchmy.client.model.PersonTitle
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.person.PersonViewModel
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.PosterHeight
import media.watchmy.client.tv.components.PosterWidth
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.home.FactsLine
import media.watchmy.client.tv.library.gridFacts
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ReportShown
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.placeholderColor

/**
 * Where a cast member leads: their picture and name, how many of their
 * titles the library has, then their movies and their shows in rows, each
 * poster with the part they play, and the focused title's facts beside the
 * row's heading. OK opens a title on top; Back returns to the page the
 * person was chosen on, with them focused in its cast. When the title they
 * came from is their only one here, the screen says so and offers the way
 * back. [library] is the server the title came from: a page opened in
 * place on another server shows that server's titles.
 */
@Composable
fun PersonScreen(
    personId: Long,
    name: String,
    from: String?,
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    library: LibraryRepository = LocalAppServices.current.library,
    /** The server the person's page is for, when it isn't the one in use. */
    key: String? = null,
) {
    val vm = viewModel(key = "tv-person-$key-$personId") { PersonViewModel(library, personId, name) }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    TvScaffold(null, onNavigate, modifier, sectionRoot = false) {
        when (val page = state.person) {
            Load.Loading -> LoadingState()
            is Load.Failed -> if (state.gone) {
                OnlyTitle(name, from, onBack)
            } else {
                ErrorState(page.message, vm::retry, title = "Couldn’t load $name’s titles")
            }
            is Load.Ready -> {
                val p = page.data
                if (p.count <= 1 && from != null && p.titles().all { it.item.title == from }) {
                    OnlyTitle(p.name, from, onBack, p.profilePath?.let { library.imageUrl(it, ImageSize.W185) })
                } else {
                    PersonTitles(p, library, memory, from, onOpenMovie, onOpenShow)
                }
            }
        }
    }
}

private fun PersonPage.titles(): List<PersonTitle> = movies + shows

/** The person's picture, name and count, at the top left; Back's hint at the right. */
@Composable
private fun PersonHeader(name: String, line: String, picture: String?, from: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        val face = Modifier.size(132.dp)
        if (picture != null) {
            Artwork(picture, seed = name, contentDescription = null, modifier = face, shape = CircleShape)
        } else {
            Box(face.background(placeholderColor(name), CircleShape).border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = TvType.Title, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
        if (from != null) KeyHints(listOf("Back" to from), Modifier.align(Alignment.Top).padding(end = 170.dp))
    }
}

/** "5 titles in your library: 3 movies and 2 shows". */
private fun PersonPage.countLine(): String = when {
    movies.isNotEmpty() && shows.isNotEmpty() -> "${plural(count, "title")} in your library: ${plural(movies.size, "movie")} and ${plural(shows.size, "show")}"
    else -> "${plural(count, "title")} in your library"
}

@Composable
private fun PersonTitles(
    p: PersonPage,
    library: LibraryRepository,
    memory: FocusMemory,
    from: String?,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(p.titles().firstOrNull()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = SafeY, bottom = SafeY)) {
        PersonHeader(p.name, p.countLine(), p.profilePath?.let { library.imageUrl(it, ImageSize.W185) }, from)
        val groups = listOf("Movies" to p.movies, "TV Shows" to p.shows).filter { it.second.isNotEmpty() }
        groups.forEachIndexed { g, (heading, titles) ->
            Row(
                Modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd, top = if (g == 0) 44.dp else 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(heading, style = TvType.CardTitle, color = WmmColors.Text, maxLines = 1)
                Text(titles.size.toString(), style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
                Spacer(Modifier.weight(1f))
                // The focused title's facts sit beside the heading of its own row.
                val f = focused?.takeIf { it in titles }
                if (f != null) {
                    Text(f.item.title, style = TvType.Body, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
                    Text("·", style = TvType.Body, color = TvPalette.Muted)
                    FactsLine(f.item.gridFacts().take(4))
                }
            }
            CardRow(Modifier.padding(top = 20.dp)) {
                items(titles.size) { i ->
                    val t = titles[i]
                    val key = "person:${t.item.kind}:${t.item.id}"
                    MediaCard(
                        title = t.item.title,
                        subtitle = t.character?.let { "as $it" },
                        imageUrl = library.imageUrl(t.item.posterPath, ImageSize.W342),
                        artWidth = PosterWidth,
                        artHeight = PosterHeight,
                        onClick = { if (t.item.kind == ItemKind.Movie) onOpenMovie(t.item.id) else onOpenShow(t.item.id) },
                        modifier = Modifier.focusMemory(memory, key).onFocused { focused = t },
                    ) {
                        ArtWatched(t.item.watched)
                        ArtProgress(t.item.progress)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val first = p.titles().firstOrNull()?.let { "person:${it.item.kind}:${it.item.id}" }
        memory.restore(fallback = first)
    }
}

/** The title the person was chosen on is their only one here: say so, and offer the way back. */
@Composable
private fun OnlyTitle(name: String, from: String?, onBack: () -> Unit, picture: String? = null) {
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(top = SafeY)) {
        PersonHeader(name, "1 title in your library", picture, from)
        Column(Modifier.padding(start = ContentStart, end = ContentEnd, top = 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (from != null) "$from is the only title here with $name" else "No other title here has $name",
                style = TvType.Heading,
                color = WmmColors.Text,
            )
            Text(
                "When another of their movies or shows is added to your library, it appears on this screen.",
                style = TvType.Body,
                color = WmmColors.TextSoft,
                modifier = Modifier.widthIn(max = 820.dp),
            )
            TvButton(
                if (from != null) "Back to $from" else "Back",
                onBack,
                Modifier.padding(top = 12.dp).focusRequester(focus),
                style = TvButtonStyle.Primary,
                growFrom = TransformOrigin(0f, 0.5f),
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}
