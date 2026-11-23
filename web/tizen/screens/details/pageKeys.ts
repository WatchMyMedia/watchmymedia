// The title pages' own ◀ ▶ along a line of buttons: one to the next in
// order, stopping at both ends, so ◀ from the first never lands on what is
// below it (the cast, a card) and there is no menu to open.

/** The button after (or before) `current` in a line, or none at its ends. */
export function stepAlong<T>(line: readonly T[], current: T, direction: 'left' | 'right'): T | undefined {
  const i = line.indexOf(current)
  return i < 0 ? undefined : line[i + (direction === 'left' ? -1 : 1)]
}

/** Moves focus along the `.dt-actions` line the focused button is in; false when it isn't in one. */
export function alongButtons(active: HTMLElement | null, direction: 'left' | 'right', focus: (el: HTMLElement) => void): boolean {
  const line = active?.closest<HTMLElement>('.dt-actions')
  if (!active || !line) return false
  const next = stepAlong([...line.querySelectorAll<HTMLElement>('[data-focus]')], active, direction)
  if (next) focus(next)
  return true
}
