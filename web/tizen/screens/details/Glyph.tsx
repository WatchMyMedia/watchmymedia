// The detail screens' own line icons from the boards (24×24), beside the
// kit's: the ⋯ of More, the trailer's clapper, the menus' flag and bin, the
// bell, and the rest. Drawn in currentColor; `fill` ones are solid.
const GLYPHS = {
  more: { d: 'M5 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM19 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z', fill: true },
  clapper: { d: 'M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z', fill: true },
  flag: { d: 'M5 21V4h11l-1.5 4L16 12H5', fill: false },
  trash: { d: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3', fill: false },
  replace: { d: 'M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5', fill: false },
  bell: { d: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0', fill: false },
  bellOn: { d: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9z', fill: true },
  plus: { d: 'M12 5v14M5 12h14', fill: false },
  info: { d: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01', fill: false },
  file: { d: 'M14 3H6v18h12V7zM14 3v4h4', fill: false },
  pause: { d: 'M7 5h3.5v14H7zM13.5 5H17v14h-3.5z', fill: true },
  check: { d: 'M5 12l5 5 9-10', fill: false },
  down: { d: 'M6 9l6 6 6-6', fill: false },
  ring: { d: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z', fill: false },
} as const

export type GlyphName = keyof typeof GLYPHS

export function Glyph({ name, className }: { name: GlyphName; className?: string }) {
  const g = GLYPHS[name]
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" fill={g.fill ? 'currentColor' : 'none'} stroke={g.fill ? 'none' : 'currentColor'}
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={g.d}/></svg>
}
