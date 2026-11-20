import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelPlexSignIn,
  connectPlex,
  invalidatePlexState,
  plexConnectionsQuery,
  plexSignInQuery,
  startPlexSignIn,
  type PlexConnection,
  type PlexServer,
  type PlexSignIn,
} from '../../../api/plex'
import { FlowFrame } from '../../../components/settings/Flow'
import { Field, fieldInput, Message } from '../../../components/settings/Frame'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { useGo } from '../../../components/settings/hooks'
import { plexAuthorizationURL } from '../../../lib/plex'
import { CONNECT_STEPS, connectMessage, connectStep, plexLinked, signInMessage } from '../../../lib/plexSettings'
import type { Crumb } from '../../../lib/settingsRoutes'
import { FlowWaiting, ListCard, PlexNote, Working } from './parts'
import { plexCrumbs, plexHref, signInKey, usePlexConnection, useStored } from './state'

/** Connect a Plex server: Sign in → Choose server → Connected.
 * With a server already connected it is Reconnect, which keeps the links. */
export function ConnectFlow({ step }: { step?: string }) {
  const { query, connection } = usePlexConnection()
  if (query.isPending || query.isError)
    return <FlowWaiting crumbs={plexCrumbs('connect')} steps={CONNECT_STEPS} title="Sign in to Plex" error={query.isError} retry={() => void query.refetch()} />
  return <Connect step={step} existing={connection} />
}

function Connect({ step, existing }: { step?: string; existing?: PlexConnection }) {
  const go = useGo()
  const qc = useQueryClient()
  // The words stay those of the task that was opened, after it connects.
  const [reconnect] = useState(!!existing)
  const crumbs = plexCrumbs(reconnect ? 'reconnect' : 'connect')
  // Opening the task starts a new sign-in; a reload keeps the one under way.
  const [signInId, setSignInId] = useStored(signInKey, '', !step)
  const [connected, setConnected] = useState<PlexConnection | null>(null)
  const start = useMutation({
    mutationFn: startPlexSignIn,
    onSuccess: (value) => {
      qc.setQueryData(plexSignInQuery(value.id).queryKey, value)
      setSignInId(value.id)
      // Plex's page opens in a new tab; the waiting card has the link too.
      const url = plexAuthorizationURL(value.authUrl)
      if (url) window.open(url, '_blank', 'noopener,noreferrer')
    },
  })
  const attempt = useQuery(plexSignInQuery(signInId))
  const signIn = signInId ? attempt.data : undefined
  const current = connectStep({ signedIn: signIn?.status === 'ready', connected: !!connected })
  const loading = !!signInId && attempt.isPending

  useEffect(() => {
    if (!loading && current !== step) go(plexHref('connect', current), { replace: true })
  }, [loading, current, step, go])

  const forget = () => {
    if (signInId) void cancelPlexSignIn(signInId).catch(() => {})
    setSignInId('')
    start.reset()
  }
  const again = () => {
    forget()
    start.mutate()
  }

  if (loading) return <FlowWaiting crumbs={crumbs} steps={CONNECT_STEPS} current="sign-in" title="Sign in to Plex" />

  if (current === 'done' && connected) return <Connected crumbs={crumbs} connection={connected} reconnect={reconnect} />

  if (current === 'server' && signIn)
    return (
      <ChooseServer
        key={signIn.id}
        crumbs={crumbs}
        signIn={signIn}
        onBack={() => {
          forget()
          go(plexHref('connect', 'sign-in'))
        }}
        onConnected={(value) => {
          qc.setQueryData(plexConnectionsQuery.queryKey, { items: [value] })
          invalidatePlexState(qc)
          setSignInId('')
          setConnected(value)
        }}
      />
    )

  const startError = signInMessage(start.error)
  const pollError = signInId ? signInMessage(attempt.error) : null
  const authUrl = plexAuthorizationURL(signIn?.authUrl)
  const waiting = !!signIn && signIn.status === 'pending' && !pollError
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={CONNECT_STEPS}
      current="sign-in"
      title={reconnect ? 'Sign in to Plex again' : 'Sign in to Plex'}
      lede={waiting ? undefined : 'Use the Plex account that owns your server. Signing in happens on Plex’s own page, in a new tab. WatchMyMedia never asks for your Plex password.'}
      skip={signInId ? { label: 'Start again', onClick: again, guarded: true, busy: start.isPending } : undefined}
      next={signInId ? undefined : { label: 'Sign in to Plex', onClick: () => start.mutate(), guarded: true, busy: start.isPending, busyLabel: 'Starting sign-in…' }}
    >
      <div className="space-y-4">
        {waiting ? (
          authUrl ? (
            <Working title="Waiting for you to sign in on Plex">
              <p>The sign-in page opened in a new tab. Didn’t open? Use the button. This page moves on as soon as you’ve signed in.</p>
              <a href={authUrl} target="_blank" rel="noopener noreferrer" className="mt-3 inline-flex min-h-[40px] items-center rounded-md border border-line-2 px-3.5 text-sm text-neutral-100 hover:bg-surface-2">
                Open Plex sign-in ↗
              </a>
            </Working>
          ) : (
            <PlexNote message={signInMessage('bad_sign_in_link')} on={{ 'start-again': again }} focus />
          )
        ) : signInId && pollError ? (
          <PlexNote message={pollError} on={{ 'start-again': again }} focus />
        ) : (
          <ListCard
            title="What happens"
            items={[
              'We ask Plex for a sign-in link and open it in a new tab.',
              'You sign in there and come back to this tab.',
              'This page moves on by itself once Plex says you’re in.',
              reconnect && 'Your links to people and libraries are kept.',
            ]}
          />
        )}
        {startError && <PlexNote message={startError} on={{ 'start-again': () => start.mutate() }} focus />}
      </div>
    </FlowFrame>
  )
}

function preferredAddress(server: PlexServer | undefined) {
  return (server?.addresses.find((a) => a.local) ?? server?.addresses[0])?.url ?? ''
}

function ChooseServer({ crumbs, signIn, onBack, onConnected }: { crumbs: Crumb[]; signIn: PlexSignIn; onBack: () => void; onConnected: (c: PlexConnection) => void }) {
  const servers = signIn.servers ?? []
  const [machine, setMachine] = useState(servers[0]?.machineIdentifier ?? '')
  const selected = servers.find((s) => s.machineIdentifier === machine)
  const [address, setAddress] = useState(preferredAddress(servers[0]))
  const [typing, setTyping] = useState(!servers[0]?.addresses.length)
  const connect = useMutation({ mutationFn: connectPlex, onSuccess: onConnected })
  const name = selected?.name ?? 'your Plex server'
  const message = connectMessage(connect.error, name)
  const submit = () => {
    if (selected && address.trim()) connect.mutate({ signInId: signIn.id, machineIdentifier: selected.machineIdentifier, origin: address.trim() })
  }
  const listed = selected?.addresses.some((a) => a.url === address)
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={CONNECT_STEPS}
      current="server"
      title="Choose your Plex server"
      lede="Signed in to Plex. Pick the server to read watched movies and episodes from, and the address this WatchMyMedia server can reach it at."
      back={{ label: 'Sign in again', onClick: onBack, disabled: connect.isPending }}
      next={servers.length ? {
        label: 'Connect server',
        onClick: submit,
        guarded: true,
        busy: connect.isPending,
        busyLabel: 'Connecting…',
        disabled: !selected || !address.trim(),
        reason: 'Choose a server and an address first.',
      } : undefined}
    >
      <LeaveCheck when={typing && !!address.trim() && !listed && !connect.isSuccess} what="The address you typed isn’t saved. Nothing has been connected." />
      {!servers.length ? (
        <PlexNote message={connectMessage('no_owned_server', name)} on={{ 'sign-in-again': onBack }} focus />
      ) : (
        <div className="space-y-4">
          <div className="grid gap-x-6 sm:grid-cols-2">
            <Field label="Plex server" hint="Servers this account owns.">
              {(props) => (
                <select
                  {...props}
                  className={fieldInput}
                  value={machine}
                  disabled={connect.isPending}
                  onChange={(e) => {
                    const server = servers.find((s) => s.machineIdentifier === e.target.value)
                    setMachine(e.target.value)
                    setAddress(preferredAddress(server))
                    setTyping(!server?.addresses.length)
                    connect.reset()
                  }}
                >
                  {servers.map((s) => <option key={s.machineIdentifier} value={s.machineIdentifier}>{s.name}</option>)}
                </select>
              )}
            </Field>
            <Field
              label="Address"
              hint="Reachable from this server, not from your browser."
              error={message?.field ? message.body : undefined}
            >
              {(props) =>
                typing ? (
                  <input
                    {...props}
                    type="url"
                    inputMode="url"
                    autoComplete="off"
                    spellCheck={false}
                    placeholder="http://192.0.2.10:32400"
                    className={fieldInput}
                    value={address}
                    disabled={connect.isPending}
                    onChange={(e) => { setAddress(e.target.value); connect.reset() }}
                  />
                ) : (
                  <select {...props} className={fieldInput} value={address} disabled={connect.isPending} onChange={(e) => { setAddress(e.target.value); connect.reset() }}>
                    {selected?.addresses.map((a) => <option key={a.url} value={a.url}>{a.url} · {a.local ? 'local' : 'remote'}</option>)}
                  </select>
                )
              }
            </Field>
          </div>
          {!!selected?.addresses.length && (
            <p className="text-sm">
              <button
                type="button"
                className="text-neutral-200 underline underline-offset-2 hover:text-neutral-100"
                onClick={() => {
                  setTyping(!typing)
                  if (typing) setAddress(preferredAddress(selected))
                  connect.reset()
                }}
              >
                {typing ? 'Choose an address from the list' : 'Type an address instead'}
              </button>
            </p>
          )}
          {message && !message.field && (
            <PlexNote
              message={message}
              focus
              busy={connect.isPending}
              on={{ 'sign-in-again': onBack, 'start-again': onBack, 'try-again': submit }}
            />
          )}
          <p className="text-sm text-neutral-400">Connecting doesn’t import anything.</p>
        </div>
      )}
    </FlowFrame>
  )
}

function Connected({ crumbs, connection, reconnect }: { crumbs: Crumb[]; connection: PlexConnection; reconnect: boolean }) {
  const linked = plexLinked(connection)
  const name = connection.name
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={CONNECT_STEPS}
      current="done"
      finished="ok"
      title={`${name} is connected`}
      lede={reconnect && linked ? 'Your links to people and libraries are kept. Nothing new has been imported.' : 'Nothing has been imported yet.'}
      skip={{ label: 'Back to Plex', href: plexHref() }}
      next={linked ? { label: 'Check what will change →', href: plexHref('import') } : { label: 'Link people and libraries →', href: plexHref('link') }}
    >
      <div className="grid gap-4 md:grid-cols-2">
        <ListCard
          title="What changed"
          items={[
            `This server can read ${name}’s people, libraries and watched marks.`,
            'Plex lists this server as a device on your account.',
            reconnect && linked && 'Your links to people and libraries are kept.',
          ]}
        />
        <ListCard
          title="What happens next"
          items={linked ? [
            'Check what an import would change. It reads Plex and writes nothing.',
            'Then import once, or import and keep up to date.',
          ] : [
            'Link each Plex person to an account here, and each Plex library to a library here.',
            'Then check what would change before importing anything.',
          ]}
        />
      </div>
      {connection.historyCapability === 'unavailable' && (
        <Message tone="info" className="mt-4">{name} doesn’t share earlier viewings with this server. Watched marks can still come across.</Message>
      )}
    </FlowFrame>
  )
}
