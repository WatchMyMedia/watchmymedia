import { useEffect, useId, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../../api/settings'
import { useAuth } from '../../authContext'
import { PageHeader, Panel, primaryButton, secondaryButton, StatusPill } from '../../components/SettingsPanel'
import { SignInProtectionSection } from '../../components/SettingsSections'
import { ServerOnly } from './SettingsLayout'

export function SecuritySettings() {
  return (
    <>
      <PageHeader title="Security">Protect sign-in and choose where admins can change server settings.</PageHeader>
      <ServerOnly>
        <div className="space-y-8">
          <section aria-labelledby="sign-in-heading" className="space-y-4">
            <div><h3 id="sign-in-heading" className="text-lg font-semibold">Sign-in protection</h3>
              <p className="mt-1.5 text-sm text-neutral-400">Cloudflare Turnstile asks everyone who signs in or finishes setup to pass a quick check that they’re not a bot, before their password is tried. While it’s on, TV and phone apps sign in by linking with a code rather than a password.</p></div>
            <SignInProtectionSection />
          </section>
          <RemoteAdminSection />
        </div>
      </ServerOnly>
    </>
  )
}

function RemoteAdminSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const auth = useAuth()
  const id = useId()
  const [confirming, setConfirming] = useState(false)
  const value = settings.data?.remoteAdmin
  const enabled = value?.enabled ?? false
  return (
    <section aria-labelledby={`${id}-heading`} className="space-y-4">
      <div><h3 id={`${id}-heading`} className="text-lg font-semibold">Admin settings away from home</h3>
        <p className="mt-1.5 text-sm text-neutral-400">Admins can manage this server wherever they sign in over HTTPS. First-run setup still needs your home network or tailnet.</p></div>
      <Panel>
        <div className="flex items-center justify-between gap-4 px-5 py-4">
          <label htmlFor={`${id}-switch`} className="min-w-0 font-medium">Let admins change server settings from anywhere, over HTTPS</label>
          <button id={`${id}-switch`} type="button" role="switch" aria-checked={enabled} aria-describedby={`${id}-help`} disabled={!value || value.fromConfig || update.isPending}
            onClick={() => enabled ? setConfirming(true) : update.mutate({ remoteAdmin: true })}
            className={`relative h-8 w-[52px] shrink-0 rounded-full border disabled:cursor-default disabled:opacity-60 ${enabled ? 'border-accent bg-accent' : 'border-line-2 bg-surface-2'}`}>
            <span className={`absolute top-[3px] h-6 w-6 rounded-full ${enabled ? 'left-[23px] bg-ground' : 'left-[3px] bg-neutral-300'}`} />
          </button>
        </div>
        {value?.fromConfig && <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">Set by <code className="text-neutral-200">network.remote_admin</code> in config.yaml. Change it there and restart the server.</p>}
        <div id={`${id}-help`} className="border-t border-line px-5 py-4 text-sm text-neutral-400">
          {enabled ? <>
            <p className="font-medium text-neutral-200">Away from home, these ask for the admin’s password again</p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>Adding an admin, resetting a password or deleting an account</li>
              <li>Making or downloading a backup</li>
              <li>Changing sign-in protection</li>
              <li>Adding a library or changing its folders</li>
              <li>Signing in to Plex, or connecting or disconnecting a Plex server</li>
              <li>Adding, testing or moving a Live TV tuner</li>
              <li>Connecting, changing, testing or removing Radarr, Sonarr or Seerr</li>
            </ul>
            <p className="mt-3">The password lasts 10 minutes in that browser. Each of these changes made away from home, and each password entered for one, is written to the server’s log with the address it came from.</p>
          </> : <p>Admins away from home see Activity only. Turning this on works only from your home network or tailnet.</p>}
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-3">
          <StatusPill tone={enabled ? 'ok' : 'off'}>{enabled ? 'On' : 'Off'}</StatusPill>
          <p className="text-sm text-neutral-400">{auth.onAdminNetwork ? 'This browser is on your home network.' : 'This browser is away from home, over HTTPS.'}</p>
        </div>
        {update.isError && <p role="alert" className="px-5 pb-4 text-sm text-warn">{update.error.message}</p>}
      </Panel>
      {confirming && <TurnOffDialog away={!auth.onAdminNetwork} pending={update.isPending} error={update.error?.message} onClose={() => { setConfirming(false); update.reset() }}
        onConfirm={() => update.mutate({ remoteAdmin: false }, { onSuccess: () => setConfirming(false) })} />}
    </section>
  )
}

function TurnOffDialog({ away, pending, error, onClose, onConfirm }: { away: boolean; pending: boolean; error?: string; onClose: () => void; onConfirm: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const id = useId()
  useEffect(() => {
    const modal = dialog.current!, trigger = document.activeElement
    modal.showModal()
    return () => { modal.close(); if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus() }
  }, [])
  return <dialog ref={dialog} aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} onCancel={(e) => { e.preventDefault(); if (!pending) onClose() }}
    className="mx-auto my-6 max-h-[calc(100%-3rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-xl border border-line-2 bg-surface p-6 text-neutral-100 backdrop:bg-black/80 sm:my-10">
    <h2 id={`${id}-title`} className="text-xl font-semibold">Turn off admin settings away from home?</h2>
    <p id={`${id}-description`} className="mt-3 text-sm text-neutral-400">{away ? 'You’re away from home, so this closes server settings for you too, as soon as it’s off. An admin can turn it back on from your home network or tailnet.' : 'You won’t be able to change server settings away from home until an admin turns this back on from your home network or tailnet.'}</p>
    {error && <p role="alert" className="mt-3 text-sm text-warn">{error}</p>}
    <div className="mt-5 flex justify-end gap-3"><button type="button" autoFocus className={secondaryButton} disabled={pending} onClick={onClose}>Cancel</button>
      <button type="button" className={primaryButton} disabled={pending} onClick={onConfirm}>{pending ? 'Turning off…' : 'Turn off'}</button></div>
  </dialog>
}
