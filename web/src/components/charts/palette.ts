/**
 * Chart colours for the dark surface (bg-surface, #151517). The first three
 * categorical slots of the reference palette's dark steps, in its fixed
 * order; validated all-pairs against #151517 (worst CVD ΔE 9.4, normal-vision
 * 20.9, every slot >= 3:1). Charts here never need more than three series.
 * Colour follows the entity: each key below keeps its slot whatever else is
 * on screen.
 */
export const SERIES = ['#3987e5', '#d95926', '#199e70'] as const

export const SERIES_COLOR: Record<string, string> = {
  // Types
  episode: SERIES[0],
  movie: SERIES[1],
  file: SERIES[2],
  // Delivery: audio-only transcodes fold into transcode, as in peak streams.
  direct: SERIES[0],
  remux: SERIES[1],
  transcode: SERIES[2],
}

/**
 * Sequential blue for the heatmap, low to high; the low end is step 600 so
 * it still clears 2:1 on the surface (validated as an ordinal ramp, dark).
 * Zero is the neutral empty tile, not a step of the ramp.
 */
export const RAMP = ['#184f95', '#256abf', '#3987e5', '#6da7ec', '#9ec5f4', '#cde2fb'] as const
export const EMPTY = '#232326'

/** Chart chrome on the dark surface. */
export const GRID = '#2c2c2a'
export const AXIS = '#383835'
export const MUTED = '#898781'
