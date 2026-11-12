import type { components } from '../api/schema'
import { formatBytes } from './format'

export type TrickplayStatus = components['schemas']['TrickplayStatus']
type Current = components['schemas']['TrickplayCurrent']

/** What the Seek thumbnails card shows (the SettingsThumbnails board's states). */
export type ThumbnailState = 'working' | 'paused' | 'lowDisk' | 'done' | 'off'

/** Where the job stands: off; waiting for a play to end or for disk space; every file done; else making them. */
export function thumbnailState(s: TrickplayStatus): ThumbnailState {
  if (!s.enabled) return 'off'
  if (s.paused === 'lowDisk') return 'lowDisk'
  if (s.paused === 'playing') return 'paused'
  if (s.pending === 0) return 'done'
  return 'working'
}

/** The status pill's words and look, and the line under the bar. */
export function thumbnailCopy(state: ThumbnailState, s: TrickplayStatus): { pill: string; tone: 'ok' | 'warn' | 'off'; note: string } {
  switch (state) {
    case 'working':
      return { pill: 'Making thumbnails', tone: 'ok', note: '' }
    case 'paused':
      return { pill: 'Paused while someone is watching', tone: 'off', note: 'It goes on 2 minutes after the last play stops.' }
    case 'lowDisk':
      return {
        pill: 'Paused: under 5 GB free',
        tone: 'warn',
        note: `The disk that holds the data folder has ${formatBytes(s.freeBytes ?? 0)} free. It goes on when there is 5 GB.`,
      }
    case 'done':
      return { pill: 'Done', tone: 'ok', note: 'New and changed files get thumbnails after each scan.' }
    case 'off':
      return { pill: 'Off', tone: 'off', note: 'No new thumbnails are made. The ones already made are kept, and the apps still show them.' }
  }
}

/** "7,912 of 11,284 files". */
export function filesLine(s: TrickplayStatus): string {
  return `${s.done.toLocaleString('en-US')} of ${s.files.toLocaleString('en-US')} files`
}

/** How far along, in percent to one decimal. */
export function percentDone(s: TrickplayStatus): number {
  return s.files > 0 ? Math.round((s.done / s.files) * 1000) / 10 : 0
}

/** The file being made: "Harbor Lights · S03 E07 · The Long Dark", or a movie's title. */
export function currentTitle(c: Current): string {
  if (!c.showTitle) return c.title
  const se = c.seasonNumber != null && c.episodeNumber != null ? `S${String(c.seasonNumber).padStart(2, '0')} E${String(c.episodeNumber).padStart(2, '0')}` : ''
  return [c.showTitle, se, c.title].filter(Boolean).join(' · ')
}

/** How often to ask again: every few seconds while there is work, else not at all. */
export function pollInterval(s: TrickplayStatus | undefined): number | false {
  if (!s) return false
  return s.running || (s.enabled && s.pending > 0) ? 5000 : false
}

/** The Delete thumbnails row's hint. */
export function deleteHint(enabled: boolean): string {
  return enabled ? 'Frees the space now. With seek thumbnails on, they are made again, newest first.' : 'Frees the space. Nothing is made again while seek thumbnails are off.'
}

// ---------------------------------------------------------------- seek thumbnails in the player

export type Trickplay = components['schemas']['Trickplay']

/** One seek thumbnail: tile `index`, in sheet `sheet` at `column` and `row`. */
export type Tile = { index: number; sheet: number; column: number; row: number }

const SHEET = '{sheet}'

/**
 * The decision's manifest, or null when the player can't draw from it: a
 * size of 0, no `{sheet}` in the URL, more tiles than the sheets hold.
 */
export function usableTrickplay(t: Trickplay | null | undefined): Trickplay | null {
  if (!t || t.intervalMs <= 0 || !t.url.includes(SHEET)) return null
  if ([t.width, t.height, t.columns, t.rows, t.count, t.sheets].some((n) => !Number.isInteger(n) || n < 1)) return null
  return t.columns * t.rows * t.sheets >= t.count ? t : null
}

/**
 * The tile at `positionMs` (PLAN-TRICKPLAY.md §2): tile i shows the last
 * keyframe at or before i × intervalMs, so the one at or before the
 * position, and the last tile past the end.
 */
export function tileAt(t: Trickplay, positionMs: number): Tile {
  const index = Math.min(Math.max(Math.floor(positionMs / t.intervalMs), 0), t.count - 1)
  const perSheet = t.columns * t.rows
  const cell = index % perSheet
  return { index, sheet: Math.floor(index / perSheet), column: cell % t.columns, row: Math.floor(cell / t.columns) }
}

export function sheetUrl(t: Trickplay, sheet: number): string {
  return t.url.replace(SHEET, String(sheet))
}

/**
 * CSS that shows `tile` in a box of the tile's shape at any size: the sheet
 * scaled to columns × rows boxes, and moved by percentages, which CSS takes
 * as fractions of what the sheet overhangs the box.
 */
export function tileBackground(t: Trickplay, tile: Tile): { backgroundImage: string; backgroundSize: string; backgroundPosition: string } {
  const at = (i: number, n: number) => (n > 1 ? (i / (n - 1)) * 100 : 0)
  return {
    backgroundImage: `url("${sheetUrl(t, tile.sheet)}")`,
    backgroundSize: `${t.columns * 100}% ${t.rows * 100}%`,
    backgroundPosition: `${at(tile.column, t.columns)}% ${at(tile.row, t.rows)}%`,
  }
}

/**
 * Where a preview `width` wide goes over a track `trackWidth` wide: centred
 * on `x`, and kept within the track.
 */
export function previewLeft(x: number, width: number, trackWidth: number): number {
  return Math.round(Math.min(Math.max(x - width / 2, 0), Math.max(trackWidth - width, 0)))
}

type SheetState = 'loading' | 'loaded' | 'failed'

/**
 * Fetches a manifest's sheets for the scrubber, one request per sheet at a
 * time, and says which have come. The browser caches them for good (their
 * URLs name the version). A sheet that failed is not asked for again until
 * the next scrub, so a scrub doesn't retry it at every tile.
 */
export class SheetLoader {
  private readonly sheets = new Map<number, SheetState>()

  constructor(
    private readonly trickplay: Trickplay,
    private readonly fetchSheet: (url: string) => Promise<void>,
    private readonly onLoad: () => void,
  ) {}

  loaded(sheet: number): boolean {
    return this.sheets.get(sheet) === 'loaded'
  }

  /** Fetches the sheet unless it has come, is coming or failed since the last scrub. */
  want(sheet: number): void {
    if (sheet < 0 || sheet >= this.trickplay.sheets || this.sheets.has(sheet)) return
    this.sheets.set(sheet, 'loading')
    this.fetchSheet(sheetUrl(this.trickplay, sheet)).then(
      () => {
        this.sheets.set(sheet, 'loaded')
        this.onLoad()
      },
      () => this.sheets.set(sheet, 'failed'),
    )
  }

  /** A scrub may start at `tile`: fetch its sheet and the sheets either side, and give failed ones another go. */
  scrubFrom(tile: Tile): void {
    for (const [sheet, state] of this.sheets) if (state === 'failed') this.sheets.delete(sheet)
    for (const sheet of [tile.sheet, tile.sheet + 1, tile.sheet - 1]) this.want(sheet)
  }
}

/** Fetches an image and waits for it to decode, so a background of the same URL draws at once. */
export function fetchImage(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve()
    img.onerror = () => reject(new Error(`could not load ${url}`))
    img.src = url
  })
}
