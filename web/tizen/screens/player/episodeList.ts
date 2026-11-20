import type { components } from '../../../src/api/schema'
import { episodeMinutes, isPartlyWatched } from '../../../src/lib/episode'
import { seasonName, seasonTabs } from '../../../src/lib/showPage'

// The Episodes panel's rules (PlayerEpisodes, Android TV's
// PlayerEpisodesViewModel): which show and season the file playing belongs
// to, the season tabs, the episodes that can play, and each one's line.

type Episode = components['schemas']['Episode']
type Season = components['schemas']['Season']
type FileDetail = Pick<components['schemas']['MediaFileDetail'], 'showId' | 'seasonNumber' | 'episodeNumber' | 'episodeId'>

export type EpisodeContext = { showId: number; season: number; episode: number }

/** The file's show and place in it; undefined for a file that is no episode. */
export function episodeContext(file: FileDetail | undefined): EpisodeContext | undefined {
  if (!file?.showId || file.seasonNumber === undefined || file.episodeNumber === undefined || !file.episodeId) return undefined
  return { showId: file.showId, season: file.seasonNumber, episode: file.episodeNumber }
}

export type SeasonTab = { number: number; name: string }

/** The tabs: the seasons with episodes on disk (and the one open), by number with Specials last. */
export function seasonTabList(seasons: Pick<Season, 'number' | 'availableCount'>[] | undefined, open: number | undefined): SeasonTab[] {
  return seasonTabs(seasons ?? [], false, open).map((s) => ({ number: s.number, name: seasonName(s.number) }))
}

/** The episodes that can play, in order: the ones with a file. */
export function playableEpisodes<E extends Pick<Episode, 'files'>>(episodes: E[] | undefined): E[] {
  return (episodes ?? []).filter((e) => e.files.length > 0)
}

/** The episode whose files include the one playing. */
export function playingEpisode<E extends Pick<Episode, 'files'>>(episodes: E[], fileId: number | undefined): E | undefined {
  return fileId === undefined ? undefined : episodes.find((e) => e.files.some((f) => f.id === fileId))
}

/** The one before the playing one in its season, for the remote's Previous; none at the first. */
export function previousEpisode<E extends Pick<Episode, 'files'>>(episodes: E[], fileId: number | undefined): E | undefined {
  const playing = playingEpisode(episodes, fileId)
  return playing ? episodes[episodes.indexOf(playing) - 1] : undefined
}

/** What an episode plays: its first file. */
export const episodeFile = (e: Pick<Episode, 'files'>) => e.files[0]?.id

/** A row's line: "52 min · 14 min in · playing now", "52 min · watched", "52 min". */
export function episodeRowLine(e: Pick<Episode, 'runtimeMin' | 'files' | 'watch'>, playing: boolean, minutesIn: number): string {
  const min = episodeMinutes(e)
  const runtime = min ? `${min} min` : undefined
  const rest = playing ? [`${minutesIn} min in`, 'playing now'] : e.watch?.completed ? ['watched'] : []
  return [runtime, ...rest].filter(Boolean).join(' · ')
}

/** The still's progress bar: the playing episode's place, else a partly watched one's; undefined for none. */
export function episodeProgress(e: Pick<Episode, 'watch' | 'files'>, playing: { positionMs: number; durationMs: number } | undefined): number | undefined {
  if (playing) return playing.durationMs > 0 ? Math.min(1, playing.positionMs / playing.durationMs) : undefined
  if (!isPartlyWatched(e) || !e.watch) return undefined
  const duration = e.watch.durationMs || e.files[0]?.durationMs || 0
  return duration > 0 ? Math.min(1, e.watch.positionMs / duration) : undefined
}
