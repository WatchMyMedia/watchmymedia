// The boards' line icons (24×24, 2px stroke), drawn in currentColor.
const PATHS = {
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-3.5-3.5',
  home: 'M3 11 12 3l9 8M5 10v10h14V10',
  shows: 'M3 5h18v12H3zM8 21h8',
  movies: 'M3 4h18v16H3zM7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4',
  live: 'M3 8h18v12H3zM8 3l4 5 4-5',
  settings: 'M12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
  globe: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18',
  unlocked: 'M4 11h16v10H4zM8 11V7a4 4 0 0 1 7.5-2',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  offline: 'M3 3l18 18M8 18h9a4 4 0 0 0 .8-7.9A6 6 0 0 0 7.2 8M5 10.5A4 4 0 0 0 6 18',
  update: 'M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5',
  signedOut: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10',
  setup: 'M4 5h16v11H4zM8 20h8M12 16v4M9 10l2 2 4-4',
  phone: 'M7 2h10v20H7zM11 18h2',
  check: 'M5 12l5 5 9-10',
  alert: 'M12 3 2 20h20L12 3zM12 10v4M12 17h.01',
  bell: 'M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  down: 'M6 9l6 6 6-6',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size }: { name: IconName; size?: string }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
    aria-hidden="true" style={size ? { width: size, height: size } : undefined}><path d={PATHS[name]}/></svg>
}

/** A solid play triangle, as on Play buttons and Continue watching cards. */
export function PlayGlyph() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>
}

/** The mark from the boards: a ring and a gold play triangle, with the name. */
export function Brand() {
  return <div className="tv-brand">
    <svg viewBox="0 0 512 512" aria-hidden="true"><circle cx="256" cy="256" r="164" fill="none" stroke="currentColor" strokeWidth="44"/><polygon points="222,190 222,322 330,256" fill="var(--accent)" stroke="var(--accent)" strokeWidth="28" strokeLinejoin="round"/></svg>
    <span>WatchMy<span>Media</span></span>
  </div>
}
