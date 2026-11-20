import { useRef, type ReactNode } from 'react'
import { useHold } from '../app/hooks'
import { Icon, PlayGlyph, type IconName } from './Icon'

export type ButtonProps = {
  /** The data-focus id: stable, unique on the screen. */
  id: string
  children?: ReactNode
  variant?: 'primary' | 'ghost' | 'danger'
  icon?: IconName | 'play'
  /** OK (or a click in the preview). */
  onPress?: () => void
  /** Hold OK. */
  onHold?: () => void
  /** Shows as working and ignores OK, but keeps focus (so Return and the arrows still work). */
  busy?: boolean
  /** Where the screen's focus starts. */
  autoFocus?: boolean
  /** For an icon-only button. */
  label?: string
}

/** The kit's button: 64px tall, burgundy (primary) or glass (ghost), the gold ring on focus. */
export function Button({ id, children, variant = 'ghost', icon, onPress, onHold, busy, autoFocus, label }: ButtonProps) {
  const ref = useRef<HTMLButtonElement>(null)
  useHold(ref, busy ? undefined : onHold)
  const iconOnly = !children
  return <button ref={ref} type="button" data-focus={id} data-autofocus={autoFocus || undefined} aria-label={label} aria-disabled={busy || undefined}
    className={`tv-btn tv-btn--${variant}${iconOnly ? ' tv-btn--icon' : ''}`} onClick={busy ? undefined : onPress}>
    {icon === 'play' ? <PlayGlyph/> : icon ? <Icon name={icon}/> : null}
    {children}
  </button>
}

/** The remote's keys a screen can use, at its foot ("OK Connect · ▲ ▼ Move"). */
export function KeyHints({ hints, right }: { hints: { key: string; label: string }[]; right?: ReactNode }) {
  return <div className="tv-hints" aria-hidden="true">
    <span className="tv-hints__keys">{hints.map((h) => <span key={h.key + h.label} className="tv-hints__hint"><span className="tv-kbd">{h.key}</span><span>{h.label}</span></span>)}</span>
    {right && <span>{right}</span>}
  </div>
}
