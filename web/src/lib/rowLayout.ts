import type { components } from '../api/schema'

// Edit rows (PLAN-ROWS.md §5): the order a person gives a screen's rows and
// which are hidden, kept as a draft until Done saves it.

export type RowLayoutEntry = components['schemas']['RowLayoutEntry']
export type RowPlacement = components['schemas']['RowPlacement']

/** Swaps a row with the one above (by -1) or below (by 1); the list as it was at either end. */
export function moveRow<T>(rows: readonly T[], index: number, by: -1 | 1): T[] {
  const other = index + by
  if (index < 0 || index >= rows.length || other < 0 || other >= rows.length) return [...rows]
  const next = [...rows]
  ;[next[index], next[other]] = [next[other], next[index]]
  return next
}

/** Takes the row at from out and puts it back at to, counted in the list without it: what a drag ends with. */
export function moveRowTo<T>(rows: readonly T[], from: number, to: number): T[] {
  if (from < 0 || from >= rows.length) return [...rows]
  const next = [...rows]
  const [row] = next.splice(from, 1)
  next.splice(Math.max(0, Math.min(to, next.length)), 0, row)
  return next
}

export type Span = { top: number; height: number }

/**
 * Where a dragged row lands: after every other row whose middle is above the
 * dragged row's middle (centre), counted in the list without the dragged row.
 */
export function dropIndex(centre: number, spans: readonly Span[], from: number): number {
  let to = 0
  spans.forEach((s, i) => {
    if (i !== from && s.top + s.height / 2 < centre) to++
  })
  return to
}

/** How far the rows move aside for a dragged one: its height and the gap after it. */
export function dragStride(spans: readonly Span[], from: number): number {
  const s = spans[from]
  if (!s) return 0
  const next = spans[from + 1]
  if (next) return next.top - s.top
  const prev = spans[from - 1]
  return prev ? s.top - prev.top + (s.height - prev.height) : s.height
}

/** The offset of a row that is not being dragged, while the row from is over place to. */
export function dragShift(index: number, from: number, to: number, stride: number): number {
  if (index === from) return 0
  if (from < to && index > from && index <= to) return -stride
  if (to < from && index >= to && index < from) return stride
  return 0
}

/** Hides a row, or shows it again, in place. */
export function setHidden(rows: readonly RowLayoutEntry[], id: string, hidden: boolean): RowLayoutEntry[] {
  return rows.map((r) => (r.id === id ? { ...r, hidden } : r))
}

/** Add a row: the row goes to the top of the screen, shown, as a new list's row does; the person moves it from there. */
export function addRow(rows: readonly RowLayoutEntry[], id: string): RowLayoutEntry[] {
  const row = rows.find((r) => r.id === id)
  if (!row) return [...rows]
  return [{ ...row, hidden: false }, ...rows.filter((r) => r.id !== id)]
}

/** What Add a row offers: the rows the layout lists as hidden, including lists made for other screens. */
export function addable(rows: readonly RowLayoutEntry[]): RowLayoutEntry[] {
  return rows.filter((r) => r.hidden)
}

/** The body updateRowLayout takes: every row in order, so hidden ones stay hidden. */
export function placements(rows: readonly RowLayoutEntry[]): RowPlacement[] {
  return rows.map((r) => ({ id: r.id, hidden: r.hidden }))
}

/** Whether two layouts put the same rows in the same order with the same ones hidden. */
export function samePlacement(a: readonly RowLayoutEntry[], b: readonly RowLayoutEntry[]): boolean {
  return a.length === b.length && a.every((r, i) => r.id === b[i].id && r.hidden === b[i].hidden)
}

/**
 * Brings the server's latest layout into a draft being edited, as the
 * server merges a saved order: the draft keeps its order and its hidden
 * choices; rows gone from the layout (a deleted list) leave; a row new to
 * it (a list just made) goes after the nearest row before it in the latest
 * layout, or first when none is.
 */
export function mergeDraft(draft: readonly RowLayoutEntry[], latest: readonly RowLayoutEntry[]): RowLayoutEntry[] {
  const fresh = new Map(latest.map((r) => [r.id, r]))
  const out = draft.flatMap((r) => {
    const now = fresh.get(r.id)
    return now ? [{ ...now, hidden: r.hidden }] : []
  })
  latest.forEach((r, i) => {
    if (out.some((o) => o.id === r.id)) return
    let at = 0
    for (let j = i - 1; j >= 0; j--) {
      const k = out.findIndex((o) => o.id === latest[j].id)
      if (k >= 0) {
        at = k + 1
        break
      }
    }
    out.splice(at, 0, r)
  })
  return out
}

/** "Continue watching, position 2 of 6", for the editor's live region. */
export function movedWords(title: string, index: number, count: number): string {
  return `${title}, position ${index + 1} of ${count}`
}

/** The tag on a list's row in the editor and Add a row: whose list it is. */
export function ownerLabel(owner: string | undefined): string {
  if (owner === 'me') return 'Your list'
  if (owner === 'everyone') return 'Household list'
  return 'List'
}

/** The most rows a screen draws (PLAN-ROWS.md Decision 13). */
export const MAX_ROWS = 30

export type RowView = { note?: string; over: boolean; divider: boolean }
export type LayoutView = { rows: RowView[]; total: number; shown: number; hidden: number; over: number }

/**
 * What the editor says about each row as the draft stands: a row with
 * nothing to show keeps the server's note; rows past the 30th that would
 * draw are "Not shown: too many rows", with a line above the first. A row
 * counts as empty when the saved layout says it isn't shown though it isn't
 * hidden and comes before 30 shown rows; a row the person just showed again
 * counts as one that draws, since only saving tells.
 */
export function layoutView(rows: readonly RowLayoutEntry[], saved: readonly RowLayoutEntry[], max = MAX_ROWS): LayoutView {
  const empty = new Set<string>()
  let before = 0
  for (const r of saved) {
    if (r.hidden) continue
    if (r.shown) before++
    else if (before < max) empty.add(r.id)
  }
  let drawn = 0
  let cut = false
  const views = rows.map((r): RowView => {
    if (r.hidden) return { over: false, divider: false }
    if (empty.has(r.id)) return { note: r.note || 'Nothing to show yet', over: false, divider: false }
    drawn++
    if (drawn <= max) return { over: false, divider: false }
    const divider = !cut
    cut = true
    return { note: 'Not shown: too many rows', over: true, divider }
  })
  return { rows: views, total: rows.length, shown: Math.min(drawn, max), hidden: rows.filter((r) => r.hidden).length, over: Math.max(0, drawn - max) }
}

/** "10 rows · 7 shown · 2 hidden". */
export function countWords(v: Pick<LayoutView, 'total' | 'shown' | 'hidden'>): string {
  return `${v.total} ${v.total === 1 ? 'row' : 'rows'} · ${v.shown} shown · ${v.hidden} hidden`
}

/** "The last 3 rows aren’t shown." */
export function overWords(over: number): string {
  return over === 1 ? 'The last row isn’t shown.' : `The last ${over} rows aren’t shown.`
}

/** "Edit Home’s rows", "Edit Movies’ rows". */
export function editTitle(screenName: string): string {
  return `Edit ${screenName}${screenName.endsWith('s') ? '’' : '’s'} rows`
}
