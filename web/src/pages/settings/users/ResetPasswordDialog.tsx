import { useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../../../api/client'
import { visibleConfirmationError, withConfirm } from '../../../api/withConfirm'
import { Button, GuardedCaption, Message, fieldInput } from '../../../components/settings/Frame'
import { MIN_PASSWORD, passwordProblem, resetRefusal } from '../../../lib/usersSettings'

/**
 * The approved Reset password dialog. A native modal, so the password
 * dialog away from home opens on top of it, and Cancel there leaves this
 * one as it was. Focus returns to Reset password when it closes.
 */
export function ResetPasswordDialog({ user, onClose, onSuccess }: { user: { id: number; name: string }; onClose: () => void; onSuccess: () => void }) {
  const qc = useQueryClient()
  const dialog = useRef<HTMLDialogElement>(null)
  const passwordInput = useRef<HTMLInputElement>(null)
  const id = useId()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [problem, setProblem] = useState<{ field: 'password' | 'again'; text: string } | null>(null)
  const reset = useMutation({
    mutationFn: async () =>
      withConfirm(async () => unwrap(await api.PUT('/api/users/{id}/password', { params: { path: { id: user.id } }, body: { password } })), 'reset this account’s password'),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions'] })
      onSuccess()
    },
  })
  useEffect(() => {
    const modal = dialog.current!
    const trigger = document.activeElement
    modal.showModal()
    passwordInput.current?.focus()
    return () => {
      modal.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus()
    }
  }, [])
  const close = () => { if (!reset.isPending) onClose() }
  return (
    <dialog
      ref={dialog}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-description`}
      onCancel={(e) => { e.preventDefault(); close() }}
      onClick={(e) => { if (e.target === e.currentTarget) close() }}
      className="mx-auto my-6 max-h-[calc(100%-3rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-xl border border-line-2 bg-surface p-0 text-neutral-100 shadow-xl backdrop:bg-black/80 sm:my-10 sm:max-h-[calc(100%-5rem)]"
    >
      <div className="p-6">
        <div className="flex items-start justify-between gap-3">
          <h2 id={`${id}-title`} className="min-w-0 text-xl font-bold break-words">Reset password for {user.name}</h2>
          <button type="button" onClick={close} disabled={reset.isPending} aria-label="Close"
            className="-mt-2 -mr-2 min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 disabled:opacity-50">✕</button>
        </div>
        <p id={`${id}-description`} className="mt-2 text-sm text-neutral-400">
          {user.name} is signed out on every device and signs in again with the new password. Their watch history and settings stay.
        </p>
        <form
          className="mt-5"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            if (reset.isPending) return
            const short = passwordProblem(password)
            if (short) return setProblem({ field: 'password', text: short })
            if (password !== confirmation) return setProblem({ field: 'again', text: 'The two passwords don’t match. Type the same password in both boxes.' })
            setProblem(null)
            reset.mutate()
          }}
        >
          <label htmlFor={`${id}-new`} className="block text-sm font-semibold">New password</label>
          <p id={`${id}-hint`} className="mt-0.5 text-[13px] text-neutral-400">{MIN_PASSWORD} characters or more.</p>
          <input
            id={`${id}-new`}
            ref={passwordInput}
            type="password"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setProblem(null) }}
            maxLength={256}
            autoComplete="new-password"
            disabled={reset.isPending}
            aria-invalid={problem?.field === 'password' || undefined}
            aria-describedby={`${id}-hint${problem?.field === 'password' ? ` ${id}-problem` : ''}`}
            className={`${fieldInput} mt-1.5`}
          />
          {problem?.field === 'password' && <p id={`${id}-problem`} className="mt-1.5 text-sm text-red-300">{problem.text}</p>}
          <label htmlFor={`${id}-again`} className="mt-4 block text-sm font-semibold">Type it again</label>
          <input
            id={`${id}-again`}
            type="password"
            value={confirmation}
            onChange={(e) => { setConfirmation(e.target.value); setProblem(null) }}
            maxLength={256}
            autoComplete="new-password"
            disabled={reset.isPending}
            aria-invalid={problem?.field === 'again' || undefined}
            aria-describedby={problem?.field === 'again' ? `${id}-problem` : undefined}
            className={`${fieldInput} mt-1.5`}
          />
          {problem?.field === 'again' && <p id={`${id}-problem`} role="alert" className="mt-1.5 text-sm text-red-300">{problem.text}</p>}
          {reset.isError && visibleConfirmationError(reset.error) && <Message tone="err" className="mt-4" focus>{resetRefusal(reset.error, user.name)}</Message>}
          <div className="mt-5 flex flex-wrap items-center justify-end gap-3 border-t border-line pt-4">
            <GuardedCaption className="basis-full sm:mr-auto sm:basis-auto" />
            <Button onClick={close} disabled={reset.isPending} className="flex-1 sm:flex-none">Cancel</Button>
            <Button type="submit" variant="primary" guarded busy={reset.isPending} busyLabel="Resetting…" className="flex-1 sm:flex-none">Reset password</Button>
          </div>
        </form>
      </div>
    </dialog>
  )
}
