import type { ReactNode } from 'react'

// The building blocks every settings page is made of: a page header, then
// panels whose rows put a setting's label on the left and its control on
// the right.

export const primaryButton = 'min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50'
export const secondaryButton = 'min-h-[40px] rounded-md border border-line-2 px-3.5 text-sm text-neutral-100 hover:bg-surface-2 disabled:opacity-50'
export const textInput = 'min-h-[40px] rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 placeholder:text-neutral-500'

/** A settings page's title and what it is for, with optional actions on the right. */
export function PageHeader({ title, badge, actions, children }: { title: string; badge?: ReactNode; actions?: ReactNode; children?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0 max-w-2xl flex-1 basis-80">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-[22px] font-semibold tracking-tight">{title}</h2>
          {badge}
        </div>
        {children && <p className="mt-1.5 text-neutral-400">{children}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

/** A titled card. Rows inside it are divided by lines; free content brings its own padding. */
export function Panel({ title, description, actions, tone = 'plain', children }: { title?: ReactNode; description?: ReactNode; actions?: ReactNode; tone?: 'plain' | 'warn'; children?: ReactNode }) {
  const frame = tone === 'warn' ? 'border-orange-900/70 bg-orange-950/25' : 'border-line bg-surface'
  return (
    <section className={`rounded-xl border ${frame}`}>
      {(title || description || actions) && (
        <div className="flex flex-wrap items-start justify-between gap-3 px-5 pb-3 pt-4">
          <div className="min-w-0 flex-1 basis-72">
            {title && <h3 className="text-[15px] font-semibold">{title}</h3>}
            {description && <p className="mt-1 text-sm text-neutral-400">{description}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-3">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  )
}

/** One setting in a panel: label and hint on the left, the control on the right. */
export function Row({ label, hint, htmlFor, controlsClassName = '', children }: { label: ReactNode; hint?: ReactNode; htmlFor?: string; controlsClassName?: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-8 gap-y-3 border-t border-line px-5 py-4 first:border-t-0">
      <div className="min-w-0 flex-1 basis-72">
        {htmlFor ? (
          <label htmlFor={htmlFor} className="block font-medium">
            {label}
          </label>
        ) : (
          <div className="font-medium">{label}</div>
        )}
        {hint && <div className="mt-0.5 text-sm text-neutral-400">{hint}</div>}
      </div>
      <div className={`flex min-w-0 max-w-full flex-wrap items-center gap-2 ${controlsClassName}`}>{children}</div>
    </div>
  )
}

/** A yes/no setting: the whole row toggles the checkbox on the right. */
export function CheckRow({ label, hint, checked, disabled, onChange }: { label: ReactNode; hint?: ReactNode; checked: boolean; disabled?: boolean; onChange: (on: boolean) => void }) {
  return (
    <label className="flex cursor-pointer flex-wrap items-center justify-between gap-x-8 gap-y-3 border-t border-line px-5 py-4 first:border-t-0">
      <span className="min-w-0 flex-1 basis-72">
        <span className="block font-medium">{label}</span>
        {hint && <span className="mt-0.5 block text-sm text-neutral-400">{hint}</span>}
      </span>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} className="mx-1.5 h-[18px] w-[18px] accent-accent" />
    </label>
  )
}

/** A read-only fact in a panel's <dl>: what it is on the left, its value on the right. */
export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap gap-x-6 gap-y-1 border-t border-line px-5 py-3 text-sm">
      <dt className="w-44 shrink-0 text-neutral-400">{label}</dt>
      <dd className="min-w-0 flex-1 basis-64">{children}</dd>
    </div>
  )
}

/** A short state label: green for on or ready, orange for something to fix. */
export function StatusPill({ tone, children }: { tone: 'ok' | 'warn' | 'off'; children: ReactNode }) {
  const look = {
    ok: 'border-emerald-900 bg-emerald-950/60 text-emerald-300',
    warn: 'border-orange-900 bg-orange-950/50 text-warn',
    off: 'border-line-2 text-neutral-400',
  }[tone]
  const dot = { ok: 'bg-emerald-400', warn: 'bg-warn', off: 'bg-neutral-500' }[tone]
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium ${look}`}>
      <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${dot}`} />
      {children}
    </span>
  )
}
