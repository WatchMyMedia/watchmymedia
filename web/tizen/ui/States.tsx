import type { CSSProperties, ReactNode } from 'react'
import { Button, type ButtonProps } from './Button'
import { Icon, type IconName } from './Icon'
import { CARDS_IN_VIEW } from './tones'
import type { CardVariant } from './Card'

/** A grey block that pulses while something loads. */
export function Skeleton({ width, height, radius }: { width: string; height: string; radius?: string }) {
  return <span className="tv-skel" aria-hidden="true" style={{ width, height, borderRadius: radius }}/>
}

/** A line of text while it loads. */
export function SkeletonText({ width = '12rem' }: { width?: string }) {
  return <span className="tv-skel tv-skel--text" aria-hidden="true" style={{ width }}/>
}

/** A row of cards while it loads, the size of the real one. */
export function SkeletonRow({ variant, title = true }: { variant: CardVariant; title?: boolean }) {
  const poster = variant === 'poster'
  const w = poster ? 'var(--poster-w)' : 'var(--wide-w)'
  const h = poster ? 'var(--poster-h)' : 'var(--wide-h)'
  return <div className="tv-row" aria-hidden="true">
    {title && <div className="tv-row__head"><SkeletonText width="16rem"/></div>}
    <div className="tv-row__viewport"><div className="tv-row__track" style={{ '--cell-w': w } as CSSProperties}>
      {Array.from({ length: CARDS_IN_VIEW[variant] }, (_, i) => <span key={i} style={{ display: 'flex', flexDirection: 'column', gap: '.75rem' }}>
        <Skeleton width={w} height={h}/><SkeletonText width="70%"/>
      </span>)}
    </div></div>
  </div>
}

export type ProblemAction = Omit<ButtonProps, 'autoFocus' | 'children'> & { label: string }

/**
 * A screen that can't go on, and why: an icon, a title, a few lines and
 * what to do. Focus starts on the first action.
 */
export function ProblemScreen({ icon, title, children, actions, extra }: { icon: IconName; title: string; children?: ReactNode; actions: ProblemAction[]; extra?: ReactNode }) {
  return <section className="tv-problem" role="alert" aria-label={title}>
    <span className="tv-problem__icon"><Icon name={icon}/></span>
    <h1 className="tv-problem__title">{title}</h1>
    {children && <div className="tv-problem__body">{children}</div>}
    {extra}
    <div className="tv-actions">
      {actions.map(({ label, ...a }, i) => <Button key={a.id} {...a} variant={a.variant ?? (i === 0 ? 'primary' : 'ghost')} autoFocus={i === 0}>{label}</Button>)}
    </div>
  </section>
}
