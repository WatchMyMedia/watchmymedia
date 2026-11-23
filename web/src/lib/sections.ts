/** The sections the app bar links to, in its order. */
export type Section = 'home' | 'movies' | 'tv' | 'live' | 'activity'

/** The section a path belongs to, for the underline in the bar and the gold in the foot bar. */
export function sectionOf(path: string): Section | undefined {
  if (path === '/') return 'home'
  if (path.startsWith('/movies')) return 'movies'
  if (path.startsWith('/tv') || path.startsWith('/shows')) return 'tv'
  if (path.startsWith('/live-tv')) return 'live'
  if (path.startsWith('/activity')) return 'activity'
  return undefined
}
