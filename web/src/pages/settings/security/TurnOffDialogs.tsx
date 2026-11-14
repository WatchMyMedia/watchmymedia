import { useUpdateSettings } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { errorText } from '../../../components/settings/hooks'
import { turnOffRefusal } from '../../../lib/securitySettings'

/**
 * Turn sign-in protection off (board sec-ts-off). Asks for the password away
 * from home. `onDone` runs even if the page under it has moved on, as Try
 * it's does once protection is off.
 */
export function TurnOffProtectionDialog({ onClose, onStart, onDone, onFailed }: { onClose: () => void; onStart?: () => void; onDone: () => void; onFailed?: () => void }) {
  const update = useUpdateSettings()
  return (
    <ConfirmDialog
      title="Turn off sign-in protection?"
      lede="The sign-in page stops showing the Cloudflare check. People already signed in aren’t affected. You can turn it on again any time."
      confirmLabel="Turn off"
      guarded
      busy={update.isPending}
      error={update.isError && visibleConfirmationError(update.error) ? turnOffRefusal(update.error) : undefined}
      onConfirm={() => {
        onStart?.()
        update.mutateAsync({ turnstile: { siteKey: '' } }).then(onDone, () => onFailed?.())
      }}
      onClose={onClose}
    />
  )
}

/**
 * Turn admin settings away from home off (board sec-ra-off). Away from
 * home it says first that it closes server settings for this admin too, and
 * it asks for the password.
 */
export function TurnOffRemoteAdminDialog({ away, onClose }: { away: boolean; onClose: () => void }) {
  const update = useUpdateSettings()
  return (
    <ConfirmDialog
      title="Turn off admin settings away from home?"
      confirmLabel="Turn off"
      guarded
      busy={update.isPending}
      error={update.isError && visibleConfirmationError(update.error) ? errorText(update.error) : undefined}
      onConfirm={() => update.mutate({ remoteAdmin: false }, { onSuccess: onClose })}
      onClose={onClose}
    >
      {away && <p className="text-sm font-semibold text-amber-300">You’re away from home, so this closes server settings for you too, straight away.</p>}
      <p className={`text-sm text-neutral-300 ${away ? 'mt-3' : ''}`}>You won’t be able to change server settings away from home until an admin turns this back on from your home network or tailnet.</p>
    </ConfirmDialog>
  )
}
