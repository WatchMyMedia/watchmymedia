import { useRef, type FocusEvent, type ReactNode } from 'react'
import { Icon, type IconName } from '../../ui/Icon'
import type { RowSave } from './saves'
import { saveFailedLine } from './settingsWords'

/** The ▸ on a row that opens something (the kit's ▾, turned). */
export function Chevron() {
  return <span className="settings-chevron"><Icon name="down"/></span>
}

/** An on/off switch drawn in a row (`.toggle`). */
export function Toggle({ on }: { on: boolean }) {
  return <span className={`settings-toggle${on ? '' : ' settings-toggle--off'}`} aria-hidden="true"><span/></span>
}

export type SettingRowProps = {
  /** The data-focus id (paneRowId). */
  id: string
  label: string
  /** The line beside the label (or under it, with `stacked`). */
  detail?: string
  /** What is chosen, at the right; `chevron` adds ▸. */
  value?: ReactNode
  chevron?: boolean
  /** An on/off switch at the right, in place of a value. */
  toggle?: boolean
  stacked?: boolean
  onPress?: () => void
  /** Shown dim and skipped by focus (the account's settings before they load). */
  disabled?: boolean
  save?: RowSave
  tone?: 'danger' | 'off'
  icon?: IconName
  autoFocus?: boolean
}

/**
 * One row of a pane (`.row`): one focus stop, OK does it. A save under way
 * says Saving… where the value was; one that failed says what it is still,
 * and OK tries again.
 */
export function SettingRow({ id, label, detail, value, chevron, toggle, stacked, onPress, disabled, save, tone, icon, autoFocus }: SettingRowProps) {
  const failed = save?.state === 'failed' ? save : undefined
  const two = stacked || !!failed
  const right = save?.state === 'saving'
    ? <span className="settings-row__value"><span className="tv-spinner" aria-hidden="true"/>Saving…</span>
    : toggle !== undefined ? <Toggle on={toggle}/>
      : value !== undefined || chevron ? <span className="settings-row__value">{value}{chevron && <Chevron/>}</span> : null
  return <button type="button" data-focus={id} data-autofocus={autoFocus || undefined} aria-disabled={disabled || undefined}
    role={toggle !== undefined ? 'switch' : undefined} aria-checked={toggle !== undefined ? toggle : undefined}
    className={`settings-row${two ? ' settings-row--two' : ''}${disabled ? ' settings-row--dim' : ''}${tone ? ` settings-row--${tone}` : ''}`}
    onClick={disabled ? undefined : onPress}>
    {icon && <Icon name={icon}/>}
    {two ? <span className="settings-row__stack">
      <span className="settings-row__label">{label}</span>
      {failed ? <span role="alert" className="settings-row__error">{saveFailedLine(failed.still)}</span> : detail && <span className="settings-row__detail settings-row__detail--wrap">{detail}</span>}
    </span> : <>
      <span className="settings-row__label">{label}</span>
      {detail && <span className="settings-row__detail">{detail}</span>}
    </>}
    {right}
  </button>
}

/** A row that only tells (`.row` without focus): a version, this TV in the devices. */
export function InfoRow({ label, detail, value }: { label: string; detail?: string; value?: ReactNode }) {
  return <div className="settings-row settings-row--info">
    <span className="settings-row__label">{label}</span>
    {detail && <span className="settings-row__detail">{detail}</span>}
    {value !== undefined && <span className="settings-row__value">{value}</span>}
  </div>
}

export function PaneHeading({ children }: { children: ReactNode }) {
  return <h3 className="settings-heading settings-heading--pane">{children}</h3>
}

export function PaneNote({ children }: { children: ReactNode }) {
  return <p className="settings-note">{children}</p>
}

/**
 * A pane's list, moved with a transform as focus goes down it, as the
 * kit's VScroll does, except that its first focus stop shows the pane from
 * the top, with the title and the lines above it.
 */
export function PaneScroll({ children, pad = 48 }: { children: ReactNode; pad?: number }) {
  const view = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const offset = useRef(0)
  const onFocus = (e: FocusEvent) => {
    const v = view.current, t = track.current
    if (!v || !t) return
    const target = e.target as HTMLElement
    const first = t.querySelector('[data-focus]:not([aria-disabled="true"])')
    const box = target.getBoundingClientRect()
    const top = box.top - t.getBoundingClientRect().top
    const bottom = top + box.height
    let next = offset.current
    if (target === first) next = 0
    else if (top - next < pad) next = top - pad
    else if (bottom - next > v.clientHeight - pad) next = bottom - v.clientHeight + pad
    next = Math.max(0, Math.min(next, t.scrollHeight - v.clientHeight))
    if (next === offset.current) return
    offset.current = next
    t.style.transform = `translateY(${-next}px)`
  }
  return <div ref={view} className="settings-scroll" data-vscroll>
    <div ref={track} className="settings-scroll__track" onFocus={onFocus}>{children}</div>
  </div>
}
