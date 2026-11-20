import type { MenuIcon } from './guideModel'
import type { ProblemIcon } from './liveProblems'

// The Live TV boards' own icons (24×24), beside the kit's. Filled ones are
// solid shapes; the rest are 2px strokes in currentColor.

const STROKE: Partial<Record<LiveIconName, string>> = {
  bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0',
  bellOff: 'M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7M10.3 21a1.94 1.94 0 0 0 3.4 0M2 2l20 20',
  moved: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  star: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  close: 'M6 6l12 12M18 6 6 18',
  swap: 'M4 8h13l-3-3M20 16H7l3 3',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
  guide: 'M3 5h18v14H3zM3 10h18M9 10v9',
  keypad: 'M4 3h16v18H4zM8 7h.01M12 7h.01M16 7h.01M8 11h.01M12 11h.01M16 11h.01M8 15h.01M12 15h.01M16 15h.01',
  chevron: 'm9 6 6 6-6 6',
  chevronLeft: 'm15 6-6 6 6 6',
  up: 'm18 15-6-6-6 6',
  down: 'm6 9 6 6 6-6',
  quality: 'M3 5h18v14H3zM8 9v6M8 12h3M11 9v6M14 9v6h2a3 3 0 0 0 0-6z',
  tuner: 'M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM7 12h.01M11 12h.01M15 12h2',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
  antennaOff: 'M12 12v9M8.5 8.5a5 5 0 0 1 7 0M5 5a10 10 0 0 1 14 0M3 3l18 18',
  lock: 'M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3',
  offline: 'M2 9a15 15 0 0 1 20 0M5.5 12.5a10 10 0 0 1 13 0M9 16a5 5 0 0 1 6 0M12 19.5h.01M3 3l18 18',
  gauge: 'M12 14l4-4M4 18a9 9 0 1 1 16 0',
  noNet: 'M3 3l18 18M8 18h9a4 4 0 0 0 .8-7.9A6 6 0 0 0 7.2 8M5 10.5A4 4 0 0 0 6 18',
  power: 'M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  pause: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM10 9v6M14 9v6',
  done: 'm5 12 5 5 9-10',
  warn: 'M12 3 2 20h20zM12 10v4M12 17h.01',
}

const FILL: Partial<Record<LiveIconName, string>> = {
  play: 'M8 5v14l11-7z',
  pauseBars: 'M7 5h4v14H7zM13 5h4v14h-4z',
  starFilled: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  start: 'M5 5h2v14H5zM20 5v14L9 12z',
  stop: 'M7 7h10v10H7z',
}

export type LiveIconName = MenuIcon | ProblemIcon | 'moved' | 'keypad' | 'chevron' | 'chevronLeft' | 'up' | 'down' | 'quality' | 'pauseBars' | 'done' | 'warn'

export function LiveIcon({ name, className }: { name: LiveIconName; className?: string }) {
  if (name === 'record') {
    return <svg className={className} viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2"/><circle cx="12" cy="12" r="4.5" className="live-rec-fill"/></svg>
  }
  const fill = FILL[name]
  if (fill) return <svg className={className} viewBox="0 0 24 24" aria-hidden="true"><path d={fill} fill="currentColor"/></svg>
  return <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={STROKE[name] ?? ''}/></svg>
}

/** The guide's recording marks: a red dot (will record), ringed for a series, hollow orange (won't record). */
export function RecordingMark({ look }: { look: 'dot' | 'ring' | 'rec' | 'hollow' }) {
  if (look === 'rec') return <span className="live-tag live-tag--rec">REC</span>
  return <span className={`live-mark live-mark--${look}`} aria-hidden="true"/>
}
