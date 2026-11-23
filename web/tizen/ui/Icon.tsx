// The boards' line icons (24×24, 2px stroke), drawn in currentColor.
const PATHS = {
  search: 'M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13zM15.5 15.5l5 5',
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  shows: 'M4.5 5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM8 21h8',
  movies: 'M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7.5 4v16M16.5 4v16M3 9.3h4.5M3 14.7h4.5M16.5 9.3H21M16.5 14.7H21',
  live: 'M12 9.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2zM12 13v8M8.6 7.6a4.8 4.8 0 0 0 0 6.8M15.4 7.6a4.8 4.8 0 0 1 0 6.8M5.8 4.8a8.8 8.8 0 0 0 0 12.4M18.2 4.8a8.8 8.8 0 0 1 0 12.4',
  settings: 'M4 7h9M19 7h1M4 17h3M13 17h7M16 4.4a2.6 2.6 0 1 0 0 5.2a2.6 2.6 0 1 0 0-5.2zM10 14.4a2.6 2.6 0 1 0 0 5.2a2.6 2.6 0 1 0 0-5.2z',
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
  up: 'M6 15l6-6 6 6',
  right: 'M9 5l7 7-7 7',
  left: 'M15 5l-7 7 7 7',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01',
  filter: 'M4 6h16M7 12h10M10 18h4',
  sort: 'M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  trailer: 'M4 5h16v14H4zM10 9v6l5-3z',
  restart: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5',
  person: 'M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4 21a8 8 0 0 1 16 0',
  plus: 'M12 5v14M5 12h14',
  swap: 'M4 7h14M15 4l3 3-3 3M20 17H6M9 14l-3 3 3 3',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6',
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

/** The reel, the app's mark: burgundy holes in a white disc. */
export function Reel() {
  return <svg viewBox="0 0 512 512" aria-hidden="true">
    <circle cx="256" cy="256" r="244" fill="var(--text)"/><circle cx="256" cy="256" r="233" fill="none" stroke="var(--brand)" strokeWidth="22"/>
    <circle cx="256" cy="120" r="64" fill="var(--brand)"/><circle cx="385.34" cy="213.97" r="64" fill="var(--brand)"/><circle cx="335.94" cy="366.03" r="64" fill="var(--brand)"/>
    <circle cx="176.06" cy="366.03" r="64" fill="var(--brand)"/><circle cx="126.66" cy="213.97" r="64" fill="var(--brand)"/>
  </svg>
}

/** The mark from the boards: the reel, with the name. */
export function Brand() {
  return <div className="tv-brand">
    <Reel/>
    <span>WatchMy<span>Media</span></span>
  </div>
}

