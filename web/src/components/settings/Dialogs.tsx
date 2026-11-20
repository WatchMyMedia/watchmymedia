import { useId, useState, type ReactNode } from 'react'
import { useBlocker } from '@tanstack/react-router'
import { Dialog } from '../Dialog'
import { shouldAskToLeave } from '../../lib/drafts'
import { Button, GuardedCaption, Message, fieldInput } from './Frame'

/**
 * A deliberate remove or a choice whose cost should be seen first:
 * what Stays, what is Deleted and what Stops,
 * with real numbers where the server has them. `typeToConfirm` asks for a
 * name to be typed before the button works.
 */
export function ConfirmDialog({
  title, lede, stays, deleted, stops, typeToConfirm, confirmLabel, cancelLabel = 'Cancel', danger = true, guarded, busy, error, onConfirm, onClose, children,
}: {
  title: ReactNode
  lede?: ReactNode
  stays?: ReactNode[]
  deleted?: ReactNode[]
  stops?: ReactNode[]
  typeToConfirm?: string
  confirmLabel: ReactNode
  cancelLabel?: string
  danger?: boolean
  guarded?: boolean
  busy?: boolean
  error?: ReactNode
  onConfirm: () => void
  onClose: () => void
  children?: ReactNode
}) {
  const id = useId()
  const [typed, setTyped] = useState('')
  const matches = !typeToConfirm || typed.trim() === typeToConfirm.trim()
  const close = () => { if (!busy) onClose() }
  return (
    <Dialog title={title} onClose={close} width="max-w-lg">
      <form className="px-6 pt-3 pb-6" onSubmit={(e) => { e.preventDefault(); if (matches && !busy) onConfirm() }}>
        {lede && <p className="text-sm text-neutral-300">{lede}</p>}
        <div className="mt-4 space-y-3">
          {deleted && deleted.length > 0 && <Kept tone="err" label="Deleted" items={deleted} />}
          {stops && stops.length > 0 && <Kept tone="warn" label="Stops" items={stops} />}
          {stays && stays.length > 0 && <Kept tone="ok" label="Stays" items={stays} />}
        </div>
        {children}
        {typeToConfirm && (
          <div className="mt-5">
            <label htmlFor={`${id}-type`} className="block text-sm font-semibold">Type <span className="font-mono text-neutral-100">{typeToConfirm}</span> to confirm</label>
            <input id={`${id}-type`} value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" spellCheck={false} className={`${fieldInput} mt-1.5`} />
          </div>
        )}
        {error && <Message tone="err" className="mt-4" focus>{error}</Message>}
        <div className="mt-5 flex flex-wrap items-center justify-end gap-3 border-t border-line pt-4">
          {guarded && <GuardedCaption className="basis-full sm:mr-auto sm:basis-auto" />}
          <Button onClick={close} disabled={busy} className="flex-1 sm:flex-none">{cancelLabel}</Button>
          <Button type="submit" variant={danger ? 'danger' : 'primary'} guarded={guarded} busy={busy} disabled={!matches} className="flex-1 sm:flex-none">{confirmLabel}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function Kept({ tone, label, items }: { tone: 'ok' | 'warn' | 'err'; label: string; items: ReactNode[] }) {
  const look = { ok: 'text-emerald-300', warn: 'text-warn', err: 'text-red-300' }[tone]
  return (
    <div className="rounded-lg border border-line bg-ground/60 px-4 py-3">
      <div className={`text-xs font-bold tracking-wider uppercase ${look}`}>{label}</div>
      <ul className="mt-1.5 list-disc space-y-1 pl-5 text-sm text-neutral-300 marker:text-neutral-600">
        {items.map((item, i) => <li key={i}>{item}</li>)}
      </ul>
    </div>
  )
}

/**
 * The leave check: while `when` is true, leaving this
 * address by Exit, Back, a breadcrumb, the browser's back button or closing
 * the tab asks first. `what` says what isn't saved.
 */
export function LeaveCheck({ when, what = 'What you typed on this step isn’t saved. The steps before it are.' }: { when: boolean; what?: ReactNode }) {
  const blocker = useBlocker({
    shouldBlockFn: ({ current, next }) => shouldAskToLeave(when, current.pathname, next.pathname),
    enableBeforeUnload: when,
    withResolver: true,
  })
  if (blocker.status !== 'blocked') return null
  return (
    <Dialog title="Leave this step?" onClose={() => blocker.reset()} width="max-w-md">
      <div className="px-6 pt-2 pb-6">
        <p className="text-sm text-neutral-300">{what}</p>
        <div className="mt-5 flex justify-end gap-3 border-t border-line pt-4">
          <Button onClick={() => blocker.reset()} className="flex-1 sm:flex-none">Keep editing</Button>
          <Button variant="primary" onClick={() => blocker.proceed()} className="flex-1 sm:flex-none">Leave</Button>
        </div>
      </div>
    </Dialog>
  )
}
