import { useId, useState } from 'react'
import { canUseName, failureWords, MAX_NAME, nameProblem, type Watchlist } from '../../lib/watchlists'
import { Dialog } from '../Dialog'
import { nameInput } from './styles'

const b1 = 'inline-flex min-h-11 items-center justify-center rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50 max-sm:flex-1'
const b2 = 'inline-flex min-h-11 items-center justify-center rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 max-sm:flex-1'

/**
 * New watchlist and Rename: the picker's Name field in a dialog. The
 * button waits for a name that is new; one already used says so.
 */
export function WatchlistNameDialog({ heading, confirmLabel, lists, self, busy, error, onSubmit, onClose }: {
  heading: string
  confirmLabel: string
  lists: readonly Pick<Watchlist, 'id' | 'name'>[]
  self?: Pick<Watchlist, 'id' | 'name'>
  busy: boolean
  error: unknown
  onSubmit: (name: string) => void
  onClose: () => void
}) {
  const id = useId()
  const [name, setName] = useState(self?.name ?? '')
  const taken = nameProblem(name, lists, self?.id)
  const problem = taken ?? (error ? failureWords(error, 'Couldn’t save the name.') : undefined)
  return (
    <Dialog title={heading} onClose={onClose} width="max-w-md">
      <form
        className="px-6 pb-6 pt-3"
        onSubmit={(e) => {
          e.preventDefault()
          if (canUseName(name, lists, self) && !busy) onSubmit(name)
        }}
      >
        <label htmlFor={`${id}-name`} className="block text-[13px] text-neutral-400">
          Name
        </label>
        <input
          id={`${id}-name`}
          autoFocus
          value={name}
          maxLength={MAX_NAME}
          autoComplete="off"
          aria-invalid={!!taken}
          aria-describedby={problem ? `${id}-err` : undefined}
          onChange={(e) => setName(e.target.value)}
          className={nameInput}
        />
        {problem && (
          <p id={`${id}-err`} role="alert" className="mt-2 text-[13px] leading-snug text-red-300">
            {problem}
          </p>
        )}
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <button type="button" onClick={onClose} className={b2}>
            Cancel
          </button>
          <button type="submit" disabled={!canUseName(name, lists, self) || busy} className={b1}>
            {confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
