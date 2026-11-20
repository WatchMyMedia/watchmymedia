import type { components } from '../../../src/api/schema'
import { formatBytes, plural, relativeTime } from '../../../src/lib/format'
import { drawable, homeRows, titleParts, type ScreenRow } from '../../../src/lib/screenRows'

// Home one row a screen (HomeStart and HomeRow on the Samsung TV canvas,
// Android TV's HomePages.kt): which screens Home has, which one a focus id
// is on, and the words along their bottom edge.

export type HomeData = components['schemas']['HomeData']

/**
 * Whether Home draws a row: what the web draws, and Live TV's row when this
 * TV has channels to show in it (the web skips that one).
 */
export function homeDrawable(row: ScreenRow, live: boolean): boolean {
  return row.style === 'live' ? live : drawable(row)
}

/** Home's rows in the person's order, those this TV draws. */
export function homeScreenRows(home: HomeData, live: boolean): ScreenRow[] {
  return homeRows(home).filter((r) => homeDrawable(r, live))
}

/** Nothing to show: no hero and no row with something in it, Live TV's included. */
export function homeIsEmpty(home: HomeData, rows: readonly ScreenRow[]): boolean {
  return !home.hero && rows.length === 0
}

export type HomePage =
  /** The hero over the first row drawn (none: a hero alone). */
  | { kind: 'start'; key: 'start'; row?: ScreenRow }
  /** One row under the panel of its focused card. */
  | { kind: 'row'; key: string; row: ScreenRow }
  /** Edit rows and the library's totals. */
  | { kind: 'end'; key: 'end' }

/** The first screen is the hero and the first row; every row after it fills a screen; the last is Edit rows. Without a hero every row is a row screen. */
export function homePages(hero: boolean, rows: readonly ScreenRow[]): HomePage[] {
  const pages: HomePage[] = []
  if (hero) pages.push({ kind: 'start', key: 'start', row: rows[0] })
  for (const row of hero ? rows.slice(1) : rows) pages.push({ kind: 'row', key: `row-${row.id}`, row })
  pages.push({ kind: 'end', key: 'end' })
  return pages
}

/** The focus group of a row's cards: its cards' ids are `<group>/<card key>`. */
export function rowGroup(row: Pick<ScreenRow, 'id'>): string {
  return `row/${row.id}`
}

export const HERO_PLAY = 'home/hero/play'
export const HERO_OPEN = 'home/hero/open'
export const END_EDIT = 'home/end/edit'
export const END_TOP = 'home/end/top'

/** The page a focus id is on, by key: a row's card, the hero's buttons, or the last page's. */
export function pageOfFocus(pages: readonly HomePage[], id: string | undefined): string | undefined {
  if (!id) return undefined
  if (id === HERO_PLAY || id === HERO_OPEN) return pages.find((p) => p.kind === 'start')?.key
  if (id === END_EDIT || id === END_TOP) return 'end'
  return pages.find((p) => p.kind !== 'end' && p.row && id.startsWith(`${rowGroup(p.row)}/`))?.key
}

/** "Recently added · TV Shows", "Live TV · On now": a row's name along the bottom edge of the screen before it. */
export function headingLine(row: ScreenRow, listings: boolean): string {
  if (row.style === 'live') return listings ? 'Live TV · On now' : 'Live TV'
  const [title, small] = titleParts(row)
  return small ? `${title} · ${small}` : title
}

/** What the bottom edge of a page names: the next row (▼), or on the last page the row before it (▲). */
export function peekLine(pages: readonly HomePage[], index: number, listings: boolean): { text: string; up: boolean } {
  const page = pages[index]
  if (page?.kind === 'end') {
    const before = pages[index - 1]
    return { text: before && before.kind !== 'end' && before.row ? headingLine(before.row, listings) : 'Home', up: true }
  }
  const next = pages[index + 1]
  return { text: next?.kind === 'row' ? headingLine(next.row, listings) : 'End of Home', up: false }
}

/** "Row 2 of 7". */
export function rowCounter(rows: readonly ScreenRow[], row: ScreenRow): string {
  return `Row ${rows.indexOf(row) + 1} of ${rows.length}`
}

/** "214 movies · 142 shows · 3,418 episodes · 18.4 TB · last scan 12 min ago". */
export function totalsLine(home: Pick<HomeData, 'totals' | 'lastScannedAt'>): string {
  const t = home.totals
  return [
    plural(t.movies, 'movie'), plural(t.shows, 'show'), `${t.episodes.toLocaleString()} ${t.episodes === 1 ? 'episode' : 'episodes'}`,
    formatBytes(t.totalSize), home.lastScannedAt ? `last scan ${relativeTime(home.lastScannedAt)}` : '',
  ].filter(Boolean).join(' · ')
}

/** Which page ▲ or ▼ goes to from `index`, or none: the first page is the top, the last the bottom. */
export function pageStep(count: number, index: number, direction: 'up' | 'down'): number | undefined {
  const next = index + (direction === 'down' ? 1 : -1)
  return next >= 0 && next < count ? next : undefined
}
