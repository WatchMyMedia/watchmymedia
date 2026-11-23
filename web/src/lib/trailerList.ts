import { youTubeTrailers, type MovieTrailer } from './trailers'

/** A trailer in the trailer page's list: a YouTube video it plays in place, or a file the player plays. */
export type TrailerItem =
  | { kind: 'youtube'; id: string; key: string; name: string; index: number; current: boolean }
  | { kind: 'file'; id: string; fileId: number; name: string; durationMs?: number }

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
      items.push({ kind: 'file', id: `file-${t.fileId}`, fileId: t.fileId, name: t.name, durationMs: t.durationMs })
    } else if (t.source === 'youtube' && yt < youtube.length && youtube[yt].key === t.key) {
      const v = youtube[yt]
      items.push({ kind: 'youtube', id: `yt-${v.key}-${yt}`, key: v.key, name: v.name, index: yt, current: v.key === currentKey })
      yt++
    }
  }
  const at = items.findIndex((i) => i.kind === 'youtube' && i.current)
  return { items, position: items.length > 1 && at >= 0 ? `Trailer ${at + 1} of ${items.length}` : undefined }
}
