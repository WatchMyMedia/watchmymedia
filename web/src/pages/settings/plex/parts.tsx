import type { ReactNode } from 'react'
import { FlowFrame } from '../../../components/settings/Flow'
import { Button, LoadingRows, Message } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import type { FlowStep } from '../../../lib/flowSteps'
import type { PlexFix, PlexMessage } from '../../../lib/plexSettings'
import type { Crumb } from '../../../lib/settingsRoutes'
import { plexHref } from './state'

/** Fixes that are a place to go; the rest are the caller's to run. */
const PLACES: Partial<Record<PlexFix, string>> = {
  exit: plexHref(),
  link: plexHref('link'),
  reconnect: plexHref('connect'),
  'see-import': plexHref('import', 'import'),
  restore: plexHref('restore'),
}

/**
 * One of the tab's messages, with its fix as a button. `on` runs the fixes
 * that aren't a place to go (Start again, Try again, Check again…); a fix
 * with neither is left off.
 */
export function PlexNote({ message, on, focus, busy, className }: {
  message: PlexMessage | null | undefined
  on?: Partial<Record<PlexFix, () => void>>
  focus?: boolean
  busy?: boolean
  className?: string
}) {
  const go = useGo()
  if (!message) return null
  const fix = message.fix
  const run = fix && (on?.[fix.do] ?? (PLACES[fix.do] ? () => go(PLACES[fix.do]!) : undefined))
  return (
    <Message
      tone={message.tone}
      title={message.title}
      focus={focus}
      className={className}
      actions={fix && run ? <Button small guarded={fix.do === 'reconnect'} busy={busy} onClick={run}>{fix.label}</Button> : undefined}
    >
      {message.body}
    </Message>
  )
}

/** A flow while the connection loads, when it can't be read, or when there's
 * no connection to work on: the frame stays, with what to do instead. */
export function FlowWaiting({ crumbs, steps, current, title, error, retry, missing }: {
  crumbs: Crumb[]
  steps?: readonly FlowStep[]
  current?: string
  title: ReactNode
  error?: boolean
  retry?: () => void
  /** No Plex server is connected, so this task can't start. */
  missing?: boolean
}) {
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={steps}
      current={current ?? steps?.[0]?.key}
      title={title}
      next={missing ? { label: 'Connect Plex', href: plexHref('connect'), guarded: true } : undefined}
    >
      {missing ? (
        <Message tone="info" title="Plex isn’t connected">Connect your Plex server first. Nothing has been imported.</Message>
      ) : error ? (
        <Message tone="err" title="Couldn’t check your Plex connection" actions={retry && <Button small onClick={retry}>Try again</Button>}>
          Nothing was changed. Check this server’s connection, then try again.
        </Message>
      ) : (
        <LoadingRows rows={2} />
      )}
    </FlowFrame>
  )
}

/** Counts in a grid of tiles: three across, two on phones. */
export function Metrics({ items }: { items: [number, string][] }) {
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {items.map(([n, label]) => (
        <div key={label} className="flex flex-col-reverse rounded-[10px] border border-line bg-surface px-4 py-3">
          <dt className="mt-0.5 text-[13px] text-neutral-400">{label}</dt>
          <dd className="text-2xl font-semibold tabular-nums">{n.toLocaleString()}</dd>
        </div>
      ))}
    </dl>
  )
}

/** A card with a short list, as the boards' "What happens" cards. */
export function ListCard({ title, items, children }: { title?: ReactNode; items?: ReactNode[]; children?: ReactNode }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      {title && <h3 className="font-semibold">{title}</h3>}
      {items && (
        <ul className={`${title ? 'mt-2' : ''} list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500`}>
          {items.filter(Boolean).map((item, i) => <li key={i}>{item}</li>)}
        </ul>
      )}
      {children}
    </div>
  )
}

/** Something running, with a spinner and an optional bar. */
export function Working({ title, value, children }: { title: ReactNode; value?: number; children?: ReactNode }) {
  return (
    <div role="status" className="rounded-xl border border-line bg-surface p-5">
      <h3 className="flex items-center gap-2.5 font-semibold">
        <span aria-hidden="true" className="inline-block h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-accent border-r-transparent motion-reduce:animate-none" />
        {title}
      </h3>
      <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-line">
        {value === undefined ? (
          <div className="h-full w-1/3 animate-pulse rounded-full bg-accent motion-reduce:animate-none" />
        ) : (
          <div className="h-full rounded-full bg-accent transition-[width] motion-reduce:transition-none" style={{ width: `${Math.round(Math.min(1, Math.max(0, value)) * 100)}%` }} />
        )}
      </div>
      {children && <div className="mt-3 text-sm text-neutral-400">{children}</div>}
    </div>
  )
}

/**
 * A table on wide screens and one block per row on phones, each value with
 * its column's name. `cells` are in the columns' order.
 */
export function RowTable({ columns, rows, label }: { columns: string[]; rows: { key: string | number; cells: ReactNode[] }[]; label: string }) {
  return (
    <div className="overflow-hidden rounded-xl border border-line bg-surface">
      <table className="w-full text-sm" aria-label={label}>
        <thead className="hidden sm:table-header-group">
          <tr className="border-b border-line text-left text-xs font-semibold tracking-wider text-neutral-400 uppercase">
            {columns.map((c) => <th key={c} scope="col" className="px-5 py-2.5 font-semibold">{c}</th>)}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((row) => (
            <tr key={row.key} className="block px-5 py-3.5 sm:table-row sm:p-0">
              {row.cells.map((cell, i) => (
                <td key={i} className={`block py-1 align-middle sm:table-cell sm:px-5 sm:py-3.5 ${i === 0 ? '' : 'sm:w-[34%]'}`}>
                  {i > 0 && <span className="mb-1 block text-xs font-semibold tracking-wider text-neutral-500 uppercase sm:hidden">{columns[i]}</span>}
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
