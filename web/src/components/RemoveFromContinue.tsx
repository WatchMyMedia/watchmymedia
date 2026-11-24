import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { components } from '../api/schema'
import { hideFromContinueWatching, showInContinueWatching } from '../api/watch'
import { continueTarget, hiddenWords } from '../lib/continueWatching'
import { showToast } from '../lib/toast'
import { CloseIcon } from './icons'

type ContinueItem = components['schemas']['ContinueItem']

/**
 * The × beside a Continue watching item: it leaves the row at once and its
 * place is kept, with Undo in the message at the foot of the window.
 * name is what the button and the message call it ("Night Shift Diner").
 */
export function RemoveFromContinue({ item, name, className = '' }: { item: ContinueItem; name: string; className?: string }) {
  const qc = useQueryClient()
  const [busy, setBusy] = useState(false)
  const target = continueTarget(item)
  if (!target) return null
  const remove = () => {
    setBusy(true)
    hideFromContinueWatching(qc, target)
      .then(() =>
        showToast({
          text: hiddenWords(name),
          undo: () => void showInContinueWatching(qc, target).catch((e: Error) => showToast({ text: `${name} couldn’t come back: ${e.message}` })),
        }),
      )
      .catch((e: Error) => showToast({ text: `${name} couldn’t be removed: ${e.message}` }))
      .finally(() => setBusy(false))
  }
  return (
    <button type="button" disabled={busy} onClick={remove} aria-label={`Remove ${name} from Continue watching`} title="Remove from this row. Your place is kept." className={`ib ib-sm ${className}`}>
      <CloseIcon size={16} />
    </button>
  )
}
