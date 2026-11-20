// The player's own icons, drawn as on the boards (24 × 24, stroked).

/** A stroked path, a filled one, or both. */
type Glyph = { d?: string; fill?: string; width?: number }

const GLYPHS = {
  back10: { d: 'M3 12a9 9 0 1 0 3-6.7M3 4v5h5' },
  forward10: { d: 'M21 12a9 9 0 1 1-3-6.7M21 4v5h-5' },
  play: { fill: 'M8 5v14l11-7z' },
  pause: { fill: 'M7 5h4v14H7zM13 5h4v14h-4z' },
  episodes: { d: 'M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7 20h10M8 8h8M8 12h5' },
  audio: { fill: 'M4 10v4h4l5 4V6L8 10z', d: 'M4 10v4h4l5 4V6L8 10zM16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11' },
  subtitles: { d: 'M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM10 10a2 2 0 1 0 0 4M17 10a2 2 0 1 0 0 4' },
  quality: { d: 'M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM8 9v6M8 12h3M11 9v6M14 9v6h2a3 3 0 0 0 0-6z' },
  nextEpisode: { fill: 'M6 5v14l9-7z', d: 'M18 5v14', width: 2.4 },
  skip: { d: 'm5 5 8 7-8 7M13 5l8 7-8 7', width: 2.4 },
  chevronLeft: { d: 'm15 6-6 6 6 6', width: 2.4 },
  check: { d: 'm5 12 5 5 9-10', width: 3 },
  download: { d: 'M12 4v11M7 10l5 5 5-5M5 20h14', width: 2.6 },
  signal: { d: 'M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01' },
  alert: { d: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7.5v5.5M12 16.5h.01' },
} satisfies Record<string, Glyph>

export type PlayerIconName = keyof typeof GLYPHS

export function PlayerIcon({ name }: { name: PlayerIconName }) {
  const g: Glyph = GLYPHS[name]
  return <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth={g.width ?? 2} strokeLinecap="round" strokeLinejoin="round">
    {g.fill && <path d={g.fill} fill="currentColor" stroke="none"/>}
    {g.d && <path d={g.d}/>}
  </svg>
}
