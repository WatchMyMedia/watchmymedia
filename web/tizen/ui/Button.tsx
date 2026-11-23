import { useRef, type ReactNode } from 'react'
import { useHold } from '../app/hooks'
import { Icon, PlayGlyph, type IconName } from './Icon'
import { showNote } from './notes'

export type ButtonProps = {
  /** The data-focus id: stable, unique on the screen. */
  id: string
  children?: ReactNode
  /** Primary is burgundy (one a screen); ghost is the quiet grey; glass the grey over a picture. */
  variant?: 'primary' | 'ghost' | 'glass' | 'danger'
  icon?: IconName | 'play'
  /** OK (or a click in the preview). */
  onPress?: () => void
  /** Hold OK: a shortcut only, never the one way to do something. */
  onHold?: () => void
  /** Shows as working and ignores OK, but keeps focus (so Return and the arrows still work). */
  busy?: boolean
  /** Where the screen's focus starts. */
  autoFocus?: boolean
  /** For an icon-only button. */
  label?: string
  /** A toggle (Notify me, Watchlist): whether it is on, for a screen reader. */
  pressed?: boolean
  /** Can't be pressed now: dimmed, and still focusable (Connect before an address is typed). */
  disabled?: boolean
  /** What a press says while it is disabled ("Type an address first"). */
  why?: string
}

/** The kit's button: a 76px pill, burgundy (primary) or quiet grey, the gold ring and a little growth on focus. */
export function Button({ id, children, variant = 'ghost', icon, onPress, onHold, busy, autoFocus, label, pressed, disabled, why }: ButtonProps) {
  const ref = useRef<HTMLButtonElement>(null)
  const off = busy || disabled
  useHold(ref, off ? undefined : onHold)
  const iconOnly = !children
  const press = off ? (disabled && why ? () => showNote(why, 'info') : undefined) : onPress
  return <button ref={ref} type="button" data-focus={id} data-autofocus={autoFocus || undefined} aria-label={label} aria-disabled={disabled || undefined} aria-busy={busy || undefined} aria-pressed={pressed}
    className={`tv-btn tv-btn--${variant}${iconOnly ? ' tv-btn--icon' : ''}${busy ? ' tv-btn--busy' : ''}`} onClick={press}>
    {busy ? <span className="tv-spinner" aria-hidden="true"/> : icon === 'play' ? <PlayGlyph/> : icon ? <Icon name={icon}/> : null}
    {children}
  </button>
}

/** The remote's keys a screen can use, at its foot inside the safe area ("Return Back"). One line at most. */
export function KeyHints({ hints, right }: { hints: { key: string; label: string }[]; right?: ReactNode }) {
  return <div className="tv-hints" aria-hidden="true">
    <span className="tv-hints__keys">{hints.map((h) => <span key={h.key + h.label} className="tv-hints__hint"><span className="tv-kbd">{h.key}</span><span>{h.label}</span></span>)}</span>
    {right && <span>{right}</span>}
  </div>
}
