import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, unwrap } from '../../api/client'
import { useAuth } from '../../authContext'
import { DevicesSection } from '../../components/DevicesSection'
import { PageHeader, Panel, primaryButton, textInput } from '../../components/SettingsPanel'

/** Settings › Account & devices: this person's password and everywhere they are signed in. */
export function AccountSettings() {
  const auth = useAuth()
  return (
    <>
      <PageHeader title="Account & devices">
        Signed in as <span className="font-medium text-neutral-100">{auth.user?.name}</span>
        {auth.user?.isAdmin && ' · admin'}. Your password and everywhere you are signed in.
      </PageHeader>
      <div className="space-y-6">
        <ChangePassword />
        <DevicesSection />
      </div>
    </>
  )
}

function ChangePassword() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const change = useMutation({
    mutationFn: async () => unwrap(await api.PUT('/api/auth/password', { body: { current, new: next } })),
    onSuccess: () => {
      setCurrent('')
      setNext('')
    },
  })
  return (
    <Panel title="Password" description="Changing it signs out everything except this browser.">
      <form
        className="flex flex-wrap items-end gap-3 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          change.mutate()
        }}
      >
        <label className="min-w-0 flex-1 basis-52">
          <span className="mb-1.5 block text-sm text-neutral-400">Current password</span>
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} required autoComplete="current-password" className={`${textInput} w-full`} />
        </label>
        <label className="min-w-0 flex-1 basis-52">
          <span className="mb-1.5 block text-sm text-neutral-400">New password</span>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            placeholder="8 or more characters"
            required
            minLength={8}
            autoComplete="new-password"
            className={`${textInput} w-full`}
          />
        </label>
        <button type="submit" disabled={change.isPending} className={primaryButton}>
          Change password
        </button>
      </form>
      {change.isSuccess && <p className="px-5 pb-4 text-sm text-emerald-400">Changed. Other devices were signed out.</p>}
      {change.isError && <p className="px-5 pb-4 text-sm text-red-400">{change.error.message}</p>}
    </Panel>
  )
}
