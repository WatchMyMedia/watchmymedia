import type { ReactNode } from 'react'
import { Spinner } from '../../../components/settings/Frame'
import type { RowSave } from './hooks'

/** A row's result: Saving…, ✓ Saved and what it did, or why it went back. */
export function RowNote({ state, idle }: { state?: RowSave<unknown>; idle?: ReactNode }) {
  if (state?.status === 'saving') return <span role="status" className="inline-flex items-center gap-1.5 text-sm text-neutral-400"><Spinner />Saving…</span>
  if (state?.status === 'saved') return <span role="status" className="settings-fade text-sm text-emerald-300">✓ Saved. {state.message}</span>
  if (state?.status === 'failed') return <span role="alert" className="settings-fade text-sm text-red-300">{state.message}</span>
  return idle ? <span className="text-sm text-neutral-400">{idle}</span> : null
}

