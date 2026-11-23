import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { timeOfDay } from '../../../src/lib/liveGuide'
import type { OnNowCard } from './liveWords'

// The Live TV player's words that decide something: what the banner says
// next, what holds the tuners when they are all busy, when a recording
// takes this one, and the order of On now. Pure, so they are tested here.

const ms = (iso: string) => Date.parse(iso)
const SEP = ' · '

/** "Next at 9:00 PM · Station 9", or nothing when the listings don't say. */
export function nextWords(next: Pick<LiveProgramme, 'start' | 'title'> | undefined, locale?: string): string | undefined {
  if (!next) return undefined
  return `Next at ${timeOfDay(ms(next.start), locale)}${SEP}${next.title}`
}

/** On now's line under a channel: "48 min left · then Station 9", "48 min left", or "No listings". */
export function onNowLine(card: Pick<OnNowCard, 'programme' | 'line'>, next?: Pick<LiveProgramme, 'title'>): string {
  if (!card.programme) return 'No listings'
  return next ? `${card.line}${SEP}then ${next.title}` : card.line
}

/** On now's order: favourites first, each group in the server's order. */
export function favouritesFirst<T extends { channel: Pick<LiveChannel, 'favourite'> }>(cards: readonly T[]): { cards: T[]; favourites: boolean } {
  const fav = cards.filter((c) => c.channel.favourite)
  return { cards: [...fav, ...cards.filter((c) => !c.channel.favourite)], favourites: fav.length > 0 && fav.length < cards.length }
}

/** Where the picture is against the broadcast, as the red mark or grey words say it. */
export function liveBadge(o: { tuning: boolean; paused: boolean; behind: boolean }): { words: string; live: boolean } {
  if (o.tuning) return { words: 'Tuning', live: false }
  if (o.paused) return { words: 'Paused', live: false }
  if (o.behind) return { words: 'Behind live', live: false }
  return { words: 'Live', live: true }
}

/** The line under the controls: what the keys do with the controls hidden. */
export const CONTROLS_NOTE = 'With these hidden, Up and Down or the channel keys change channel, and the number keys open Go to channel.'

/** What a press on Go live says while the picture is already live. */
export const AT_LIVE_NOTE = 'You’re watching live already.'

/**
 * A recording needs this tuner soon: when, in bold, then what happens.
 * "At 9:00 PM a recording needs this tuner." "Watching stops then."
 */
export function takeoverLine(at: string | undefined, now: number, locale?: string): { strong: string; soft: string } {
  const when = at ? ms(at) : NaN
  const strong = Number.isFinite(when) && when > now ? `At ${timeOfDay(when, locale)} a recording needs this tuner.` : 'A recording needs this tuner now.'
  return { strong, soft: 'Watching stops then.' }
}

type Holder = { id: string; title: string; end: string }

/**
 * Every tuner is busy: what is recording on them and until when, when one
 * is free, and the first recording to offer from its start. Undefined when
 * nothing is recording (then someone else is watching, and the problem's
 * own words say so).
 */
export function busyWords(recording: readonly Holder[], tuners: number | undefined, locale?: string): { why: string; what: string; watch: Holder } | undefined {
  const holders = [...recording].sort((a, b) => ms(b.end) - ms(a.end))
  if (!holders.length) return undefined
  const until = (h: Holder) => `${h.title} until ${timeOfDay(ms(h.end), locale)}`
  const list = holders.length === 1 ? until(holders[0]) : `${holders.slice(0, -1).map(until).join(', ')}, and ${until(holders[holders.length - 1])}`
  const all = tuners !== undefined && holders.length >= tuners
  const lead = holders.length === 1 ? (all ? 'It is recording' : 'One is recording') : all ? (holders.length === 2 ? 'Both are recording' : `All ${holders.length} are recording`) : `${holders.length} are recording`
  const first = holders.reduce((a, b) => (ms(a.end) <= ms(b.end) ? a : b))
  const free = all ? `One is free at ${timeOfDay(ms(first.end), locale)}. ` : ''
  return {
    why: `${lead}: ${list}.`,
    what: `${free}Until then you can watch what is recording, from its start. Nothing is taken from another viewer or a recording.`,
    watch: holders[0],
  }
}
