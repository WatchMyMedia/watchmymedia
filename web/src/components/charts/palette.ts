/**
 * Chart colours for the dark surface (bg-surface, #151517). Activity's charts
 * are gold, the app's accent for progress: the brightest step is the most,
 * so the series and the heatmap read by lightness, which colour-blind
 * people see too. Colour follows the entity: each key below keeps its step
 * whatever else is on screen.
 */
export const GOLD = ['#ffb612', '#d99b0b', '#a87700', '#8a6200', '#6b4b00'] as const

export const SERIES_COLOR: Record<string, string> = {
  // Types: episodes the brightest, movies a step darker, unmatched files darker still.
  episode: GOLD[0],
  movie: GOLD[3],
  file: GOLD[4],
  // How it played: the original the brightest, converted video the darkest.
  original: GOLD[0],
  direct: GOLD[0],
  remux: GOLD[0],
  audio: '#b07c00',
  sound: '#b07c00',
  transcode: GOLD[4],
  video: GOLD[4],
}

/** Text on a series' colour: dark on the light steps, white on the darkest. */
export const ON_SERIES: Record<string, string> = { original: '#0f0f10', sound: '#0f0f10', video: '#ffffff' }

/**
 * The heatmap's steps, low to high; the low end still clears 2:1 on the
 * surface. Zero is the neutral empty tile, not a step of the ramp.
 */
export const RAMP = [GOLD[4], GOLD[2], GOLD[1], GOLD[0]] as const
export const EMPTY = '#232326'

/** Chart chrome on the dark surface. */
export const GRID = '#2a2a2e'
export const AXIS = '#3a3a3f'
export const MUTED = '#a3a3a3'
