import type { components } from '../../../src/api/schema'
import { resolutionLabel } from '../../../src/lib/format'
import { bareName } from '../search/crossSearch'
import { leftWords } from '../../ui/timeWords'
import type { ChosenCopy } from '../../session/chosenCopies'

// One card across servers on a title's page: every server's copy of the
// title, grouped by server, which copy Play uses, and where the person got
// to on any of them. Play stays one button; the button after it names the
// copy it will use and opens the version panel. Pure, so the movie page, the
// show page and their tests share one set of rules.

type WatchState = components['schemas']['WatchState']
type MediaFile = Pick<components['schemas']['MediaFileDetail'], 'id' | 'width' | 'height' | 'hdr' | 'durationMs'>

/** A server a copy is on. `current` is the server the app is on; `away` one reached beyond this network. */
export type CopyServer = { id: string; name: string; current: boolean; away: boolean }

/** One server's copy of a title: the title's id there, its versions, and where the person is in it there. */
export type ServerCopy = { server: CopyServer; titleId: number; files: MediaFile[]; watch?: WatchState }

/** A version a copy panel row stands for. */
export type CopyRef = { server: string; file: number }

/** "Home server", "Cabin server": a group's heading, so "Home" doesn't read as the Home screen. */
export function serverHeading(name: string): string {
  return /\bserver$/i.test(name) ? name : `${name} server`
}

/** Said under a server reached away from home. */
export const AWAY_NOTE = 'Away from home, so it may be converted to keep up.'

/** A version's picture in a word or two: "4K HDR", "4K Dolby Vision", "1080p". */
export function copyQuality(f: MediaFile): string {
  const size = resolutionLabel(f.width, f.height)
  const colour = f.hdr === 'dolbyvision' ? 'Dolby Vision' : f.hdr ? 'HDR' : ''
  return [size, colour].filter(Boolean).join(' ') || 'Version'
}

/** Partway: a saved place past the start and short of the end. */
function inProgress(w: WatchState | undefined): boolean {
  return !!w && !w.completed && w.positionMs > 0 && w.durationMs > 0
}

/** Where the person is in a copy, in words: "52 min left", "Watched", "Not started". */
export function placeWords(w: WatchState | undefined): string {
  if (w && inProgress(w)) return leftWords(w.durationMs - w.positionMs)
  return w?.completed ? 'Watched' : 'Not started'
}

/** The copies in the panel's order: the current server first, then Servers' order, a server away from home last. */
export function orderCopies(copies: readonly ServerCopy[], serverOrder: readonly string[]): ServerCopy[] {
  const rank = (c: ServerCopy) => (c.server.current ? -1 : c.server.away ? 1000 + serverOrder.indexOf(c.server.id) : serverOrder.indexOf(c.server.id))
  return [...copies].sort((a, b) => rank(a) - rank(b))
}

/** The latest place on any server: the copy partway through that was played last. */
export function latestPlace(copies: readonly ServerCopy[]): { copy: ServerCopy; watch: WatchState } | undefined {
  let best: { copy: ServerCopy; watch: WatchState } | undefined
  for (const copy of copies) {
    const w = copy.watch
    if (!w || !inProgress(w)) continue
    if (!best || Date.parse(w.updatedAt) > Date.parse(best.watch.updatedAt)) best = { copy, watch: w }
  }
  return best
}

/** A copy's version Play uses by default: the one its place is on, else its first. */
function defaultFile(copy: ServerCopy): MediaFile | undefined {
  return copy.files.find((f) => f.id === copy.watch?.fileId) ?? copy.files[0]
}

/**
 * The copy Play uses: the one chosen for this title on this TV while it is
 * still there; else the copy with the latest place on any server; else the
 * first in the panel's order (the current server's, when it has one).
 */
export function playCopy(copies: readonly ServerCopy[], chosen: ChosenCopy | undefined): { copy: ServerCopy; file: MediaFile } | undefined {
  if (chosen) {
    const copy = copies.find((c) => c.server.id === chosen.server)
    const file = copy?.files.find((f) => f.id === chosen.file)
    if (copy && file) return { copy, file }
  }
  const latest = latestPlace(copies)
  const copy = latest?.copy ?? copies[0]
  const file = copy && defaultFile(copy)
  return copy && file ? { copy, file } : undefined
}

export type CopyRow = { ref: CopyRef; quality: string; place: string; chosen: boolean }
export type CopyGroup = { server: CopyServer; heading: string; note?: string; rows: CopyRow[] }

/** The version panel's groups, one a server in the panel's order, each version with where the person is in it there. */
export function copyGroups(copies: readonly ServerCopy[], uses: CopyRef | undefined): CopyGroup[] {
  return copies.map((c) => ({
    server: c.server,
    heading: serverHeading(c.server.name),
    ...(c.server.away ? { note: AWAY_NOTE } : {}),
    rows: c.files.map((f) => ({
      ref: { server: c.server.id, file: f.id },
      quality: copyQuality(f),
      place: placeWords(c.watch),
      chosen: !!uses && uses.server === c.server.id && uses.file === f.id,
    })),
  }))
}

/** "1:06:12": a place, for the panel's line about Resume. */
export function clockWords(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`
}

/** "1 h 06 min in": how far in a place is, in minutes. */
function inWords(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000))
  return min >= 60 ? `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} min in` : `${min} min in`
}

/**
 * What a title on several servers says on its page. `page` is the server
 * the page's title came from; `copies` holds every server's copy, that one
 * included, in the panel's order.
 */
export type CopiesView = {
  /** Under the facts: "Also on Cabin", "Also on Cabin · you got further there", "On Cabin server · away from home". */
  also?: string
  /** The button after Play, naming the copy it uses: "Home · 4K HDR"; none with one server. */
  button?: string
  /** What Play plays, and from where. */
  play?: { ref: CopyRef; startMs?: number }
  /** Play's label when it resumes another server's copy: "Resume · from Cabin". */
  playLabel?: string
  /** The bar's place when it is another server's: "1 h 06 min in, on Cabin", and how far through. */
  where?: { text: string; progress: number; leftMs: number }
  /** At the panel's top when the latest place is on a server Play no longer uses. */
  resumeNote?: string
  groups: CopyGroup[]
}

export function copiesView(page: CopyServer, copies: readonly ServerCopy[], chosen: ChosenCopy | undefined): CopiesView {
  const uses = playCopy(copies, chosen)
  const latest = latestPlace(copies)
  const ref = uses && { server: uses.copy.server.id, file: uses.file.id }
  const others = copies.filter((c) => c.server.id !== page.id)
  const several = copies.length > 1
  // Resume starts from the latest place on any server, whichever copy plays.
  const startMs = latest && uses && latest.copy.server.id !== uses.copy.server.id ? latest.watch.positionMs : undefined
  const furtherElsewhere = latest && latest.copy.server.id !== page.id ? latest : undefined
  const also = !several
    ? (page.current ? undefined : `On ${serverHeading(page.name)}${page.away ? ' · away from home' : ''}`)
    : `Also on ${others.map((c) => bareName(c.server.name)).join(' and ')}${furtherElsewhere ? ' · you got further there' : ''}`
  return {
    also,
    button: several && uses ? `${bareName(uses.copy.server.name)} · ${copyQuality(uses.file)}` : undefined,
    play: ref && { ref, ...(startMs !== undefined ? { startMs } : {}) },
    playLabel: several && latest && uses && uses.copy.server.id === latest.copy.server.id && latest.copy.server.id !== page.id ? `Resume · from ${bareName(latest.copy.server.name)}` : undefined,
    where: furtherElsewhere && {
      text: `${inWords(furtherElsewhere.watch.positionMs)}, on ${bareName(furtherElsewhere.copy.server.name)}`,
      progress: Math.min(1, furtherElsewhere.watch.positionMs / furtherElsewhere.watch.durationMs),
      leftMs: furtherElsewhere.watch.durationMs - furtherElsewhere.watch.positionMs,
    },
    resumeNote: latest && several && (!uses || uses.copy.server.id !== latest.copy.server.id || latest.copy.server.id !== page.id)
      ? `Resume starts at ${clockWords(latest.watch.positionMs)}, where you got to on ${bareName(latest.copy.server.name)}, whichever you choose.` : undefined,
    groups: several ? copyGroups(copies, ref) : [],
  }
}
