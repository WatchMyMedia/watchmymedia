package media.watchmy.client.mobile.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.model.Person
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle

/** How far the page scrolls before the bar turns solid: about where the title meets it. */
internal val HeaderScroll = 110.dp

/**
 * The top of Show and Movie: the backdrop fading into the ground, the
 * poster, and [info] (title, facts, tags) beside it, as the mockups place
 * them under a 64 dp bar.
 */
@Composable
fun DetailHeader(title: String, backdropUrl: String?, posterUrl: String?, info: @Composable ColumnScope.() -> Unit) {
    val layout = rememberTabletLayout()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropHeight = if (layout.isTablet) 300.dp else 182.dp
    val posterWidth = if (layout.twoPane) 120.dp else if (layout.isTablet) 160.dp else 104.dp
    Box(Modifier.fillMaxWidth()) {
        Artwork(backdropUrl, seed = title, contentDescription = null, modifier = Modifier.fillMaxWidth().height(top + backdropHeight))
        Box(
            Modifier.fillMaxWidth().height(top + backdropHeight).background(
                Brush.verticalGradient(0f to WmmColors.Ground.copy(alpha = 0.5f), 0.35f to Color.Transparent, 1f to WmmColors.Ground),
            ),
        )
        Row(Modifier.padding(start = if (layout.isTablet) 24.dp else 16.dp, end = if (layout.isTablet) 24.dp else 16.dp, top = top + if (layout.isTablet) 96.dp else 102.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(
                posterUrl,
                seed = title,
                contentDescription = title,
                modifier = Modifier.size(posterWidth, posterWidth * 1.5f).shadow(12.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            )
            Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = info)
        }
    }
}

@Composable
fun DetailTitle(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 25.2.sp, letterSpacing = (-0.48).sp, color = WmmColors.Text),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The facts under a title ("2022– · 3 seasons · 28 episodes"). */
@Composable
fun DetailFacts(lines: List<String>) {
    Text(lines.joinToString("\n"), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextSoft))
}

/** Outlined tags ("4K", "HEVC"). */
@Composable
fun Tags(tags: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.forEach { Tag(it) }
    }
}

@Composable
fun Tag(text: String, gold: Boolean = false) {
    Badge(text, style = if (gold) BadgeStyle.Gold else BadgeStyle.Outline, horizontalPadding = 6.dp, verticalPadding = 2.dp)
}

/** The overview: two lines, the rest on tap. */
@Composable
fun Overview(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 2) {
    if (text.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.5.sp, color = WmmColors.TextSoft),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.animateContentSize().clickable { expanded = !expanded },
    )
}

/**
 * The bar over the backdrop: Back, and More with [actions]. Once the header
 * has scrolled away ([solid]) it takes the ground colour and shows [title].
 */
@Composable
fun DetailBar(onBack: () -> Unit, actions: List<CardAction>, modifier: Modifier = Modifier, title: String? = null, solid: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val ground by animateColorAsState(if (solid) WmmColors.Ground else Color.Transparent, label = "bar")
    BackTopBar(title?.takeIf { solid }, onBack, modifier.background(ground), overArt = !solid) {
        CastButton(overArt = !solid)
        if (actions.isNotEmpty()) {
            Box {
                BarIconButton(PhoneIcons.MoreVert, "More", { menu = true }, overArt = !solid)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WmmColors.Surface3) {
                    actions.forEach { a ->
                        DropdownMenuItem(text = { Text(a.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text)) }, onClick = {
                            menu = false
                            a.onClick()
                        })
                    }
                }
            }
        }
    }
}

/** A section heading inside a detail page ("Versions", "Cast"). */
@Composable
fun DetailHeading(text: String, modifier: Modifier = Modifier, small: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline())
        if (small != null) Text("· $small", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
    }
}

/** Cast as a sideways row of name and role. */
@Composable
fun CastRow(cast: List<Person>) {
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailHeading("Cast", modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cast) { person ->
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(WmmColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(person.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Text), maxLines = 1)
                    person.role?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted), maxLines = 1) }
                }
            }
        }
    }
}

/** The status pill under a show's facts: gold for continuing, grey for ended. */
@Composable
fun StatusPill(text: String, active: Boolean) {
    val color = if (active) WmmColors.Accent else WmmColors.TextMuted
    Row(
        Modifier.border(1.dp, color, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = color))
    }
}
