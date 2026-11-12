import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link as RouterLink, useNavigate } from '@tanstack/react-router'
import { api, unwrap } from '../api/client'
import { useAuth } from '../authContext'
import { APP_LINK, onAppPlatform, pairingKind } from '../lib/pairing'

const normalize = (code: string) => code.toUpperCase().replace(/[\s-]/g, '')

/**
 * Approves a device that is showing a pairing code: the device signs in as
 * whoever approves it here. Reached from the QR code on a TV, from the phone
 * and tablet apps' "Open the link page" (/link?code=...), or by typing the code.
 */
export function LinkDevice({ code: initial }: { code?: string }) {
  const auth = useAuth()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const code = normalize(initial ?? '')
  const [typed, setTyped] = useState(code)
  const ready = code.length === 6
  const pairing = useQuery({
    queryKey: ['pairing', code],
    enabled: ready,
    retry: false,
    queryFn: async () => unwrap(await api.GET('/api/auth/pair/{code}', { params: { path: { code } } })),
  })
  const approve = useMutation({
    mutationFn: async () => unwrap(await api.POST('/api/auth/pair/{code}/approve', { params: { path: { code } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['sessions'] }),
  })
  const device = pairing.data
  const deviceLine = device ? [device.product, device.version, device.ip && `from ${device.ip}`].filter(Boolean).join(' · ') : ''
  const kind = pairingKind(device?.platform)
  const noun = kind === 'tv' ? 'TV' : 'device'

  return (
    <div className="mx-auto max-w-md space-y-6 py-6">
      <div>
        <h1 className="text-2xl font-semibold">Link a device</h1>
        <p className="mt-1 text-sm text-neutral-400">
          Enter the code your TV or the WatchMyMedia app shows. It signs in as you, {auth.user?.name}, and shows up under Signed-in devices in Settings.
        </p>
      </div>

      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          approve.reset()
          void navigate({ to: '/link', search: { code: normalize(typed) } })
        }}
      >
        <input
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          placeholder="ABC234"
          maxLength={9}
          autoFocus={!ready}
          autoCapitalize="characters"
          autoComplete="one-time-code"
          spellCheck={false}
          aria-label="Code shown on the device"
          className="min-h-[44px] w-full rounded-md border border-line-2 bg-ground px-3 font-mono text-xl uppercase tracking-[0.3em]"
        />
        <button type="submit" disabled={normalize(typed).length !== 6} className="min-h-[44px] shrink-0 rounded-md border border-line-2 px-4 text-sm hover:bg-surface-2 disabled:opacity-50">
          Find
        </button>
      </form>

      {ready && pairing.isPending && <p className="text-sm text-neutral-500">Looking for {code}…</p>}
      {ready && pairing.isError && <p className="rounded-md border border-line-2 bg-surface px-3 py-2 text-sm text-warn">{pairing.error.message}</p>}

      {device && !approve.isSuccess && (
        <div className="space-y-4 rounded-xl border border-line bg-surface p-5">
          <div>
            <div className="text-xs uppercase tracking-wide text-neutral-500">Waiting to sign in</div>
            <div className="mt-1 text-lg font-semibold">{device.name || 'Unnamed device'}</div>
            {deviceLine && <div className="text-sm text-neutral-400">{deviceLine}</div>}
          </div>
          {device.approved ? (
            <>
              <p className="text-sm text-neutral-300">This device has already been approved.</p>
              {kind === 'app' && <BackToApp platform={device.platform} name={device.name} />}
            </>
          ) : (
            <div className="flex flex-wrap items-center gap-3">
              <button
                type="button"
                disabled={approve.isPending}
                onClick={() => approve.mutate()}
                className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50"
              >
                Sign in as {auth.user?.name}
              </button>
              <span className="text-xs text-neutral-500">
                Only approve a {noun} you can see showing {device.code}.
              </span>
            </div>
          )}
          {approve.isError && <p className="text-sm text-red-400">{approve.error.message}</p>}
        </div>
      )}

      {approve.isSuccess && (
        <div className="space-y-3 rounded-xl border border-line bg-surface p-5">
          <p className="text-lg font-semibold">Linked</p>
          <p className="text-sm text-neutral-300">
            {approve.data.name || `The ${noun}`} is signing in now. You can sign it out any time from Settings.
          </p>
          {kind === 'app' && <BackToApp platform={approve.data.platform} name={approve.data.name} />}
          <RouterLink to="/settings/account" className="inline-block text-sm text-accent hover:underline">
            Signed-in devices
          </RouterLink>
        </div>
      )}
    </div>
  )
}

/**
 * After a phone or tablet is approved the app signs itself in, but this page
 * is the web app in a browser, which is easy to mistake for it and carry on
 * in. Send the person back: with a button when the app could be on this
 * device, by name otherwise.
 */
function BackToApp({ platform, name }: { platform?: string; name: string }) {
  if (!onAppPlatform(platform, navigator.userAgent, navigator.maxTouchPoints)) {
    return <p className="text-sm text-neutral-300">Carry on in the WatchMyMedia app on {name || 'that device'}.</p>
  }
  return (
    <div className="space-y-3">
      <p className="text-sm text-neutral-300">Go back to the WatchMyMedia app to carry on. This page is the web version, in your browser.</p>
      <a href={APP_LINK} className="inline-flex min-h-[44px] items-center rounded-md btn-primary px-4 text-sm font-semibold">
        Back to the app
      </a>
    </div>
  )
}
