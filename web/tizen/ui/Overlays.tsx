import { useEffectEvent, useLayoutEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { LayerContext, useBack, useFocus, useKeys } from '../app/hooks'
import { Button, type ButtonProps } from './Button'
import { Icon, type IconName } from './Icon'

/**
 * Holds focus inside a modal while it is open (data-scope="modal") and puts
 * it back on what the screen had when it closes, or where `returnFocus`
 * puts it (the rail's button that opened a panel). Its keys come first.
 */
function useModalFocus(returnFocus?: () => boolean) {
  const focus = useFocus()
  const elsewhere = useEffectEvent(() => returnFocus?.() === true)
  useLayoutEffect(() => {
    focus.restore([], 'modal')
    return () => queueMicrotask(() => { if (!focus.scope('modal') && !elsewhere()) focus.focusContent() })
  }, [focus])
}

function Modal({ children, onClose, className, returnFocus }: {
  children: ReactNode
  onClose(): void
  className: string
  /** Where focus goes when this closes, when that isn't the screen: return true once it is there. */
  returnFocus?: () => boolean
}) {
  useModalFocus(returnFocus)
  useBack(() => { onClose(); return true }, true, 'modal')
  return createPortal(<LayerContext.Provider value="modal">
    <div className={`tv-scrim ${className}`} data-scope="modal">{children}</div>
  </LayerContext.Provider>, document.getElementById('root') ?? document.body)
}

export type DialogAction = Omit<ButtonProps, 'autoFocus'> & { label: string; safe?: boolean }

/**
 * A centred confirmation: a title, a few lines, and its buttons. Focus
 * starts on the safe one (Stay, Keep, Cancel), which Return also chooses.
 */
export function Dialog({ title, children, actions, onCancel, narrow }: { title: string; children?: ReactNode; actions: DialogAction[]; onCancel(): void; narrow?: boolean }) {
  return <Modal onClose={onCancel} className="tv-dialog-host">
    <section role="alertdialog" aria-modal="true" aria-label={title} className={`tv-dialog${narrow ? ' tv-dialog--narrow' : ''}`}>
      <h2 className="tv-dialog__title">{title}</h2>
      {children}
      <div className="tv-dialog__actions">
        {actions.map(({ label, safe, ...a }) => <Button key={a.id} {...a} autoFocus={safe}>{label}</Button>)}
      </div>
    </section>
  </Modal>
}

/**
 * The panel a choice opens over a screen (More, Audio and subtitles,
 * Quality, a setting's choices): from the right, 760px wide (960 for File
 * details), or from the left (Servers). Its title, a line under it, the
 * rows, and what is at its foot. Return closes it; focus goes back to what
 * opened it. `className` sizes a screen's own kind of panel.
 */
export function SidePanel({ title, sub, children, foot, onClose, className, side = 'right', wide }: {
  title: string
  /** The line under the title (the movie's name, "Kept for this TV"). */
  sub?: ReactNode
  children: ReactNode
  /** Under the rows: a note, or the panel's key hint. */
  foot?: ReactNode
  onClose(): void
  className?: string
  side?: 'right' | 'left'
  wide?: boolean
}) {
  return <Modal onClose={onClose} className="">
    <aside role="dialog" aria-modal="true" aria-label={title} className={`tv-panel${side === 'left' ? ' tv-panel--left' : ''}${wide ? ' tv-panel--wide' : ''}${className ? ` ${className}` : ''}`}>
      <h2 className="tv-panel__title">{title}</h2>
      {sub && <p className="tv-panel__sub">{sub}</p>}
      <div className="tv-panel__body">{children}</div>
      {foot && <div className="tv-panel__foot">{foot}</div>}
    </aside>
  </Modal>
}

/**
 * One row in a SidePanel: its label, a line under it, and how it is marked.
 * A choice among several shows a radio, one that opens more shows a chevron
 * (`next`), a plain action shows nothing. Chosen is the gold dot, never a ring.
 */
export function Option({ id, label, detail, checked, onPress, autoFocus, next, end, mark = 'radio', disabled }: {
  id: string
  label: string
  detail?: string
  /** A choice: whether it is the chosen one. Leave out for an action. */
  checked?: boolean
  onPress(): void
  autoFocus?: boolean
  /** Opens the next panel in place: a chevron at the end. */
  next?: boolean
  /** Words at the end of the row ("English"). */
  end?: ReactNode
  /** How a choice is marked: a radio (the default) or a tick when chosen. */
  mark?: 'radio' | 'tick'
  disabled?: boolean
}) {
  const choice = checked !== undefined
  return <button type="button" role={choice ? 'menuitemradio' : 'menuitem'} aria-checked={choice ? checked : undefined} data-focus={id} data-autofocus={autoFocus || checked || undefined}
    aria-disabled={disabled || undefined} className="tv-option" onClick={disabled ? undefined : onPress}>
    {choice && mark === 'radio' && <span className={`tv-radio${checked ? ' tv-radio--on' : ''}`} aria-hidden="true"/>}
    <span className="tv-option__text"><span className="tv-option__label">{label}</span>{detail && <span className="tv-option__detail">{detail}</span>}</span>
    {(end || next) && <span className="tv-option__end">{end}{next && <Icon name="right"/>}</span>}
    {choice && mark === 'tick' && checked && <span className="tv-option__check"><Icon name="check"/></span>}
  </button>
}

/** The line between groups of rows in a panel. */
export function PanelLine() { return <div className="tv-panel__line" aria-hidden="true"/> }

/**
 * A notice over the screen that takes no focus (a Live TV reminder, being
 * offline). Its keys come before the screen's: Return dismisses it, Hold OK
 * does its action, and every other key goes on to the screen.
 */
export function Banner({ icon = 'bell', title, children, hints, onDismiss, onHoldOk }: { icon?: IconName; title: string; children?: ReactNode; hints?: ReactNode; onDismiss?(): void; onHoldOk?(): void }) {
  useKeys((i) => {
    if (i.action === 'back' && onDismiss) { onDismiss(); return true }
    if (i.action === 'hold' && onHoldOk) { onHoldOk(); return true }
    return false
  }, true, 'overlay')
  return <div className="tv-banner" role="status">
    <span className="tv-banner__icon"><Icon name={icon}/></span>
    <span className="tv-banner__text">
      <span className="tv-banner__title">{title}</span>
      {children && <span className="tv-banner__body">{children}</span>}
      {hints && <span className="tv-banner__hints">{hints}</span>}
    </span>
  </div>
}

/**
 * A modal over the whole screen, as the request dialog is:
 * it draws its own page, takes every key while open, and Return
 * closes it, putting focus back on what opened it.
 */
export function Sheet({ label, children, onClose }: { label: string; children: ReactNode; onClose(): void }) {
  return <Modal onClose={onClose} className="tv-sheet">
    <section role="dialog" aria-modal="true" aria-label={label} className="tv-sheet__page">{children}</section>
  </Modal>
}

/** The frame Dialog and SidePanel share (focus held inside, Return closes), for a feature's own modal such as a Hold OK menu. */
export { Modal }
