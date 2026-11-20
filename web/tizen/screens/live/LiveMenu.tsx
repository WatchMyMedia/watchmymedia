import type { CSSProperties, ReactNode } from 'react'
import { Modal } from '../../ui/Overlays'
import type { Menu } from './guideModel'
import { LiveIcon } from './LiveIcon'

/**
 * A Hold OK menu (the guide's, Recordings'): what it is for, its rows with
 * the first focused, and a note. Return closes it and focus goes back to
 * what was held. `at` places it beside the cell, in rem; centred without.
 */
export function LiveMenu<A extends string>({ menu, onPick, onClose, at, head }: { menu: Menu<A>; onPick(id: A): void; onClose(): void; at?: { left: number; top: number }; head?: ReactNode }) {
  const first = menu.items.find((i) => !i.disabled)?.id
  const style: CSSProperties | undefined = at ? { position: 'absolute', left: `${at.left}rem`, top: `${at.top}rem` } : undefined
  return <Modal onClose={onClose} className={at ? 'live-menu-host' : 'tv-dialog-host'}>
    <section role="dialog" aria-modal="true" aria-label={menu.title} className="live-menu" style={style}>
      <div className="live-menu__head">
        {head}
        <span className="live-menu__titles">
          <h2 className="live-menu__title">{menu.title}</h2>
          <span className="live-menu__sub">{menu.subtitle}</span>
        </span>
      </div>
      {menu.items.map((item) => <button key={item.id} type="button" className="live-mrow" data-focus={`menu/${item.id}`} data-autofocus={item.id === first || undefined}
        aria-disabled={item.disabled || undefined} onClick={item.disabled ? undefined : () => onPick(item.id)}>
        <LiveIcon name={item.icon} className="live-mrow__icon"/>{item.label}
      </button>)}
      {menu.note && <p className="live-menu__note">{menu.note}</p>}
    </section>
  </Modal>
}
