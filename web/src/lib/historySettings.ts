// Settings › Server › Watch history (PLAN-ADMIN-SETTINGS.md §5.9): choices
// that save at once, and the warning before Keep history for is shortened.

export type Option = { value: number; label: string }

function withSaved(base: Option[], saved: number, label: (n: number) => string): Option[] {
  return base.some((o) => o.value === saved) ? base : [...base, { value: saved, label: label(saved) }].sort((a, b) => a.value - b.value)
}

/** Keep plays longer than, in seconds; a saved value that is none of these is kept as a choice. */
export function minPlayOptions(saved: number): Option[] {
  const base = [
    { value: 0, label: 'Every play' },
    { value: 30, label: '30 seconds' },
    { value: 60, label: '1 minute' },
    { value: 120, label: '2 minutes' },
    { value: 300, label: '5 minutes' },
    { value: 600, label: '10 minutes' },
  ]
  return withSaved(base, saved, (n) => `${n} seconds`)
}

/** Keep history for, in days; 0 keeps it forever. */
export function retentionOptions(saved: number): Option[] {
  const base = [
    { value: 0, label: 'Forever' },
    { value: 30, label: '30 days' },
    { value: 90, label: '90 days' },
    { value: 182, label: '6 months' },
    { value: 365, label: '1 year' },
    { value: 730, label: '2 years' },
    { value: 1825, label: '5 years' },
  ]
  return withSaved(base, saved, (n) => `${n} days`)
}

export function retentionLabel(days: number): string {
  return retentionOptions(days).find((o) => o.value === days)?.label ?? `${days} days`
}

/** A new limit that removes plays: from forever to any limit, or to a shorter one. */
export function isShortening(from: number, to: number): boolean {
  return to > 0 && (from === 0 || to < from)
}

/** "a year", "6 months", "90 days", for "older than …". */
function span(days: number): string {
  const label = retentionLabel(days)
  return label === '1 year' ? 'a year' : label
}

/** The warning before a shorter limit saves, with the count the server gives (undefined when it couldn't). */
export function shorteningWarning(to: number, plays: number | undefined): string {
  const choosing = `Choosing ${retentionLabel(to)}`
  if (plays === undefined) return `${choosing} removes the plays older than ${span(to)}. This can’t be undone.`
  return `${choosing} removes about ${plays.toLocaleString('en-US')} ${plays === 1 ? 'play' : 'plays'} older than that. This can’t be undone.`
}

/** The Keep button: what stays as it is. */
export function keepLabel(from: number): string {
  return from === 0 ? 'Keep forever' : `Keep ${retentionLabel(from)}`
}

/** What a saved Keep history for does. */
export function retentionSaved(days: number): string {
  return days === 0 ? 'Plays are kept forever.' : `Plays older than ${span(days)} are removed, starting within 6 hours.`
}

/** The Keep history for row's line while nothing is being saved. */
export function retentionLine(days: number): string {
  return days === 0 ? 'Every play is kept.' : `Plays older than ${span(days)} are removed.`
}
