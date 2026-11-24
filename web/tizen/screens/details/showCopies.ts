import type { components } from '../../../src/api/schema'
import { bareName } from '../search/crossSearch'
import type { CopyServer, ServerCopy } from './copies'

// A show on several servers, on its page: the season's episodes from every
// server, matched by number (the show matched by its TMDB id), each with its
// copies. An episode only another server has a file of is listed in its
// place, from that server, with "On Cabin". Pure, so it is tested.

type Episode = components['schemas']['Episode']

/** An episode on the page: the page's own, or another server's (`on`) when only that one has its file. */
export type MergedEpisode = Episode & { on?: CopyServer }

/** One server's season, as asked from that server. */
export type ServerSeason = { server: CopyServer; episodes: Episode[] }

/** The id an episode only another server has goes by on this page: never one of the page's own (they are positive). */
export function elsewhereId(season: number, number: number): number {
  return -(season * 10_000 + number)
}

/**
 * The season's episodes with every server's copies. Each number the page or
 * another server lists is one episode: the page's own when it has the file;
 * else the first other server's that has one (in `others`' order), under
 * the page's id for it or one of its own; else the page's, as it is.
 */
export function mergeSeason(season: number, page: ServerSeason, others: readonly ServerSeason[]): { episodes: MergedEpisode[]; copies: Map<number, ServerCopy[]> } {
  const numbers = new Set<number>([...page.episodes, ...others.flatMap((o) => o.episodes)].map((e) => e.number))
  const episodes: MergedEpisode[] = []
  const copies = new Map<number, ServerCopy[]>()
  for (const n of [...numbers].sort((a, b) => a - b)) {
    const own = page.episodes.find((e) => e.number === n)
    const withFile = [page, ...others].flatMap((s) => {
      const e = s.episodes.find((x) => x.number === n)
      return e?.hasFile ? [{ server: s.server, e }] : []
    })
    const copyList: ServerCopy[] = withFile.map(({ server, e }) => ({ server, titleId: e.id, files: e.files, watch: e.watch }))
    if (own?.hasFile) {
      episodes.push(own)
      copies.set(own.id, copyList)
    } else if (withFile.length) {
      const first = withFile[0]
      const id = own?.id ?? elsewhereId(season, n)
      episodes.push({ ...first.e, id, on: first.server })
      copies.set(id, copyList)
    } else if (own) {
      episodes.push(own)
      copies.set(own.id, [])
    }
  }
  return { episodes, copies }
}

/** An episode card's line when it is only on another server: "On Cabin · 46 min". */
export function elsewhereLine(e: Pick<MergedEpisode, 'on' | 'runtimeMin'>): string | undefined {
  if (!e.on) return undefined
  return [`On ${bareName(e.on.name)}`, e.runtimeMin ? `${e.runtimeMin} min` : ''].filter(Boolean).join(' · ')
}
