import type { components } from '../../../src/api/schema'
import { plural } from '../../../src/lib/format'

// The request dialog's rules (Requests on the Samsung TV canvas, Tweak
// `view`: `dialog`), as Android TV's RequestFormViewModel has them: which
// seasons can be ticked and which start ticked, the button's words, and
// what the dialog says once it is asked for.

type Requestable = components['schemas']['Requestable']
type RequestableSeason = components['schemas']['RequestableSeason']
type CreatedRequest = components['schemas']['CreatedRequest']

export type SeasonChoice = {
  number: number
  name: string
  /** "10 episodes · 2022", or "Not aired yet". */
  detail: string
  /** "In library", "Partly in library", "Requested". */
  note?: string
  /** In the library or asked for already: shown greyed, with its icon, and never ticked. */
  locked: boolean
  /** Ticked when the dialog opens: what is missing or partly there; specials and unaired seasons are left to choose. */
  suggested: boolean
}

const NOTES: Partial<Record<RequestableSeason['state'], string>> = { available: 'In library', partial: 'Partly in library', requested: 'Requested' }

export function seasonChoices(seasons: readonly RequestableSeason[] | null | undefined): SeasonChoice[] {
  return (seasons ?? []).map((s) => {
    const locked = s.state === 'available' || s.state === 'requested'
    const year = s.airDate?.slice(0, 4)
    return {
      number: s.number,
      name: s.number === 0 ? 'Specials' : s.name || `Season ${s.number}`,
      detail: s.state === 'unaired' ? 'Not aired yet' : [plural(s.episodeCount, 'episode'), year].filter(Boolean).join(' · '),
      note: NOTES[s.state],
      locked,
      suggested: !locked && s.number > 0 && (s.state === 'missing' || s.state === 'partial'),
    }
  })
}

/** The seasons ticked when the dialog opens: the ones asked for (a show page's Request season N), else the suggested ones. */
export function initialPicks(choices: readonly SeasonChoice[], asked?: readonly number[]): number[] {
  const open = choices.filter((c) => !c.locked)
  return (asked?.length ? open.filter((c) => asked.includes(c.number)) : open.filter((c) => c.suggested)).map((c) => c.number)
}

export function toggle(picked: readonly number[], season: number): number[] {
  return picked.includes(season) ? picked.filter((n) => n !== season) : [...picked, season]
}

/** The ticked seasons that can still be asked for, in order. */
export function chosen(picked: readonly number[], choices: readonly SeasonChoice[]): number[] {
  return picked.filter((n) => choices.some((c) => c.number === n && !c.locked)).sort((a, b) => a - b)
}

/** The button: "Request movie", "Request" (nothing ticked), "Request 1 season", "Request 3 seasons". */
export function requestLabel(mediaType: 'movie' | 'tv', count: number): string {
  if (mediaType === 'movie') return 'Request movie'
  return count === 0 ? 'Request' : `Request ${plural(count, 'season')}`
}

/** Under the title: "2021 · 4 seasons · TV show", "2024 · Movie". */
export function metaLine(item: Pick<Requestable, 'mediaType' | 'year' | 'seasons'>): string {
  const show = item.mediaType === 'tv'
  const seasons = (item.seasons ?? []).filter((s) => s.number > 0).length
  return [item.year ? String(item.year) : undefined, show && seasons ? plural(seasons, 'season') : undefined, show ? 'TV show' : 'Movie'].filter(Boolean).join(' · ')
}

/** Who approves, under the buttons; nothing when the person's requests don't wait. */
export function approvalNote(options: { needsApproval: boolean; backend: string } | undefined): string | undefined {
  if (!options?.needsApproval) return undefined
  return options.backend === 'seerr' ? 'Requests go to Seerr. Approval, quotas and notifications happen there.' : 'An admin approves requests before anything downloads.'
}

/**
 * What can be done with a title as it stands: ask for it, or nothing,
 * because a movie is in the library or on its way already. A show can
 * always be opened for its seasons.
 */
export function standing(item: Pick<Requestable, 'mediaType' | 'state'>): 'ask' | 'inLibrary' | 'asked' {
  if (item.mediaType === 'tv') return 'ask'
  if (item.state === 'available') return 'inLibrary'
  return item.state === 'requested' || item.state === 'downloading' ? 'asked' : 'ask'
}

/** What the dialog says once it is asked for. */
export function sentWords(reply: Pick<CreatedRequest, 'created'> & { request: Pick<CreatedRequest['request'], 'mine' | 'requestedBy'> }, backend: string | undefined): { title: string; body: string } {
  if (reply.created || reply.request.mine) {
    return { title: backend === 'seerr' ? 'Sent to Seerr' : 'Requested', body: 'You’ll find it in Settings › Your requests.' }
  }
  return { title: `${reply.request.requestedBy} asked for this already`, body: 'It’s in their requests, and you’ll see it here when it arrives.' }
}
