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
export function Dialog({ title, children, actions, onCancel }: { title: string; children?: ReactNode; actions: DialogAction[]; onCancel(): void }) {
  return <Modal onClose={onCancel} className="tv-dialog-host">
    <section role="alertdialog" aria-modal="true" aria-label={title} className="tv-dialog">
      <h2 className="tv-dialog__title">{title}</h2>
      {children}
      <div className="tv-dialog__actions">
        {actions.map(({ label, safe, ...a }) => <Button key={a.id} {...a} autoFocus={safe}>{label}</Button>)}
      </div>
    </section>
  </Modal>
}

/**
 * The right-hand panel pickers and settings open over a screen (Audio,
 * Subtitles, Quality, a setting's choices). Return closes it; focus goes
 * back to what opened it. `className` sizes a screen's own kind of panel.
 */
export function SidePanel({ title, children, onClose, className }: { title: string; children: ReactNode; onClose(): void; className?: string }) {
  return <Modal onClose={onClose} className="">
    <aside role="dialog" aria-modal="true" aria-label={title} className={`tv-panel${className ? ` ${className}` : ''}`}>
      <h2 className="tv-panel__title">{title}</h2>
      <div className="tv-panel__body">{children}</div>
    </aside>
  </Modal>
}

/** One choice in a SidePanel: its label, a line under it, and a tick when chosen. */
export function Option({ id, label, detail, checked, onPress, autoFocus }: { id: string; label: string; detail?: string; checked?: boolean; onPress(): void; autoFocus?: boolean }) {
  return <button type="button" role="menuitemradio" aria-checked={!!checked} data-focus={id} data-autofocus={autoFocus || checked || undefined} className="tv-option" onClick={onPress}>
    <span className="tv-option__text"><span className="tv-option__label">{label}</span>{detail && <span className="tv-option__detail">{detail}</span>}</span>
    {checked && <span className="tv-option__check"><Icon name="check"/></span>}
  </button>
}

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
