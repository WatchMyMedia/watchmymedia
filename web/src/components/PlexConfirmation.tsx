import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { primaryButton, secondaryButton } from './SettingsPanel'
import { plexReason } from '../lib/plex'

/** Native modal contains keyboard focus, stops repeated submissions and returns to its trigger. */
export function PlexConfirmation({
  title,
  description,
  confirm,
  action,
  onDone,
  onClose,
  disabled = false,
  children,
}: {
  title: string
  description: string
  confirm: string
  action: () => Promise<unknown>
  onDone: () => void
  onClose: () => void
  disabled?: boolean
  children?: ReactNode
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  const id = useId()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  useEffect(() => {
    const modal = dialog.current!
    const trigger = document.activeElement
    modal.showModal()
    cancel.current?.focus()
    return () => {
      modal.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus()
    }
  }, [])
  return (
    <dialog
      ref={dialog}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-description`}
      aria-busy={pending}
      onCancel={(e) => {
        e.preventDefault()
        if (!pending) onClose()
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && !pending) onClose()
      }}
      className="mx-auto my-6 max-h-[calc(100%-3rem)] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-xl border border-line-2 bg-surface p-6 text-neutral-100 shadow-xl backdrop:bg-black/80 sm:my-10"
    >
      <h2 id={`${id}-title`} className="text-xl font-semibold">
        {title}
      </h2>
      <p id={`${id}-description`} className="mt-3 text-sm text-neutral-300">
        {description}
      </p>
      {children}
      {error != null && (
        <div className="mt-4">
          <p role="alert" className="rounded-lg border border-orange-900 bg-orange-950/25 p-3 text-sm text-orange-200">
            {error instanceof Error && /^[a-z_]+$/.test(error.message)
              ? plexReason(error.message, 'Couldn’t finish this action. Close this dialog, refresh the page and try again.')
              : 'Couldn’t finish this action. Close this dialog, refresh the page and try again.'}
          </p>
        </div>
      )}
      <form
        className="mt-6 flex flex-wrap justify-end gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (pending || disabled) return
          setPending(true)
          setError(null)
          void action()
            .then(onDone)
            .catch(setError)
            .finally(() => setPending(false))
        }}
      >
        <button ref={cancel} type="button" className={secondaryButton} disabled={pending} onClick={onClose}>
          Cancel
        </button>
        <button type="submit" className={primaryButton} disabled={pending || disabled}>
          {pending ? 'Please wait…' : confirm}
        </button>
      </form>
    </dialog>
  )
}
