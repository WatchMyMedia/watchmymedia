package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.formatDay
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.monthDay
import media.watchmy.client.model.plural
import media.watchmy.client.model.downloadSeasonLine
import media.watchmy.client.model.forFile
import media.watchmy.client.model.playable
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// The show page's parts that are the phone's own wording and controls (ShowPage.dc.html).

/** With [missing] on, a season says how much of it is here: "8 of 10", nothing when none is. */
@Composable
internal fun SeasonChip(season: SeasonSummary, selected: Boolean, missing: Boolean, onClick: () -> Unit) {
    val name = if (season.number == 0) "Specials" else "Season ${season.number}"
    val count = when {
        !missing || season.availableCount >= season.episodeCount -> "${season.availableCount}"
        season.availableCount == 0 -> null
        else -> "${season.availableCount} of ${season.episodeCount}"
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                if (count != null) Text(
                    count,
                    style = TextStyle(
                        fontFamily = Inter,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 14.sp,
                        color = if (selected) WmmColors.OnAccent else WmmColors.TextFaint,
                    ),
                )
            }
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = Color(0xFFE5E5E5),
            selectedContainerColor = WmmColors.Accent,
            selectedLabelColor = WmmColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = WmmColors.Line3, selectedBorderColor = WmmColors.Accent),
    )
}

/** "Aired Mar 14, 2024 · 50 min", or the runtime alone before it airs (its state says when). */
internal fun missingMeta(ep: EpisodeDetail): String {
    val day = formatDay(ep.airDate)
    return listOfNotNull(day?.takeIf { ep.aired }?.let { "Aired $it" }, ep.runtimeMin?.let { "$it min" }).joinToString(" · ")
}

/**
 * The season's ⋮ beside its summary (ShowDownloadSeason.dc.html): Download
 * season, every episode with a file not downloaded yet, at the Settings quality.
 */
@Composable
internal fun SeasonDownloadMenu(summary: SeasonSummary, season: SeasonDetail, downloads: DownloadsViewModel.State, onDownloadSeason: (List<Long>, Int) -> Unit) {
    val todo = season.episodes.filter { it.playable }.mapNotNull { ep -> ep.versions.firstOrNull()?.let { ep to it } }.filter { (_, v) -> downloads.all.forFile(v.fileId) == null }
    if (!downloads.canDownload || todo.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    val name = seasonName(summary.number)
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
            Icon(PhoneIcons.MoreVert, contentDescription = "$name options", tint = WmmColors.Text)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF1C1C1F), shape = RoundedCornerShape(12.dp)) {
            DropdownMenuItem(
                modifier = Modifier.width(280.dp),
                leadingIcon = { Icon(WmmIcons.Download, contentDescription = null, tint = Color(0xFFE5E5E5)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Download season", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Color(0xFFE5E5E5)))
                        Text(
                            downloadSeasonLine(todo.map { (ep, v) -> v.sizeBytes to (v.durationMs ?: (ep.runtimeMin ?: 0) * 60_000L) }, downloads.settings.quality),
                            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                        )
                    }
                },
                onClick = {
                    menu = false
                    onDownloadSeason(todo.map { it.second.fileId }, summary.number)
                },
            )
        }
    }
}

/** The board's snackbar after Download season (`div[role=status]`): light, with View for Downloads. */
@Composable
internal fun ViewSnackbar(message: String?, onView: () -> Unit, onShown: () -> Unit, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            val result = host.showSnackbar(message, actionLabel = "View", duration = SnackbarDuration.Long)
            onShown()
            if (result == SnackbarResult.ActionPerformed) onView()
        }
    }
    SnackbarHost(host, modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
        Snackbar(it, containerColor = Color(0xFFE5E5E5), contentColor = WmmColors.Ground, actionColor = WmmColors.Brand, shape = RoundedCornerShape(8.dp))
    }
}

/** "2022– · 3 seasons · 28 episodes", then "Drama, Crime · ★ 8.1". */
internal fun showFacts(show: ShowDetail): List<String> {
    val seasons = show.seasons.count { it.number > 0 }
    val ended = show.status.isEnded()
    val first = listOfNotNull(
        show.year?.let { if (ended) "$it" else "$it–" },
        plural(seasons, "season").takeIf { seasons > 0 },
        plural(show.episodesOnDisk, "episode").takeIf { show.episodesOnDisk > 0 },
    ).joinToString(" · ")
    val second = listOfNotNull(show.genres.take(2).joinToString(", ").ifEmpty { null }, show.contentRating, formatRating(show.rating)).joinToString(" · ")
    return listOf(first, second).filter { it.isNotEmpty() }
}

/** "Continuing · next Nov 4" (gold) or "Ended". */
internal fun statusLine(show: ShowDetail): Pair<String, Boolean>? {
    val status = show.status ?: return null
    if (status.isEnded()) return (if (status.contains("cancel", ignoreCase = true)) "Cancelled" else "Ended") to false
    val next = monthDay(show.nextAirDate)
    return (if (next != null) "Continuing · next $next" else "Continuing") to true
}

private fun String?.isEnded() = this != null && (contains("ended", ignoreCase = true) || contains("cancel", ignoreCase = true))

/** "Aired yesterday · 52 min", "Airs Nov 4". */
internal fun episodeMeta(ep: EpisodeDetail): String {
    val day = formatDay(ep.airDate)
    return when {
        !ep.aired -> day?.let { "Airs $it" } ?: "Not aired yet"
        else -> listOfNotNull(day?.let { "Aired $it" }, ep.runtimeMin?.let { "$it min" }).joinToString(" · ")
    }
}
