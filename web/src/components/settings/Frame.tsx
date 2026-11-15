import { useId, type AnchorHTMLAttributes, type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { Crumb } from '../../lib/settingsRoutes'
import type { SaveState } from './hooks'
import { useAway, useGo } from './hooks'

// The pieces every Settings page and flow is drawn with
// (PLAN-ADMIN-SETTINGS.md §3): the breadcrumb, the away chips and lock marks,
// buttons, the hub's parts, messages, fields and choices.

/** A link inside the app by address. Goes through the leave check. */
export function HrefLink({ href, children, className = '', ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  const go = useGo()
  return (
    <a
      {...rest}
      href={href}
      className={className}
      onClick={(e) => {
        rest.onClick?.(e)
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
        e.preventDefault()
        go(href)
      }}
    >
      {children}
    </a>
  )
}

export function Breadcrumb({ crumbs, className = '' }: { crumbs: Crumb[]; className?: string }) {
  return (
    <nav aria-label="Breadcrumb" className={`min-w-0 text-[13px] text-neutral-400 ${className}`}>
      <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1
          return (
            <li key={`${i}-${c.label}`} className={`flex min-w-0 items-center gap-1.5 ${c.wideOnly ? 'hidden sm:flex' : ''}`}>
              {last ? (
                <span aria-current="page" className="truncate font-semibold text-neutral-100">{c.label}</span>
              ) : c.href ? (
                <HrefLink href={c.href} className="truncate hover:text-neutral-100 hover:underline">{c.label}</HrefLink>
              ) : (
                <span className="truncate">{c.label}</span>
              )}
              {!last && <span aria-hidden="true" className="text-neutral-600">›</span>}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

/** Away from home: the amber chip, and how long a confirmed password lasts. */
export function AwayChips() {
  const { away, minutes } = useAway()
  if (!away) return null
  return (
    <span className="flex shrink-0 flex-wrap justify-end gap-1.5" role="status">
      <span className="inline-flex min-h-[28px] items-center rounded-full border border-amber-800 bg-amber-950/50 px-2.5 text-[12.5px] whitespace-nowrap text-amber-300">Away from home</span>
      {minutes > 0 && (
        <span className="inline-flex min-h-[28px] items-center rounded-full border border-emerald-900 bg-emerald-950/60 px-2.5 text-[12.5px] whitespace-nowrap text-emerald-300">
          Password confirmed · {minutes} min left
        </span>
      )}
    </span>
  )
}

export function LockIcon({ className = '' }: { className?: string }) {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={`shrink-0 ${className}`}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

export function Spinner({ className = '' }: { className?: string }) {
  return <span aria-hidden="true" className={`inline-block h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-current border-r-transparent motion-reduce:animate-none ${className}`} />
}

const buttonLook = {
  primary: 'btn-primary font-semibold',
  secondary: 'border border-line-2 text-neutral-100 hover:bg-surface-2',
  danger: 'border border-red-900 text-red-300 hover:bg-red-950/40',
  quiet: 'text-neutral-300 hover:bg-surface-2 hover:text-neutral-100',
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: keyof typeof buttonLook
  /** Away from home, this button asks for the password: it carries a lock
   * until the password is confirmed. */
  guarded?: boolean
  busy?: boolean
  busyLabel?: ReactNode
  small?: boolean
}

export function Button({ variant = 'secondary', guarded, busy, busyLabel, small, children, className = '', disabled, type = 'button', ...rest }: ButtonProps) {
  const { away, confirmed } = useAway()
  const lock = guarded && away && !confirmed
  return (
    <button
      {...rest}
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3.5 text-sm transition-[filter,background-color] disabled:cursor-not-allowed disabled:opacity-50 ${small ? 'min-h-[36px]' : 'min-h-[44px]'} ${buttonLook[variant]} ${className}`}
    >
      {busy ? <Spinner /> : lock && <LockIcon />}
      {busy && busyLabel ? busyLabel : children}
      {lock && <span className="sr-only"> (asks for your password)</span>}
    </button>
  )
}

/** The line beside a guarded button, away from home only. */
export function GuardedCaption({ className = '' }: { className?: string }) {
  const { away, confirmed } = useAway()
  if (!away || confirmed) return null
  return <span className={`text-[12.5px] leading-tight text-amber-300 ${className}`}>Away from home, this asks for your password.</span>
}

export type PillTone = 'ok' | 'warn' | 'err' | 'now' | 'off'

const pillLook: Record<PillTone, string> = {
  ok: 'border-emerald-900 bg-emerald-950/60 text-emerald-300',
  warn: 'border-orange-900 bg-orange-950/50 text-warn',
  err: 'border-red-900 bg-red-950/50 text-red-300',
  now: 'border-amber-800 bg-amber-950/40 text-accent',
  off: 'border-line-2 text-neutral-400',
}

export function Pill({ tone = 'off', children }: { tone?: PillTone; children: ReactNode }) {
  return <span className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium whitespace-nowrap ${pillLook[tone]}`}>{children}</span>
}

/**
 * A tab's hub or an item page under it (§3.1): the breadcrumb with the away
 * chips, the title with its state in a line and a pill, an optional banner,
 * then the page. Help, when given, sits beside the page (under it on phones).
 */
export function HubPage({ crumbs, title, lede, pill, banner, help, actions, children }: {
  crumbs: Crumb[]
  title: ReactNode
  lede?: ReactNode
  pill?: { tone: PillTone; label: ReactNode }
  banner?: ReactNode
  help?: ReactNode
  actions?: ReactNode
  children?: ReactNode
}) {
  return (
    <div className="settings-fade">
      <div className="mb-3 flex items-start justify-between gap-3">
        <Breadcrumb crumbs={crumbs} className="pt-1" />
        <AwayChips />
      </div>
      <div className="mb-6 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        <div className="min-w-0 max-w-2xl flex-1 basis-72">
          <h2 className="text-[22px] font-semibold tracking-tight break-words">{title}</h2>
          {lede && <p className="mt-1.5 text-neutral-400">{lede}</p>}
        </div>
        {(pill || actions) && (
          <div className="flex flex-wrap items-center gap-2">
            {pill && <Pill tone={pill.tone}>{pill.label}</Pill>}
            {actions}
          </div>
        )}
      </div>
      {banner && <div className="mb-6 space-y-3">{banner}</div>}
      {help ? (
        <div className="grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_17rem]">
          <div className="min-w-0 space-y-6">{children}</div>
          <Help>{help}</Help>
        </div>
      ) : (
        <div className="space-y-6">{children}</div>
      )}
    </div>
  )
}

/** Help beside the form (§3.2), under it on phones. */
export function Help({ children }: { children: ReactNode }) {
  return <aside className="border-l-2 border-line pl-4 text-[13.5px] text-neutral-400 [&_h4]:mb-1 [&_h4]:text-[13px] [&_h4]:font-semibold [&_h4]:text-neutral-300 [&_p]:mb-3">{children}</aside>
}

/** The one next step on a hub, shown only when there is something to do. */
export function NextStep({ title, children, action, label = 'Next step' }: { title: ReactNode; children?: ReactNode; action?: ReactNode; label?: string }) {
  return (
    <section aria-label={label} className="rounded-xl border border-accent bg-accent/[0.06] p-5">
      <div className="text-xs font-bold tracking-wider text-accent uppercase">{label}</div>
      <h3 className="mt-1 text-base font-semibold">{title}</h3>
      {children && <div className="mt-1 text-sm text-neutral-400">{children}</div>}
      {action && <div className="mt-3 flex flex-wrap gap-2">{action}</div>}
    </section>
  )
}

export type GuideItem = {
  key: string
  title: ReactNode
  description?: ReactNode
  state: 'done' | 'now' | 'todo'
  /** Shown in place of "Now" on a dimmed task: what it waits for. */
  waits?: ReactNode
  optional?: boolean
  action?: ReactNode
}

/** The setup guide (§3.1): never more than five tasks, with a count and a bar. */
export function SetupGuide({ items, done, total }: { items: GuideItem[]; done: number; total: number }) {
  const id = useId()
  return (
    <section aria-labelledby={id}>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <SectionLabel id={id}>Setup guide · {done} of {total} done</SectionLabel>
        <div className="h-1.5 w-28 overflow-hidden rounded-full bg-line" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done} aria-label="Setup guide">
          <div className="h-full rounded-full bg-emerald-400 transition-[width] motion-reduce:transition-none" style={{ width: `${total ? (done / total) * 100 : 0}%` }} />
        </div>
      </div>
      <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((t, i) => (
          <li key={t.key} aria-current={t.state === 'now' ? 'step' : undefined}
            className={`flex flex-col gap-1.5 rounded-xl border bg-surface p-4 ${t.state === 'done' ? 'border-emerald-900' : t.state === 'now' ? 'border-accent' : 'border-line-2 opacity-70'}`}>
            <span className={`text-xs font-semibold tracking-wide uppercase ${t.state === 'done' ? 'text-emerald-300' : t.state === 'now' ? 'text-accent' : 'text-neutral-400'}`}>
              {i + 1} · {t.state === 'done' ? 'Done' : t.state === 'now' ? 'Now' : t.waits ?? 'To do'}{t.optional ? ' · Optional' : ''}
            </span>
            <h4 className="font-semibold">{t.title}</h4>
            {t.description && <p className="flex-1 text-[13.5px] text-neutral-400">{t.description}</p>}
            {t.action && <div className="mt-1 flex flex-wrap gap-2">{t.action}</div>}
          </li>
        ))}
      </ol>
    </section>
  )
}

export function SectionLabel({ children, id }: { children: ReactNode; id?: string }) {
  return <h3 id={id} className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">{children}</h3>
}

/** A titled group of rows on a hub. */
export function Section({ title, children, aside }: { title?: ReactNode; children: ReactNode; aside?: ReactNode }) {
  return (
    <section>
      {(title || aside) && (
        <div className="mb-2 flex items-center justify-between gap-3">
          {title && <SectionLabel>{title}</SectionLabel>}
          {aside}
        </div>
      )}
      {children}
    </section>
  )
}

/** A card of rows divided by lines. */
export function Card({ children, className = '', padded }: { children: ReactNode; className?: string; padded?: boolean }) {
  return <div className={`rounded-xl border border-line bg-surface ${padded ? 'p-5' : 'divide-y divide-line'} ${className}`}>{children}</div>
}

/** A row on a hub. With `href` the whole row opens that page. */
export function ItemRow({ href, title, pill, detail, control, children }: { href?: string; title: ReactNode; pill?: ReactNode; detail?: ReactNode; control?: ReactNode; children?: ReactNode }) {
  const body = (
    <>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <h4 className="min-w-0 font-medium break-words">{title}</h4>
          {pill}
        </div>
        {detail && <div className="mt-0.5 text-sm break-words text-neutral-400 [overflow-wrap:anywhere]">{detail}</div>}
        {children}
      </div>
      {control && <div className="flex shrink-0 flex-wrap items-center gap-2">{control}</div>}
    </>
  )
  if (href) {
    return (
      <HrefLink href={href} className="flex min-h-[60px] items-center gap-4 px-5 py-3.5 hover:bg-surface-2 focus-visible:bg-surface-2">
        {body}
        <span aria-hidden="true" className="text-lg text-neutral-500">›</span>
      </HrefLink>
    )
  }
  return <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">{body}</div>
}

/** Removes sit apart, at the bottom of the page they belong to (§3.8). */
export function RemoveSection({ children, title = 'Remove' }: { children: ReactNode; title?: ReactNode }) {
  return (
    <Section title={title}>
      <Card>{children}</Card>
    </Section>
  )
}

export type MessageTone = 'err' | 'warn' | 'ok' | 'info' | 'pw'

const messageLook: Record<MessageTone, { box: string; title: string }> = {
  err: { box: 'border-red-900 bg-red-950/40', title: 'text-red-200' },
  warn: { box: 'border-orange-900 bg-orange-950/30', title: 'text-orange-200' },
  ok: { box: 'border-emerald-900 bg-emerald-950/40', title: 'text-emerald-200' },
  info: { box: 'border-line-2 bg-surface', title: 'text-neutral-100' },
  pw: { box: 'border-amber-800 bg-amber-950/30', title: 'text-amber-300' },
}

/**
 * A message (§3.5): what happened, whether anything was saved, and what to
 * do, with the fix as a button. Refusals are alerts; reports are statuses.
 * `focus` moves focus to it when it appears.
 */
export function Message({ tone = 'info', title, children, actions, focus, className = '' }: { tone?: MessageTone; title?: ReactNode; children?: ReactNode; actions?: ReactNode; focus?: boolean; className?: string }) {
  const look = messageLook[tone]
  return (
    <div
      role={tone === 'err' ? 'alert' : 'status'}
      tabIndex={focus ? -1 : undefined}
      ref={focus ? (el) => { el?.focus() } : undefined}
      className={`settings-fade rounded-[10px] border px-4 py-3 text-sm outline-none ${look.box} ${className}`}
    >
      {title && <b className={`block font-semibold ${look.title}`}>{title}</b>}
      {children && <div className={`${title ? 'mt-1' : ''} text-[13.5px] text-neutral-300 [&_a]:underline`}>{children}</div>}
      {actions && <div className="mt-2.5 flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

/** A choice's result in its own row: Saving…, ✓ Saved, or why it failed. */
export function SaveNote({ state, idle }: { state: SaveState; idle?: ReactNode }) {
  if (state.status === 'saving') return <span role="status" className="inline-flex items-center gap-1.5 text-sm text-neutral-400"><Spinner />Saving…</span>
  if (state.status === 'saved') return <span role="status" className="settings-fade text-sm text-emerald-300">✓ Saved.{state.message ? ` ${state.message}` : ''}</span>
  if (state.status === 'failed') return <span role="alert" className="settings-fade text-sm text-red-300">{state.message ?? 'Couldn’t save.'} It’s back as it was.</span>
  return idle ? <span className="text-sm text-neutral-400">{idle}</span> : null
}

export const fieldInput = 'min-h-[44px] w-full rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 placeholder:text-neutral-500 aria-[invalid=true]:border-red-700'

/** A labelled field: its hint, and its mistake under it (§3.5). The child is
 * the control; give it `id` and `aria-describedby` from the render prop. */
export function Field({ label, hint, error, children, optional }: {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  optional?: boolean
  children: (props: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }) => ReactNode
}) {
  const id = useId()
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined
  return (
    <div className="mt-4 first:mt-0">
      <label htmlFor={id} className="block text-sm font-semibold">
        {label}
        {optional && <span className="ml-1.5 font-normal text-neutral-400">Optional</span>}
      </label>
      {hint && <p id={`${id}-hint`} className="mt-0.5 text-[13px] text-neutral-400">{hint}</p>}
      <div className="mt-1.5">{children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}</div>
      {error && <p id={`${id}-error`} className="mt-1.5 text-sm text-red-300">{error}</p>}
    </div>
  )
}

/** One choice of several, as a card: radio by default, or a checkbox. */
export function Choice({ name, type = 'radio', checked, onChange, title, children, disabled, reason, value }: {
  name: string
  type?: 'radio' | 'checkbox'
  checked: boolean
  onChange: (checked: boolean) => void
  title: ReactNode
  children?: ReactNode
  disabled?: boolean
  /** Why it can't be chosen, shown in place of nothing. */
  reason?: ReactNode
  value?: string
}) {
  return (
    <label className={`mt-2.5 flex gap-3 rounded-[10px] border p-3.5 first:mt-0 ${checked ? 'border-accent bg-accent/[0.06]' : 'border-line bg-surface'} ${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:border-line-2'}`}>
      <input type={type} name={name} value={value} checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-accent" />
      <span className="min-w-0">
        <span className="block font-medium">{title}</span>
        {children && <span className="mt-0.5 block text-[13.5px] text-neutral-400">{children}</span>}
        {disabled && reason && <span className="mt-1 block text-[13px] text-warn">{reason}</span>}
      </span>
    </label>
  )
}

/** A switch that saves at once. */
export function Switch({ checked, onChange, disabled, label }: { checked: boolean; onChange: (on: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)}
      className={`relative inline-flex h-[26px] w-[46px] shrink-0 items-center rounded-full border transition-colors disabled:opacity-50 motion-reduce:transition-none ${checked ? 'border-accent bg-accent' : 'border-line-2 bg-line'}`}>
      <span aria-hidden="true" className={`inline-block h-5 w-5 rounded-full bg-neutral-100 shadow transition-transform motion-reduce:transition-none ${checked ? 'translate-x-[22px] bg-black' : 'translate-x-[2px]'}`} />
    </button>
  )
}

/**
 * The save bar (§3.6): appears with a draft's first change and holds Discard
 * and Save. Sticks to the top of the page.
 */
export function SaveBar({ dirty, saving, onDiscard, onSave, guarded, error, saveLabel = 'Save' }: { dirty: boolean; saving?: boolean; onDiscard: () => void; onSave: () => void; guarded?: boolean; error?: ReactNode; saveLabel?: string }) {
  const { away, confirmed } = useAway()
  if (!dirty) return null
  return (
    <div role="region" aria-label="Unsaved changes" className="settings-fade sticky top-14 z-20 -mx-1 mb-4 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg bg-neutral-100 px-4 py-2 text-sm text-black shadow-lg">
      <b className="font-semibold">Unsaved changes</b>
      {error && <span role="alert" className="text-red-800">{error}</span>}
      <span className="flex-1" />
      <button type="button" disabled={saving} onClick={onDiscard} className="min-h-[36px] rounded-md border border-neutral-400 bg-white px-3 disabled:opacity-50">Discard</button>
      <button type="button" disabled={saving} onClick={onSave} className="inline-flex min-h-[36px] items-center gap-1.5 rounded-md border border-brand bg-brand px-3 font-semibold text-white disabled:opacity-60">
        {saving ? <Spinner /> : guarded && away && !confirmed && <LockIcon />}
        {saving ? 'Saving…' : saveLabel}
      </button>
    </div>
  )
}

/** "What changed" and "What happens next", on a finished flow (§3.7). */
export function Outcome({ changed, next, titles = ['What changed', 'What happens next'] }: { changed: ReactNode[]; next: ReactNode[]; titles?: [string, string] }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <OutcomeCard title={titles[0]} items={changed} />
      <OutcomeCard title={titles[1]} items={next} />
    </div>
  )
}

function OutcomeCard({ title, items }: { title: string; items: ReactNode[] }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      <h3 className="font-semibold">{title}</h3>
      <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
        {items.filter(Boolean).map((item, i) => <li key={i}>{item}</li>)}
      </ul>
    </div>
  )
}

/** A loading page keeps the page's shape (m-states), not a spinner on a blank page. */
export function LoadingRows({ rows = 3 }: { rows?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading" className="divide-y divide-line rounded-xl border border-line bg-surface">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="px-5 py-4">
          <div className="h-3.5 w-36 rounded bg-line-2" />
          <div className="mt-2 h-3 w-64 max-w-[80%] rounded bg-line" />
        </div>
      ))}
    </div>
  )
}
