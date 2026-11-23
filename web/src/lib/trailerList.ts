import { youTubeTrailers, type MovieTrailer } from './trailers'

/** A trailer in the trailer page's list: a YouTube video it plays in place, or a file the player plays. */
export type TrailerItem =
  | { kind: 'youtube'; id: string; key: string; name: string; index: number; current: boolean; what: string }
  | { kind: 'file'; id: string; fileId: number; name: string; durationMs?: number; what: string }

/** "Teaser" or "Trailer", from the server's kind (API level 34); nothing for a kind it may add later. */
export function trailerKindWords(kind: string | undefined): string {
  if (kind === 'teaser') return 'Teaser'
  if (kind === 'trailer') return 'Trailer'
  return ''
}

/** A listed trailer's second line: "Playing now", "Teaser · From YouTube", "Trailer · In your library". */
export function trailerLine(t: TrailerItem): string {
  if (t.kind === 'youtube' && t.current) return 'Playing now'
  return [t.what, t.kind === 'youtube' ? 'From YouTube' : 'In your library'].filter(Boolean).join(' · ')
}

/**
 * The movie's trailers beside the player, in the server's order: its
 * YouTube trailers (index is the one the page plays) and its trailer files;
 * and "Trailer 1 of 3" for the one playing, when there is more than one.
 */
export function otherTrailers(trailers: MovieTrailer[] | undefined, currentKey: string | undefined): { items: TrailerItem[]; position?: string } {
  const youtube = youTubeTrailers(trailers)
  const items: TrailerItem[] = []
  let yt = 0
  for (const t of trailers ?? []) {
    if (t.source === 'file' && t.fileId && t.fileId > 0) {
      items.push({ kind: 'file', id: `file-${t.fileId}`, fileId: t.fileId, name: t.name, durationMs: t.durationMs, what: trailerKindWords(t.kind) })
    } else if (t.source === 'youtube' && yt < youtube.length && youtube[yt].key === t.key) {
      const v = youtube[yt]
      items.push({ kind: 'youtube', id: `yt-${v.key}-${yt}`, key: v.key, name: v.name, index: yt, current: v.key === currentKey, what: trailerKindWords(t.kind) })
      yt++
    }
  }
  const at = items.findIndex((i) => i.kind === 'youtube' && i.current)
  return { items, position: items.length > 1 && at >= 0 ? `Trailer ${at + 1} of ${items.length}` : undefined }
}
