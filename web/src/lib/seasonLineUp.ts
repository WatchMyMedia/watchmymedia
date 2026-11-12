import type { components } from '../api/schema'

/**
 * Wording and small rules for season line-ups (PLAN-SEASON-MAPPING.md): a
 * season as the files number it, and where it starts on TMDB.
 */
export type SeasonLineUp = components['schemas']['SeasonLineUp']
export type SeasonSuggestion = components['schemas']['SeasonSuggestion']
export type SeasonRule = components['schemas']['SeasonRule']
export type PreviewFile = components['schemas']['PreviewFile']

const e2 = (n: number) => `E${String(n).padStart(2, '0')}`

/** "E01–E08", or "E03" for one episode. */
export function episodeRange(first: number, last: number): string {
  return first === last ? e2(first) : `${e2(first)}–${e2(last)}`
}

/** The files of a season: "8 files · E01–E08". */
export function filesLabel(lu: Pick<SeasonLineUp, 'fileIds' | 'fromEpisode' | 'lastEpisode'>): string {
  const n = lu.fileIds.length
  return `${n} file${n === 1 ? '' : 's'} · ${episodeRange(lu.fromEpisode, lu.lastEpisode)}`
}

/**
 * Where a suggestion puts the season: "Harbor Lights: Low Tide (2026) ·
 * Season 1, E01–E08", or both ends when the files run on into the next season.
 */
export function targetLabel(s: Pick<SeasonSuggestion, 'rule' | 'show' | 'lastSeason' | 'lastEpisode'>): string {
  const show = `${s.show.title}${s.show.year ? ` (${s.show.year})` : ''}`
  const { toSeason, toEpisode } = s.rule
  const where =
    s.lastSeason === toSeason
      ? `Season ${toSeason}, ${episodeRange(toEpisode, s.lastEpisode)}`
      : `Season ${toSeason}, ${e2(toEpisode)} to Season ${s.lastSeason}, ${e2(s.lastEpisode)}`
  return `${show} · ${where}`
}

/** "8 of 8 line up". */
export function fitLabel(found: number, total: number): string {
  return `${found} of ${total} line${total === 1 && found === 1 ? 's' : ''} up`
}

/** The dialog's count: "All 5 files line up", "4 of 5 files line up", "None of the 5 files line up". */
export function previewSummary(found: number, total: number): string {
  if (total === 1) return found === 1 ? 'The file lines up' : 'The file does not line up'
  if (found === total) return `All ${total} files line up`
  if (found === 0) return `None of the ${total} files line up`
  return `${found} of ${total} files line up`
}

/** "Season 4", "Seasons 6 and 7", "Seasons 22, 23 and 24". */
export function seasonList(seasons: number[]): string {
  const s = [...seasons].sort((a, b) => a - b)
  if (s.length === 1) return `Season ${s[0]}`
  return `Seasons ${s.slice(0, -1).join(', ')} and ${s[s.length - 1]}`
}

/** The remember tick: "Do the same for new Season 4 files in Harbor Lights". */
export function rememberLabel(seasons: number[], folder: string): string {
  return `Do the same for new ${seasonList(seasons).replace(/^Seasons/, 'Season')} files in ${folder}`
}

/** A card can be matched in one step once every season has a suggestion. */
export function allSuggested(lineUps: SeasonLineUp[]): boolean {
  return lineUps.length > 0 && lineUps.every((lu) => lu.suggestion != null)
}

/**
 * The rule the dialog opens on: the suggestion's, or the folder's show with
 * the files' own season, which the dialog shows as not chosen when the show
 * has no such season.
 */
export function startingRule(lu: SeasonLineUp, tmdbId: number): SeasonRule {
  return lu.suggestion?.rule ?? { fromSeason: lu.season, fromEpisode: lu.fromEpisode, tmdbId, toSeason: lu.season, toEpisode: 1 }
}

/** What a previewed file becomes: "S19 E01 · First Day · Aug 8, 2023", or why it has no place. */
export function previewTarget(f: PreviewFile, formatDate: (d: string) => string): { text: string; ok: boolean } {
  if (f.to.length === 0) return { text: f.reason || 'No episode on TMDB.', ok: false }
  const first = f.to[0]
  const code = `S${String(first.season).padStart(2, '0')} ${f.to.map((t) => e2(t.number)).join('–')}`
  const name = f.to.map((t) => t.title).filter(Boolean).join(' / ')
  return { text: [code, name, first.airDate ? formatDate(first.airDate) : ''].filter(Boolean).join(' · '), ok: true }
}

/** The banner after a match. */
export function matchedText(matched: number, needEpisodes: number, shows: string[], seasons: number[], folder?: string): string {
  let text = `Matched ${matched} file${matched === 1 ? '' : 's'} to ${[...new Set(shows)].join(' and ')}.`
  if (needEpisodes) text += ` ${needEpisodes} ha${needEpisodes === 1 ? 's' : 've'} no episode there and stay${needEpisodes === 1 ? 's' : ''} unmatched.`
  if (folder) text += ` New ${seasonList(seasons).replace(/^Seasons/, 'Season')} files in ${folder} will go there too.`
  return text
}
