import type { components } from '../api/schema'

type Preferences = components['schemas']['Preferences']

/**
 * Subtitle size: one setting of the person's, kept on the server, that
 * every app applies to the subtitles it draws itself. Each step scales the
 * app's own size (the TVs' 22, 28, 34 and 42 px over 28). Subtitles burned
 * into the picture and styled ASS ones keep their own size.
 */

export type SubtitleSize = 'smaller' | 'normal' | 'larger' | 'largest'

export const SUBTITLE_SIZES: readonly { id: SubtitleSize; label: string }[] = [
  { id: 'smaller', label: 'Smaller' },
  { id: 'normal', label: 'Normal' },
  { id: 'larger', label: 'Larger' },
  { id: 'largest', label: 'Largest' },
]

export const SUBTITLE_SCALE: Readonly<Record<SubtitleSize, number>> = { smaller: 0.79, normal: 1, larger: 1.21, largest: 1.5 }

export const subtitleSizeLabel = (size: SubtitleSize) => SUBTITLE_SIZES.find((s) => s.id === size)?.label ?? 'Normal'

/** A stored value, read safely: anything unknown, or nothing, is Normal. */
export function subtitleSize(value: string | undefined | null): SubtitleSize {
  return value === 'smaller' || value === 'larger' || value === 'largest' ? value : 'normal'
}

export const subtitleSizeOf = (p: Pick<Preferences, 'subtitles'> | undefined) => subtitleSize(p?.subtitles?.size)

/** The line every preview shows. */
export const PREVIEW_LINE = 'Then why are the lights still on?'

/** Under the size, everywhere it is set. */
export const SIZE_KEEPS = 'Subtitles that are part of the picture keep their own size.'

/**
 * The web player's and the Cast receiver's cue size in pixels: 4.2% of the
 * picture's height at Normal, never under 14 px, times the step. Relative to
 * the picture, so subtitles look the same in a small window and on a TV.
 */
export function cueFontPx(pictureHeight: number, size: SubtitleSize): number {
  return Math.max(14, pictureHeight * 0.042) * SUBTITLE_SCALE[size]
}

/**
 * Where the picture is drawn inside a box that fits it whole (object-fit:
 * contain), for placing cues on the picture rather than on the bars.
 */
export function pictureBox(boxWidth: number, boxHeight: number, videoWidth: number, videoHeight: number): { left: number; top: number; width: number; height: number } {
  if (!(videoWidth > 0 && videoHeight > 0 && boxWidth > 0 && boxHeight > 0)) return { left: 0, top: 0, width: Math.max(0, boxWidth), height: Math.max(0, boxHeight) }
  const scale = Math.min(boxWidth / videoWidth, boxHeight / videoHeight)
  const width = videoWidth * scale
  const height = videoHeight * scale
  return { left: Math.max(0, (boxWidth - width) / 2), top: Math.max(0, (boxHeight - height) / 2), width, height }
}
