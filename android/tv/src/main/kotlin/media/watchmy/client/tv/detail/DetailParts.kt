package media.watchmy.client.tv.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.Person
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.placeholderColor

/** The 240×360 poster beside a detail header. */
@Composable
fun DetailPoster(url: String?, title: String, modifier: Modifier = Modifier) {
    Artwork(url, seed = title, contentDescription = title, modifier = modifier.size(240.dp, 360.dp), shape = RoundedCornerShape(14.dp))
}

/** "TV Shows › Harbor Lights". */
@Composable
fun Breadcrumb(section: String, title: String) {
    Text("$section › $title", style = inter(18.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun DetailTitle(title: String) {
    Text(title, style = inter(60.sp, FontWeight.ExtraBold, lineHeight = 64.sp, tracking = -0.025f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** The facts line: parts joined by grey dots, then [extra] (tags, the status pill). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FactsLine(parts: List<String>, extra: @Composable RowScope.() -> Unit = {}) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("·", style = inter(21.sp), color = WmmColors.TextFaint)
            Text(p, style = inter(21.sp), color = TextBright)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = extra)
    }
}

/** The overview under the facts, two lines at most. */
@Composable
fun Overview(text: String) {
    if (text.isBlank()) return
    Text(text, style = inter(20.sp, lineHeight = 30.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1000.dp))
}

/** "Continuing · next episode Nov 4" in a gold pill, or a grey "Ended". */
@Composable
fun StatusPill(text: String, gold: Boolean) {
    val color = if (gold) WmmColors.Accent else WmmColors.TextMuted
    Row(
        Modifier.padding(start = 8.dp).border(1.dp, if (gold) WmmColors.Accent else WmmColors.Line3, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = inter(16.sp, FontWeight.SemiBold), color = color)
    }
}

/** A cast member: a 72 dp circle, the name and the role. */
@Composable
fun CastMember(person: Person, imageUrl: String?) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (imageUrl != null) {
            Artwork(imageUrl, seed = person.name, contentDescription = person.name, modifier = Modifier.size(72.dp), shape = CircleShape)
        } else {
            Box(Modifier.size(72.dp).background(placeholderColor(person.name), CircleShape))
        }
        Text(person.name, style = inter(15.sp, FontWeight.SemiBold, lineHeight = 18.sp), color = WmmColors.Text, textAlign = TextAlign.Center, maxLines = 2)
        person.role?.let { Text(it, style = inter(13.sp, lineHeight = 16.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 2) }
    }
}

/**
 * Cast & details: a side sheet with the full overview, [facts] as label and
 * value rows, and the cast. Back or Close dismisses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsSheet(title: String, overview: String, facts: List<Pair<String, String>>, cast: List<Person>, castImage: (Person) -> String?, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(760.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(title, style = inter(34.sp, FontWeight.Bold, tracking = -0.02f), color = WmmColors.Text, maxLines = 2)
                if (overview.isNotBlank()) Text(overview, style = inter(19.sp, lineHeight = 29.sp), color = WmmColors.TextSoft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Column {
                    for ((label, value) in facts) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(label.uppercase(), style = inter(15.sp, FontWeight.SemiBold, tracking = 0.06f), color = WmmColors.TextMuted, modifier = Modifier.width(170.dp).padding(top = 3.dp))
                            Text(value, style = inter(19.sp), color = WmmColors.Text)
                        }
                    }
                }
                if (cast.isNotEmpty()) {
                    Text("Cast", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), maxLines = 2) {
                        for (p in cast.take(12)) CastMember(p, castImage(p))
                    }
                }
                Box(Modifier.weight(1f))
                TvButton("Close", onDismiss, Modifier.focusRequester(close), style = TvButtonStyle.Ghost)
            }
        }
        InitialFocus(close)
    }
}
