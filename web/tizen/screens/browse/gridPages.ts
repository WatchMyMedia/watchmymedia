// A library's grid as pages fetched around where the remote is (Android
// TV's LibraryViewModel windows, kept simpler): the grid is as long as the
// whole result, the pages near focus are asked for, and a title not loaded
// yet is a placeholder in its place. The A–Z strip jumps by the offsets the
// server sends with a title sort; CH ▲ ▼ move two rows.

/** Eight posters across. */
export const COLUMNS = 8
/** Six rows a page. */
export const PAGE_SIZE = COLUMNS * 6

/**
 * The pages to have for focus at `index`: those holding the rows from
 * `rowsAround` above it to `rowsAround` below it, so the rows the grid draws
 * (and the next screen's) are in before focus gets there.
 */
export function pagesAround(index: number, total: number, pageSize = PAGE_SIZE, columns = COLUMNS, rowsAround = 4): number[] {
  if (total <= 0) return [0]
  const from = Math.max(0, index - rowsAround * columns)
  const to = Math.min(total - 1, index + (rowsAround + 1) * columns - 1)
  const pages: number[] = []
  for (let p = Math.floor(from / pageSize); p <= Math.floor(to / pageSize); p++) pages.push(p)
  return pages
}

/** The whole result as slots: each loaded page's items at their offsets, undefined where nothing has arrived. */
export function slots<T>(total: number, pages: ReadonlyMap<number, readonly T[]>, pageSize = PAGE_SIZE): (T | undefined)[] {
  const out: (T | undefined)[] = new Array(Math.max(0, total))
  for (const [page, items] of pages) {
    const start = page * pageSize
    for (let i = 0; i < items.length && start + i < total; i++) out[start + i] = items[i]
  }
  return out
}

/** CH ▲ ▼: two rows up or down in the same column, stopping at the first and last titles. */
export function stepRows(index: number, total: number, rows: number, columns = COLUMNS): number {
  if (total <= 0) return 0
  return Math.max(0, Math.min(total - 1, index + rows * columns))
}

/** Whether ▶ from `index` goes to the A–Z strip: from the last column, or the last title. */
export function toStrip(index: number, total: number, columns = COLUMNS): boolean {
  return index % columns === columns - 1 || index === total - 1
}

/** The strip's letters, as the server indexes titles: digits and the rest under #, then A to Z. */
export const ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ']

export type LetterOffset = { letter: string; offset: number }

/** How many titles start with each letter, from where each letter's first title sits in the whole result. */
export function letterCounts(letters: readonly LetterOffset[], total: number): Map<string, number> {
  const sorted = [...letters].sort((a, b) => a.offset - b.offset)
  return new Map(sorted.map((l, i) => [l.letter, (sorted[i + 1]?.offset ?? total) - l.offset]))
}

/** The letter the title at `index` is under. */
export function letterAt(letters: readonly LetterOffset[], index: number): string | undefined {
  let best: LetterOffset | undefined
  for (const l of letters) if (l.offset <= index && (!best || l.offset > best.offset)) best = l
  return best?.letter
}

/** The letter above (-1) or below (1) `from` that has titles, skipping empty ones; none at either end. */
export function nextLetter(from: string, counts: ReadonlyMap<string, number>, step: -1 | 1): string | undefined {
  for (let i = ALPHABET.indexOf(from) + step; i >= 0 && i < ALPHABET.length; i += step) {
    if ((counts.get(ALPHABET[i]) ?? 0) > 0) return ALPHABET[i]
  }
  return undefined
}

/** Where focus comes onto the strip: the focused title's letter when it has titles, else the first that does. */
export function stripEntry(letters: readonly LetterOffset[], index: number): string | undefined {
  return letterAt(letters, index) ?? [...letters].sort((a, b) => a.offset - b.offset)[0]?.letter
}

/** "Jump to M, 4 shows", "Q, no shows": a letter's name for a screen reader. */
export function letterLabel(letter: string, count: number, noun: string): string {
  const name = letter === '#' ? 'numbers' : letter
  return count > 0 ? `Jump to ${name}, ${count} ${count === 1 ? noun : `${noun}s`}` : `${letter}, no ${noun}s`
}
