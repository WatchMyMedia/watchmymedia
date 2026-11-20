// The focus rules that need no page: what a screen remembers, where a row
// starts, and when a run of ◀ stops at a row's first card.

/** What a screen remembers of its focus while it is under another: the last item, and each group's last child. */
export type FocusMemory = { last?: string; groups: Record<string, string> }

export function emptyMemory(): FocusMemory { return { groups: {} } }

/** Notes `id` as focused, and as the last child of each group around it (innermost first). */
export function remember(memory: FocusMemory, id: string, groups: string[]) {
  memory.last = id
  for (const g of groups) memory.groups[g] = id
}

/** A ◀ this soon after the last is part of the same run (Android TV's RowStart.RUN_MS). */
export const RUN_MS = 400

/**
 * Whether a ◀ is part of a run: the key held (repeats), or pressed again
 * within RUN_MS of the last. A run stops at a row's first card instead of
 * going on into the rail; a ◀ pressed by itself from the first card opens
 * the rail, so a row that opens on its first card reaches it in one press.
 */
export class LeftRun {
  private last?: number
  private running = false

  /** A ◀ keydown at `at` ms. */
  press(at: number, repeat: boolean) {
    this.running = repeat || (this.last !== undefined && at - this.last < RUN_MS)
    this.last = at
  }

  /** ◀ let go: the next press starts a run only if it comes quickly. */
  release() { this.running = false }

  /** Any other key ends the run. */
  reset() { this.running = false; this.last = undefined }

  get inRun() { return this.running }
}

/**
 * Which cards a row draws and how far it is moved along. The card before
 * the focused one stays in view (so ◀ always shows where it goes), the row
 * never moves past its last full screen, and only the cards near the view
 * are drawn (`margin` either side), so a long row costs no more than a short one.
 */
export function rowWindow(focus: number, count: number, visible: number, margin = 3) {
  const start = Math.max(0, Math.min(focus - 1, count - visible))
  return { start, from: Math.max(0, start - margin), to: Math.min(count, start + visible + margin) }
}

/**
 * The rows of a grid to draw: those around the focused row, `margin` rows
 * either side of the ones in view, and whether to ask for more (the focus
 * is within `ahead` rows of the end of what has loaded).
 */
export function gridWindow(focus: number, count: number, columns: number, visibleRows: number, margin = 2, ahead = 2) {
  const rows = Math.ceil(count / columns)
  const row = Math.floor(Math.max(0, focus) / columns)
  const first = Math.max(0, Math.min(row - 1, rows - visibleRows))
  return {
    first,
    from: Math.max(0, first - margin) * columns,
    to: Math.min(count, (first + visibleRows + margin) * columns),
    loadMore: row >= rows - 1 - ahead,
  }
}
