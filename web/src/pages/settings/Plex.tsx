import { ConfirmationCancelled } from '../../api/withConfirm'
import { memo, useEffect, useMemo, useState } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  cancelPlexSignIn,
  connectPlex,
  plexConnectionsQuery,
  plexExclusionsQuery,
  plexLibrariesQuery,
  plexPeopleQuery,
  plexPreviewQuery,
  plexRunsQuery,
  plexRunQuery,
  patchPlexRunHistory,
  invalidatePlexState,
  type PlexRunPage,
  plexRunHistoryQuery,
  plexSignInQuery,
  previewPlexUndo,
  startPlexSignIn,
  useDisconnectPlex,
  usePlexRunAction,
  usePlexSync,
  useRebaselinePlex,
  useSavePlexExclusions,
  useSavePlexMappings,
  useStartPlexPreview,
  useStartPlexRun,
  useUndoPlexRun,
  type PlexConnection,
  type PlexPerson,
  type PlexRun,
  type PlexSection,
  type PlexServer,
  type PlexSignIn,
  type PlexUndoPreview,
} from '../../api/plex'
import { librariesQuery, usersQuery } from '../../api/queries'
import {
  addPlexExclusion,
  canImportPlexPreview,
  PLEX_COUNT_LABELS,
  plexAuthorizationURL,
  plexExampleTitle,
  plexHistoryStatus,
  plexKindLabel,
  plexLinks,
  plexMappingDraft,
  plexMappingError,
  plexMappingsChanged,
  plexPersonStatus,
  plexPreviewCounts,
  plexReason,
  plexRunActive,
  plexHasApprovedImport,
  plexRunStatus,
} from '../../lib/plex'
import { PlexConfirmation } from '../../components/PlexConfirmation'
import { PageHeader, Panel, primaryButton, secondaryButton, StatusPill, textInput } from '../../components/SettingsPanel'
import { ServerOnly } from './SettingsLayout'
import { when } from '../activity/shared'

/** Administration stays on an allowed network; personal imported history has its own route. */
export function PlexSettings() {
  return (
    <>
      <PageHeader title="Plex">Bring your watched movies and episodes across. Your choices in WatchMyMedia come first. Plex stays as it is.</PageHeader>
      <ServerOnly>
        <PlexContent />
      </ServerOnly>
    </>
  )
}

function PlexContent() {
  const connections = useQuery(plexConnectionsQuery)
  const qc = useQueryClient()
  const [reconnect, setReconnect] = useState(false)
  const connection = connections.data?.items[0]
  const connected = (value: PlexConnection) => {
    qc.setQueryData(plexConnectionsQuery.queryKey, { items: [value] })
    invalidatePlexState(qc)
    setReconnect(false)
  }
  if (connections.isPending)
    return (
      <p role="status" className="text-neutral-400">
        Checking your Plex connection…
      </p>
    )
  if (connections.isError)
    return <ErrorBox error={connections.error} fallback="Couldn’t check your Plex connection. Try again." retry={() => void connections.refetch()} />
  return (
    <div className="space-y-6">
      {(!connection || reconnect) && <PlexSignInFlow onConnected={connected} onCancel={connection ? () => setReconnect(false) : undefined} />}
      {connection && <ConnectedPlex key={connection.id} connection={connection} onReconnect={() => setReconnect(true)} />}
      <Panel title="What stays the same">
        <ul className="list-disc space-y-2 px-9 pb-5 text-sm text-neutral-400">
          <li>Nothing is changed or played on Plex.</li>
          <li>Anything you played, marked watched or marked unwatched in WatchMyMedia stays as you chose it.</li>
          <li>Earlier viewings from Plex are kept separately. They do not count as plays in WatchMyMedia.</li>
        </ul>
      </Panel>
    </div>
  )
}

function PlexSignInFlow({ onConnected, onCancel }: { onConnected: (value: PlexConnection) => void; onCancel?: () => void }) {
  const qc = useQueryClient()
  const start = useMutation({ mutationFn: startPlexSignIn, onSuccess: (value) => qc.setQueryData(plexSignInQuery(value.id).queryKey, value) })
  const attempt = useQuery({ ...plexSignInQuery(start.data?.id ?? ''), initialData: start.data })
  const cancel = useMutation({
    mutationFn: async () => {
      if (start.data) await cancelPlexSignIn(start.data.id)
    },
    onSuccess: () => {
      start.reset()
      onCancel?.()
    },
  })
  const signIn = attempt.data
  const authURL = plexAuthorizationURL(signIn?.authUrl)
  return (
    <Panel title="Sign in to Plex" description="Use the Plex account that owns your server. WatchMyMedia never asks for your Plex password.">
      <div className="space-y-4 px-5 pb-5">
        {!signIn && (
          <>
            <p className="text-sm text-neutral-400">First start signing in, then open Plex’s sign-in page in a new tab. Come back here when you’re done.</p>
            <button type="button" className={primaryButton} disabled={start.isPending} onClick={() => start.mutate()}>
              {start.isPending ? 'Starting sign-in…' : 'Sign in to Plex'}
            </button>
          </>
        )}
        {signIn?.status === 'pending' && (
          <>
            <p className="text-sm text-neutral-300">Open Plex’s page and sign in. This page checks automatically when you return.</p>
            <div className="flex flex-wrap gap-2">
              {authURL && (
                <a href={authURL} target="_blank" rel="noreferrer" className={`${primaryButton} inline-flex items-center`}>
                  Open Plex sign-in ↗
                </a>
              )}
              <button type="button" className={secondaryButton} disabled={attempt.isFetching} onClick={() => void attempt.refetch()}>
                I’ve signed in
              </button>
              <button type="button" className={secondaryButton} disabled={cancel.isPending} onClick={() => cancel.mutate()}>
                Cancel
              </button>
            </div>
            <p className="text-xs text-neutral-400">This sign-in expires {when(signIn.expiresAt)}. If it expires, start a new sign-in.</p>
            {!authURL && (
              <p role="alert" className="text-sm text-orange-300">
                Plex’s sign-in link could not be checked. Cancel and start a new sign-in.
              </p>
            )}
          </>
        )}
        {signIn?.status === 'ready' && <PlexChooseServer key={signIn.id} signIn={signIn} onConnected={onConnected} onCancel={() => cancel.mutate()} />}
        {(start.isError || attempt.isError || cancel.isError) && (
          <ErrorBox
            error={start.error ?? attempt.error ?? cancel.error}
            fallback="Couldn’t finish signing in. Check your connection, cancel this attempt and start again."
          />
        )}
        {onCancel && !signIn && (
          <button type="button" className={secondaryButton} onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </Panel>
  )
}

function PlexChooseServer({ signIn, onConnected, onCancel }: { signIn: PlexSignIn; onConnected: (value: PlexConnection) => void; onCancel: () => void }) {
  const servers = signIn.servers ?? []
  const [machine, setMachine] = useState(servers.length === 1 ? servers[0].machineIdentifier : '')
  const initial = servers.length === 1 ? preferredAddress(servers[0]) : ''
  const [address, setAddress] = useState(initial)
  const connect = useMutation({ mutationFn: connectPlex, onSuccess: onConnected })
  const selected = servers.find((server) => server.machineIdentifier === machine)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (selected && address.trim()) connect.mutate({ signInId: signIn.id, machineIdentifier: selected.machineIdentifier, origin: address.trim() })
      }}
    >
      <p className="text-sm text-neutral-300">
        Choose a server owned by this Plex account, then an address this WatchMyMedia server can reach. Connecting does not import anything.
      </p>
      {!servers.length ? (
        <p className="rounded-lg border border-line-2 p-3 text-sm text-neutral-300">
          Plex did not list a server owned by this account. Cancel, then sign in with the account that owns your server.
        </p>
      ) : (
        <label className="block text-sm font-medium">
          Plex server
          <select
            value={machine}
            disabled={connect.isPending}
            className={`${textInput} mt-1.5 block w-full`}
            onChange={(e) => {
              const server = servers.find((s) => s.machineIdentifier === e.target.value)
              setMachine(e.target.value)
              setAddress(server ? preferredAddress(server) : '')
            }}
          >
            <option value="">Choose your server</option>
            {servers.map((server) => (
              <option key={server.machineIdentifier} value={server.machineIdentifier}>
                {server.name}
              </option>
            ))}
          </select>
        </label>
      )}
      {selected && (
        <>
          <label className="block text-sm font-medium">
            Server address
            <input
              required
              type="url"
              inputMode="url"
              autoComplete="off"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              disabled={connect.isPending}
              placeholder="http://your-server:32400"
              className={`${textInput} mt-1.5 block w-full`}
            />
          </label>
          {selected.addresses.length > 1 && (
            <div className="flex flex-wrap gap-2">
              {selected.addresses.map((a) => (
                <button
                  key={a.url}
                  type="button"
                  className={`${secondaryButton} max-w-full break-all text-left`}
                  disabled={connect.isPending}
                  onClick={() => setAddress(a.url)}
                >
                  Use {a.url}
                </button>
              ))}
            </div>
          )}
        </>
      )}
      <div className="flex flex-wrap gap-2">
        {servers.length > 0 && (
          <button type="submit" disabled={!selected || !address.trim() || connect.isPending} className={primaryButton}>
            {connect.isPending ? 'Connecting…' : 'Connect server'}
          </button>
        )}
        <button type="button" disabled={connect.isPending} className={secondaryButton} onClick={onCancel}>
          Cancel
        </button>
      </div>
      {connect.isError && (
        <ErrorBox
          error={connect.error}
          fallback="Couldn’t connect. Check that Plex is running, try another server address above, then choose Connect server again. You can cancel and come back later."
        />
      )}
    </form>
  )
}

function preferredAddress(server: PlexServer): string {
  return (server.addresses.find((a) => a.local) ?? server.addresses[0])?.url ?? ''
}

function ConnectedPlex({ connection, onReconnect }: { connection: PlexConnection; onReconnect: () => void }) {
  const [editing, setEditing] = useState(connection.linkedUsers === 0 || connection.linkedLibraries === 0)
  const [previewId, setPreviewId] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmation, setConfirmation] = useState<'disconnect' | 'restored' | null>(null)
  const preview = useStartPlexPreview(connection.id)
  const sync = usePlexSync(connection.id)
  const disconnect = useDisconnectPlex(connection.id)
  const restore = useRebaselinePlex(connection.id)
  const runs = useQuery(plexRunsQuery(connection.id))
  const qc = useQueryClient()
  const currentRunId = runs.data?.active?.id
  const currentRunStatus = runs.data?.active?.status
  const latestRunId = runs.data?.items[0]?.id
  const latestRunStatus = runs.data?.items[0]?.status
  useEffect(() => {
    if (latestRunId && latestRunStatus && !plexRunActive(latestRunStatus)) void qc.invalidateQueries({ queryKey: plexConnectionsQuery.queryKey })
  }, [latestRunId, latestRunStatus, currentRunId, currentRunStatus, qc])
  const active = !!runs.data?.active || (runs.data?.items.some((run) => plexRunActive(run.status)) ?? false)
  const previous = runs.data?.items.find((run) => run.status === 'complete')
  const imported = plexHasApprovedImport(connection, runs.data?.items ?? [])
  const ready = connection.status === 'connected'
  const check = async () => {
    setNotice('')
    const value = await preview.mutateAsync()
    setPreviewId(value.id)
    setEditing(false)
  }
  return (
    <>
      <Panel title={connection.name} actions={<StatusPill tone={ready ? 'ok' : 'warn'}>{ready ? 'Connected' : 'Needs attention'}</StatusPill>}>
        <div className="space-y-3 px-5 pb-5 text-sm">
          <p className="break-all text-neutral-400">{connection.origin}</p>
          {!ready && (
            <div className="rounded-lg border border-orange-900 bg-orange-950/25 p-3">
              <p className="text-orange-200">{plexReason(connection.status)}</p>
              <p className="mt-1 text-neutral-300">Anything already imported stays. Reconnect with the account that owns this Plex server to continue.</p>
              <button type="button" className={`${secondaryButton} mt-3`} onClick={onReconnect}>
                Reconnect Plex
              </button>
            </div>
          )}
          {connection.problem && (
            <p role="status" className="text-orange-300">
              {plexReason(connection.problem)}
            </p>
          )}
          <p className="text-neutral-300">
            {ready && connection.syncEnabled
              ? 'Keeping up to date: Plex is checked about every 15 minutes.'
              : 'Updates from Plex are paused. Completed imports stay.'}
          </p>
          {previous?.finishedAt && <p className="text-neutral-400">Last completed check: {when(previous.finishedAt)}</p>}
          <p className="text-neutral-400">
            Earlier viewings:{' '}
            {connection.historyCapability === 'available'
              ? 'available, checked separately during import'
              : connection.historyCapability === 'unavailable'
                ? 'unavailable on this Plex server; watched choices can still import'
                : 'not checked yet'}
            .
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className={primaryButton}
              disabled={!ready || active || preview.isPending || !connection.linkedUsers || !connection.linkedLibraries}
              onClick={() => void check().catch(() => {})}
            >
              {preview.isPending ? 'Checking Plex…' : 'Check what will change'}
            </button>
            <button
              type="button"
              className={secondaryButton}
              disabled={!ready || active}
              onClick={() => {
                setEditing((value) => !value)
                setPreviewId('')
              }}
            >
              {editing ? 'Close people and libraries' : 'Change people or libraries'}
            </button>
            {ready && connection.syncEnabled ? (
              <button
                type="button"
                className={secondaryButton}
                disabled={sync.isPending}
                onClick={() => sync.mutate({ generation: connection.generation, enabled: false })}
              >
                Pause updates
              </button>
            ) : (
              imported && (
                <button
                  type="button"
                  className={secondaryButton}
                  disabled={!ready || sync.isPending || active}
                  onClick={() => sync.mutate({ generation: connection.generation, enabled: true })}
                >
                  Keep up to date
                </button>
              )
            )}
            {ready && (
              <button type="button" className={secondaryButton} disabled={active} onClick={onReconnect}>
                Sign in again
              </button>
            )}
          </div>
          {!connection.linkedUsers || !connection.linkedLibraries ? (
            <p className="text-neutral-400">Choose at least one person and one library below before checking an import.</p>
          ) : (
            <p className="text-neutral-400">A check shows what would change before you import. You can import once or keep up to date.</p>
          )}
          {active && (
            <p role="status" className="text-sm text-neutral-300">
              An import is running. Follow its progress below, or cancel it before changing people, libraries or items left out.
            </p>
          )}
          {(preview.isError || sync.isError) && (
            <ErrorBox
              error={preview.error ?? sync.error}
              fallback="Couldn’t complete this step. Refresh the connection or reconnect Plex, then try again."
              retry={() => void runs.refetch()}
            />
          )}
        </div>
      </Panel>
      {notice && (
        <p role="status" className="rounded-lg border border-line-2 bg-surface p-4 text-sm text-neutral-200">
          {notice}
        </p>
      )}
      {editing && ready && (
        <PlexMappings
          connection={connection}
          onSaved={() => {
            setPreviewId('')
            setNotice('People and libraries saved. Choose Check what will change when you are ready.')
          }}
          onPreview={check}
        />
      )}
      {previewId && (
        <PlexPreviewPanel
          key={previewId}
          connection={connection}
          previewId={previewId}
          active={active}
          onPreview={check}
          onStarted={() => {
            setPreviewId('')
            setNotice('Import started. The results below show completed changes as it runs.')
          }}
          onExcluded={() => {
            setPreviewId('')
            setNotice('That item will be left out. Check what will change again before importing.')
          }}
        />
      )}
      <PlexRuns connection={connection} runs={runs} />
      <PlexExcludedItems
        connection={connection}
        active={active}
        onChanged={() => {
          setPreviewId('')
          setNotice('Items left out were updated. Check what will change again before importing.')
        }}
      />
      <details className="rounded-xl border border-line bg-surface px-5 py-4">
        <summary className="cursor-pointer font-medium">Undo, disconnect or check a restored server</summary>
        <div className="mt-4 space-y-5 text-sm text-neutral-400">
          <p>To undo watched choices from an import, use Check undo beside that import above. Later choices you made here stay.</p>
          <div>
            <h3 className="font-medium text-neutral-100">Disconnect Plex</h3>
            <p className="mt-1">Stop updates and remove the saved Plex sign-in. Choices and earlier viewings already imported stay here.</p>
            <button type="button" className={`${secondaryButton} mt-3`} onClick={() => setConfirmation('disconnect')}>
              Disconnect Plex
            </button>
          </div>
          <div>
            <h3 className="font-medium text-neutral-100">Did you restore or rebuild Plex?</h3>
            <p className="mt-1">
              If Plex now gives different items its old links, imports stop to protect your choices. Check Plex first. Only continue here if you restored or
              rebuilt that server.
            </p>
            <button type="button" className={`${secondaryButton} mt-3`} disabled={!ready || active} onClick={() => setConfirmation('restored')}>
              Check a restored Plex server
            </button>
          </div>
        </div>
      </details>
      {confirmation === 'disconnect' && (
        <PlexConfirmation
          title="Disconnect Plex?"
          description="Updates stop and the saved Plex sign-in is removed. Watched choices and earlier viewings already imported stay. Nothing changes in Plex."
          confirm="Disconnect Plex"
          action={() => disconnect.mutateAsync()}
          onDone={() => setConfirmation(null)}
          onClose={() => setConfirmation(null)}
        />
      )}
      {confirmation === 'restored' && (
        <PlexConfirmation
          title="Check a restored Plex server?"
          description="Continue only if you restored or rebuilt this Plex server. Updates pause and you must check what will change again. Existing watched choices and imported viewings stay; some earlier viewings may appear twice after a restore."
          confirm="I restored Plex — check again"
          action={() => restore.mutateAsync(connection.generation)}
          onDone={() => {
            setConfirmation(null)
            setPreviewId('')
            setNotice('Ready to check the restored Plex server. Choose Check what will change before importing again.')
          }}
          onClose={() => setConfirmation(null)}
        />
      )}
    </>
  )
}

function PlexMappings({ connection, onSaved, onPreview }: { connection: PlexConnection; onSaved: () => void; onPreview: () => Promise<void> }) {
  const people = useQuery(plexPeopleQuery(connection.id, connection.generation))
  const sections = useQuery(plexLibrariesQuery(connection.id, connection.generation))
  const users = useQuery(usersQuery)
  const libraries = useQuery(librariesQuery)
  const qc = useQueryClient()
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['plex', 'connections'] })
    void people.refetch()
    void sections.refetch()
    void users.refetch()
    void libraries.refetch()
  }
  const same = people.data?.generation === sections.data?.generation && people.data?.generation === connection.generation
  if (people.isError || sections.isError || users.isError || libraries.isError)
    return (
      <ErrorBox
        error={people.error ?? sections.error ?? users.error ?? libraries.error}
        fallback="Couldn’t read the people or libraries. Check Plex’s connection, then refresh. You can leave unavailable people or libraries out."
        retry={refresh}
      />
    )
  if (!people.data || !sections.data || !users.data || !libraries.data)
    return (
      <p role="status" className="text-neutral-400">
        Reading people and libraries…
      </p>
    )
  if (!same) return <ErrorBox fallback="The connection changed while this page was open. Refresh people and libraries before saving." retry={refresh} />
  return (
    <PlexMappingForm
      key={`${connection.generation}:${JSON.stringify(people.data.users.map((u) => u.linkedUserId))}:${JSON.stringify(sections.data.libraries.map((l) => l.linkedLibraryId))}`}
      connection={connection}
      people={people.data.users}
      sections={sections.data.libraries}
      users={users.data.items}
      libraries={libraries.data.items}
      onSaved={onSaved}
      onPreview={onPreview}
      onRefresh={refresh}
    />
  )
}

type LocalUser = { id: number; name: string }
type LocalLibrary = { id: number; name: string; type: 'movie' | 'tv' }
function PlexMappingForm({
  connection,
  people,
  sections,
  users,
  libraries,
  onSaved,
  onPreview,
  onRefresh,
}: {
  connection: PlexConnection
  people: PlexPerson[]
  sections: PlexSection[]
  users: LocalUser[]
  libraries: LocalLibrary[]
  onSaved: () => void
  onPreview: () => Promise<void>
  onRefresh: () => void
}) {
  const initial = plexMappingDraft(people, sections)
  const [draft, setDraft] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)
  const save = useSavePlexMappings(connection.id)
  const busy = save.isPending || checking
  const submit = async (preview: boolean) => {
    const problem = plexMappingError(draft, people, sections, users, libraries, preview)
    if (problem) {
      setError(problem)
      return
    }
    setError(null)
    setChecking(preview)
    try {
      if (plexMappingsChanged(connection, initial, draft)) {
        await save.mutateAsync(plexLinks(connection.generation, draft))
        onSaved()
      } else if (!preview) onSaved()
      if (preview) await onPreview()
    } catch (cause) {
      setError(cause instanceof Error ? plexReason(cause.message) : 'Couldn’t save. Refresh people and libraries and try again.')
    } finally {
      setChecking(false)
    }
  }
  return (
    <Panel title="Choose people and libraries" description="Choose exactly who and what you want to bring across. People and libraries left out are not read.">
      <div className="space-y-5 px-5 pb-5">
        <div>
          <h3 className="font-medium">People</h3>
          <p className="mt-1 text-sm text-neutral-400">
            Choose each person’s account here. We do not link people by name.{' '}
            <Link to="/settings/users" className="text-neutral-200 underline">
              Add an account
            </Link>{' '}
            first if they need one.
          </p>
          <div className="mt-3 divide-y divide-line rounded-lg border border-line">
            {people.map((person) => (
              <div key={person.plexUserId} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0 flex-1 basis-52">
                  <strong className="break-words text-sm">{person.title}</strong>
                  <p className="mt-0.5 text-xs text-neutral-400">
                    {plexKindLabel(person.kind)} · {plexPersonStatus(person.status)}
                  </p>
                  {person.status !== 'ready' && (
                    <p className="mt-1 text-xs text-orange-300">
                      {person.status === 'needs_pin'
                        ? 'PIN-protected profiles can’t be imported yet. Leave this person out and continue with others.'
                        : 'Check this person’s access in Plex, then refresh. You can leave them out for now.'}
                    </p>
                  )}
                </div>
                <select
                  aria-label={`WatchMyMedia account for ${person.title}`}
                  className={`${textInput} w-full sm:w-52`}
                  value={draft.users[person.plexUserId] ?? 0}
                  disabled={busy}
                  onChange={(e) => {
                    setDraft({ ...draft, users: { ...draft.users, [person.plexUserId]: Number(e.target.value) } })
                    setError(null)
                  }}
                >
                  <option value={0}>Leave out</option>
                  {users.map((u) => (
                    <option key={u.id} value={u.id} disabled={person.status !== 'ready'}>
                      {u.name}
                    </option>
                  ))}
                </select>
              </div>
            ))}
            {!people.length && (
              <p className="p-4 text-sm text-neutral-400">No people were found. Check the Plex account that owns this server, then refresh.</p>
            )}
          </div>
        </div>
        <div>
          <h3 className="font-medium">Libraries</h3>
          <p className="mt-1 text-sm text-neutral-400">
            Movies go with movies, and TV with TV.{' '}
            <Link to="/settings/libraries" className="text-neutral-200 underline">
              Add a library here
            </Link>{' '}
            first if needed.
          </p>
          <div className="mt-3 divide-y divide-line rounded-lg border border-line">
            {sections.map((section) => (
              <div key={section.sectionId} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0 flex-1 basis-52">
                  <strong className="break-words text-sm">{section.title}</strong>
                  <p className="mt-0.5 text-xs text-neutral-400">{section.type === 'movie' ? 'Movies' : 'TV shows'}</p>
                </div>
                <select
                  aria-label={`WatchMyMedia library for ${section.title}`}
                  className={`${textInput} w-full sm:w-52`}
                  value={draft.libraries[section.sectionId] ?? 0}
                  disabled={busy}
                  onChange={(e) => {
                    setDraft({ ...draft, libraries: { ...draft.libraries, [section.sectionId]: Number(e.target.value) } })
                    setError(null)
                  }}
                >
                  <option value={0}>Leave out</option>
                  {libraries
                    .filter((l) => l.type === (section.type === 'movie' ? 'movie' : 'tv'))
                    .map((l) => (
                      <option key={l.id} value={l.id}>
                        {l.name}
                      </option>
                    ))}
                </select>
              </div>
            ))}
            {!sections.length && <p className="p-4 text-sm text-neutral-400">No movie or TV libraries were found. Add them in Plex, then refresh.</p>}
          </div>
        </div>
        <p className="text-sm text-neutral-400">Choose at least one person and one library to check an import. Saving an empty selection imports nothing.</p>
        <div className="flex flex-wrap gap-2">
          <button type="button" className={secondaryButton} disabled={busy} onClick={() => void submit(false)}>
            {save.isPending ? 'Saving…' : 'Save people and libraries'}
          </button>
          <button type="button" className={primaryButton} disabled={busy} onClick={() => void submit(true)}>
            {checking ? 'Checking Plex…' : 'Save and check what will change'}
          </button>
          <button type="button" className={secondaryButton} disabled={busy} onClick={onRefresh}>
            Refresh people and libraries
          </button>
        </div>
        {error && (
          <p role="alert" className="text-sm text-orange-300">
            {error}
          </p>
        )}
      </div>
    </Panel>
  )
}

function useClock(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}

function PlexPreviewPanel({
  connection,
  previewId,
  active,
  onPreview,
  onStarted,
  onExcluded,
}: {
  connection: PlexConnection
  previewId: string
  active: boolean
  onPreview: () => Promise<void>
  onStarted: () => void
  onExcluded: () => void
}) {
  const preview = useQuery(plexPreviewQuery(connection.id, previewId))
  const exclusions = useQuery(plexExclusionsQuery(connection.id))
  const exclude = useSavePlexExclusions(connection.id)
  const start = useStartPlexRun(connection.id)
  const now = useClock()
  const value = preview.data
  const counts = value ? plexPreviewCounts(value) : null
  const ready = !!value && canImportPlexPreview(connection, value, now)
  const busy = start.isPending || exclude.isPending
  const begin = async (enableSync: boolean) => {
    await start.mutateAsync({ previewId, enableSync })
    onStarted()
  }
  return (
    <Panel title="Check what will change" description="Nothing below changes until you choose to import.">
      <div className="space-y-4 px-5 pb-5">
        {(preview.isPending || value?.status === 'running') && (
          <p role="status" className="text-neutral-400">
            Checking each chosen person’s watched movies and episodes in Plex…
          </p>
        )}
        {value?.status === 'complete' && counts && (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
              {PLEX_COUNT_LABELS.map(([key, label]) => (
                <div key={key} className="rounded-lg border border-line-2 p-3">
                  <strong className="block text-2xl tabular-nums">{counts[key].toLocaleString()}</strong>
                  <span className="text-xs text-neutral-400">{label}</span>
                </div>
              ))}
            </div>
            <div className="rounded-lg border border-line-2 p-4 text-sm text-neutral-300">
              Your choices here come first. Progress, rewatches and anything you marked unwatched in WatchMyMedia stay as you chose them. Choices cleared before
              this feature existed may be unknown; use Leave this unwatched below for an item you do not want imported. For another item, mark it unwatched in
              WatchMyMedia before importing.
            </div>
            <p className="text-sm text-neutral-400">
              Plex left {counts.notWatched.toLocaleString()} items unwatched and did not report a watched choice for {counts.unknownState.toLocaleString()}.
              These are left alone.
            </p>
            {(value.users ?? []).map((person) => (
              <details key={person.userId} className="rounded-lg border border-line">
                <summary className="cursor-pointer px-4 py-3 text-sm font-medium">
                  {person.title} · {person.counts.willMark.toLocaleString()} will be marked watched
                  {person.status !== 'complete' || !!person.skippedSections ? ' · Couldn’t read everything' : ''}
                </summary>
                <div className="space-y-3 border-t border-line px-4 pb-4 pt-3">
                  {person.status !== 'complete' || !!person.skippedSections ? (
                    <p className="text-sm text-orange-300">
                      Check this person’s access and libraries in Plex, or leave them out above. Then check what will change again. {plexReason(person.reason)}
                    </p>
                  ) : null}
                  {person.examples.map((item, i) => (
                    <div
                      key={`${item.itemType}:${item.itemId ?? i}:${item.category}`}
                      className="flex flex-wrap items-center justify-between gap-3 border-b border-line py-2 text-sm"
                    >
                      <div className="min-w-0 flex-1 basis-56">
                        <strong className="break-words font-medium">{plexExampleTitle(item)}</strong>
                        <p className="mt-0.5 text-xs text-neutral-400">
                          {PLEX_COUNT_LABELS.find(([key]) => key === item.category)?.[1] ?? 'Left alone'}
                          {item.reason ? ` · ${plexReason(item.reason)}` : ''}
                        </p>
                      </div>
                      {item.category === 'willMark' && !!item.itemId && (
                        <button
                          type="button"
                          className={secondaryButton}
                          disabled={busy || !exclusions.data || active}
                          onClick={() => {
                            if (!exclusions.data || !item.itemId) return
                            exclude.mutate(
                              {
                                generation: exclusions.data.generation,
                                items: addPlexExclusion(exclusions.data.items, { userId: person.userId, itemType: item.itemType, itemId: item.itemId }),
                              },
                              { onSuccess: onExcluded },
                            )
                          }}
                        >
                          Leave this unwatched
                        </button>
                      )}
                    </div>
                  ))}
                  {!person.examples.length && <p className="text-sm text-neutral-400">No examples to show for this person.</p>}
                </div>
              </details>
            ))}
            {!!value.unlinked?.length && (
              <p className="text-sm text-neutral-400">
                Left out: {value.unlinked.map((person) => `${person.title} (${plexPersonStatus(person.status)})`).join(', ')}. Nothing of theirs is read.
              </p>
            )}
            <p className="text-sm text-neutral-400">
              Earlier viewings are checked separately during import, only for people who keep history here. The results show which dates were checked. Plex may
              no longer have every older viewing.
            </p>
            {!ready && (
              <p role="status" className="text-sm text-orange-300">
                This check expired, your choices changed, or not everything could be read. Fix any access problems, then check what will change again.
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <button type="button" className={secondaryButton} disabled={!ready || busy || active} onClick={() => void begin(false).catch(() => {})}>
                {start.isPending ? 'Starting import…' : 'Import once'}
              </button>
              <button type="button" className={primaryButton} disabled={!ready || busy || active} onClick={() => void begin(true).catch(() => {})}>
                Import and keep up to date
              </button>
              <button type="button" className={secondaryButton} disabled={busy || active} onClick={() => void onPreview().catch(() => {})}>
                Check again
              </button>
            </div>
            <p className="text-xs text-neutral-400">
              Import once brings across this checked set. Keep up to date also checks Plex about every 15 minutes. You can pause updates at any time.
            </p>
          </>
        )}
        {(value?.status === 'failed' || value?.status === 'stale' || preview.isError) && (
          <ErrorBox
            error={preview.error}
            fallback={
              plexReason(value?.reason) || 'This check is no longer available. Check Plex’s connection or your chosen people and libraries, then check again.'
            }
            retry={() => void onPreview().catch(() => {})}
          />
        )}
        {(start.isError || exclude.isError || exclusions.isError) && (
          <ErrorBox
            error={start.error ?? exclude.error ?? exclusions.error}
            fallback="This step could not finish. Your existing watched choices stay. Refresh the connection and check what will change again."
          />
        )}
      </div>
    </Panel>
  )
}

function PlexRuns({ connection, runs }: { connection: PlexConnection; runs: UseQueryResult<PlexRunPage> }) {
  // Disabled historical observers ignore intervals, focus and invalidation. Seed
  // the current head only when Earlier imports is requested; retain it so newly
  // arriving runs cannot roll an original row past the pagination boundary.
  const history = useInfiniteQuery(plexRunHistoryQuery(connection.id))
  const qc = useQueryClient()
  const pageItems = [...(runs.data?.items ?? []), ...(history.data?.pages.flatMap((page) => page.items) ?? [])]
  const active = runs.data?.active
  const unique = new Map<number, PlexRun>()
  for (const run of [...(active ? [active] : []), ...pageItems]) if (!unique.has(run.id)) unique.set(run.id, run)
  const items = [...unique.values()]
  const activeIDs = new Set(items.filter((run) => plexRunActive(run.status)).map((run) => run.id))
  const liveIDs = new Set(runs.data?.items.map((run) => run.id))
  const users = useQuery({ ...usersQuery, enabled: !!items.length })
  const libraries = useQuery({ ...plexLibrariesQuery(connection.id, connection.generation), enabled: false })
  const userNames = useMemo(() => new Map(users.data?.items.map((user) => [user.id, user.name])), [users.data?.items])
  const libraryNames = useMemo(() => new Map(libraries.data?.libraries.map((library) => [library.sectionId, library.title])), [libraries.data?.libraries])
  const earlier = async () => {
    if (!history.data && runs.data) qc.setQueryData(plexRunHistoryQuery(connection.id).queryKey, { pages: [runs.data], pageParams: [0] })
    await history.fetchNextPage()
  }
  return (
    <Panel
      title="Imports"
      description="Counts show completed changes. A stopped import keeps anything already completed."
      actions={<Link to="/activity/plex-history" className="min-h-[40px] py-2 text-sm text-neutral-200 underline">Earlier viewings from Plex →</Link>}
    >
      {(runs.isError || history.isError) && (
        <div className="px-5 pb-5">
          <ErrorBox error={runs.error ?? history.error} fallback="Couldn’t read imports. Try again to see the saved results."
            retry={() => { if (history.isError) void earlier(); else void runs.refetch() }} />
        </div>
      )}
      {runs.isPending && !items.length ? (
        <p role="status" className="px-5 pb-5 text-sm text-neutral-400">Checking recent imports…</p>
      ) : !items.length && !runs.isError ? (
        <p className="px-5 pb-5 text-sm text-neutral-400">Nothing has been imported yet. Choose people and libraries, check what will change, then choose Import once or Import and keep up to date.</p>
      ) : (
        <div className="divide-y divide-line">
          {items.map((run) => (
            <PlexRunRow key={run.id} connection={connection} run={run}
              anotherActive={activeIDs.size > (activeIDs.has(run.id) ? 1 : 0)} followLive={!liveIDs.has(run.id)} userNames={userNames} libraryNames={libraryNames} />
          ))}
        </div>
      )}
      {(history.data ? history.hasNextPage : !!runs.data?.next) && (
        <div className="border-t border-line px-5 py-4">
          <button type="button" className={secondaryButton} disabled={history.isFetchingNextPage || runs.isPending} onClick={() => void earlier().catch(() => {})}>
            {history.isFetchingNextPage ? 'Reading earlier imports…' : 'Earlier imports'}
          </button>
          <p className="mt-2 text-xs text-neutral-400">Open an earlier import to check undoing its watched choices. Later choices you made here stay.</p>
        </div>
      )}
    </Panel>
  )
}

// Only an old active row needs a detail observer. Inactive history may contain
// hundreds of rows; it does not create observers or repeat provider discovery.
function PlexRunFollow({ connectionId, runId }: { connectionId: number; runId: number }) {
  const detail = useQuery(plexRunQuery(connectionId, runId))
  const qc = useQueryClient()
  useEffect(() => {
    if (detail.data) patchPlexRunHistory(qc, connectionId, detail.data)
  }, [detail.data, qc, connectionId])
  return null
}

const PlexRunRow = memo(function PlexRunRow({ connection, run, anotherActive, followLive, userNames, libraryNames }: {
  connection: PlexConnection
  run: PlexRun
  anotherActive: boolean
  followLive: boolean
  userNames: Map<number, string>
  libraryNames: Map<number, string>
}) {
  const cancel = usePlexRunAction(connection.id, 'cancel')
  const retry = usePlexRunAction(connection.id, 'retry')
  const undo = useUndoPlexRun(connection.id)
  const checkUndo = useMutation({ mutationFn: () => previewPlexUndo(connection.id, run.id), onSuccess: (value) => setUndoPreview(value) })
  const [undoPreview, setUndoPreview] = useState<PlexUndoPreview | null>(null)
  const [notice, setNotice] = useState('')
  const active = plexRunActive(run.status)
  const retryable = ['failed', 'partial', 'cancelled'].includes(run.status) && run.generation === connection.generation && connection.status === 'connected'
  return (
    <div className="space-y-3 px-5 py-4">
      {followLive && active && <PlexRunFollow connectionId={connection.id} runId={run.id} />}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <strong className="text-sm">{when(run.startedAt)}</strong>
          <p className="mt-0.5 text-xs text-neutral-400">
            {run.finishedAt ? `Finished ${when(run.finishedAt)}` : active ? 'Results update while this import runs.' : 'Completed changes are kept.'}
          </p>
        </div>
        <StatusPill tone={run.status === 'complete' ? 'ok' : active ? 'off' : 'warn'}>{plexRunStatus(run.status)}</StatusPill>
      </div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-3">
        {(
          [
            ['Marked watched', run.counts.marked],
            ['Already watched here', run.counts.alreadyWatched],
            ['Your choices kept', run.counts.localKept],
            ['Not found here', run.counts.unmatched],
            ['Needs a closer match', run.counts.ambiguous],
            ['No longer available', run.counts.unavailable],
          ] as const
        ).map(([label, count]) => (
          <div key={label}>
            <dt className="text-xs text-neutral-400">{label}</dt>
            <dd className="mt-0.5 tabular-nums">{count.toLocaleString()}</dd>
          </div>
        ))}
      </dl>
      <p className="text-sm text-neutral-400">
        {plexHistoryStatus(run.history)} · {run.counts.historyImported.toLocaleString()} earlier viewings added
        {run.counts.historySkipped ? ` · ${run.counts.historySkipped.toLocaleString()} left out by your history settings` : ''}.
      </p>
      {!!run.historyCoverage?.length && (
        <details className="rounded-lg border border-line p-3">
          <summary className="cursor-pointer text-sm text-neutral-200">Dates checked for earlier viewings</summary>
          <ul className="mt-3 space-y-3 text-xs text-neutral-400">
            {run.historyCoverage.map((coverage) => (
              <li key={`${coverage.userId}:${coverage.sectionId}`}>
                <strong className="font-medium text-neutral-300">
                  {userNames.get(coverage.userId) ?? 'Chosen person'} ·{' '}
                  {libraryNames.get(coverage.sectionId) ?? 'Plex library'}
                </strong>
                <br />
                {coverage.from.startsWith('1970-') ? 'Available earlier viewings' : `From ${when(coverage.from)}`} through {when(coverage.through)}
                <br />
                {coverage.complete ? 'This range was checked.' : 'This range is still being checked. Retry the import to continue.'}
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-neutral-400">Plex may no longer have every older viewing, even when a date range has been checked.</p>
        </details>
      )}
      {run.status === 'partial' && (
        <p className="text-sm text-orange-300">
          Some earlier viewings could not be checked yet. Your completed watched choices stay. Retry to continue, or keep updates paused until you are ready.
        </p>
      )}
      {run.reason && (
        <p role="status" className="text-sm text-orange-300">
          {plexReason(run.reason)}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {active && (
          <button type="button" className={secondaryButton} disabled={cancel.isPending} onClick={() => cancel.mutate(run.id)}>
            {cancel.isPending ? 'Cancelling…' : 'Cancel import'}
          </button>
        )}
        {retryable && (
          <button type="button" className={secondaryButton} disabled={retry.isPending || anotherActive} onClick={() => retry.mutate(run.id)}>
            {retry.isPending ? 'Retrying…' : 'Retry import'}
          </button>
        )}
        {!active && run.counts.marked > 0 && (
          <button type="button" className={secondaryButton} disabled={checkUndo.isPending || anotherActive} onClick={() => checkUndo.mutate()}>
            {checkUndo.isPending ? 'Checking undo…' : 'Check undo'}
          </button>
        )}
      </div>
      {notice && (
        <p role="status" className="text-sm text-neutral-200">
          {notice}
        </p>
      )}
      {(cancel.isError || retry.isError || checkUndo.isError) && (
        <ErrorBox error={cancel.error ?? retry.error ?? checkUndo.error} fallback="Couldn’t finish this action. Refresh the connection and try again." />
      )}
      {undoPreview && (
        <PlexConfirmation
          title="Undo imported watched choices?"
          description={`From the import ${when(run.startedAt)}. ${undoPreview.willUndo.toLocaleString()} watched choices can be removed. ${undoPreview.localKept.toLocaleString()} later choices you made here will stay. Updates from Plex pause so the removed choices do not come straight back. Earlier viewings and Plex stay as they are.`}
          confirm={`Undo ${undoPreview.willUndo.toLocaleString()} watched choices`}
          disabled={!undoPreview.willUndo}
          action={() =>
            undo.mutateAsync({ runId: run.id, previewId: undoPreview.id }).then((result) => {
              setNotice(
                `${result.undone.toLocaleString()} watched choices removed; ${result.localKept.toLocaleString()} later choices kept. Updates from Plex are paused.`,
              )
              return result
            })
          }
          onDone={() => {
            setUndoPreview(null)
          }}
          onClose={() => setUndoPreview(null)}
        >
          <p className="mt-3 text-xs text-neutral-400">If this check expires or the connection changes, close it and choose Check undo again.</p>
        </PlexConfirmation>
      )}
    </div>
  )
})

function PlexExcludedItems({ connection, active, onChanged }: { connection: PlexConnection; active: boolean; onChanged: () => void }) {
  const exclusions = useQuery(plexExclusionsQuery(connection.id))
  const save = useSavePlexExclusions(connection.id)
  const [limit, setLimit] = useState(25)
  const items = exclusions.data?.items ?? []
  return (
    <Panel
      title="Items you left out"
      description="These watched choices are not brought across from Plex. Let an item import again to include it in your next check."
    >
      <div className="px-5 pb-5">
        {exclusions.isPending ? (
          <p className="text-sm text-neutral-400">Checking items left out…</p>
        ) : !items.length ? (
          <p className="text-sm text-neutral-400">
            No items were left out. On a check, choose Leave this unwatched for an item you want to keep unwatched here.
          </p>
        ) : (
          <>
            <p className="mb-3 text-sm text-neutral-400">{items.length.toLocaleString()} items left out.</p>
            <ul className="divide-y divide-line">
              {items.slice(0, limit).map((item) => (
                <li key={`${item.userId}:${item.itemType}:${item.itemId}`} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0 flex-1 basis-52">
                    <strong className="break-words text-sm">
                      {'title' in item && typeof item.title === 'string'
                        ? item.title
                        : item.itemType === 'movie'
                          ? 'A movie you left out'
                          : 'An episode you left out'}
                    </strong>
                    {!!item.userName && <p className="text-xs text-neutral-400">For {item.userName}</p>}
                  </div>
                  <button
                    type="button"
                    className={secondaryButton}
                    disabled={active || save.isPending || !exclusions.data}
                    onClick={() => {
                      if (!exclusions.data) return
                      save.mutate(
                        {
                          generation: exclusions.data.generation,
                          items: items.filter(
                            (existing) => existing.userId !== item.userId || existing.itemType !== item.itemType || existing.itemId !== item.itemId,
                          ),
                        },
                        { onSuccess: onChanged },
                      )
                    }}
                  >
                    Let this import again
                  </button>
                </li>
              ))}
            </ul>
            {items.length > limit && (
              <button type="button" className={`${secondaryButton} mt-3`} onClick={() => setLimit(limit + 25)}>
                Show more items
              </button>
            )}
          </>
        )}
        {(exclusions.isError || save.isError) && (
          <ErrorBox
            error={exclusions.error ?? save.error}
            fallback="Couldn’t update the items left out. Refresh the connection and try again."
            retry={() => void exclusions.refetch()}
          />
        )}
      </div>
    </Panel>
  )
}

function ErrorBox({ error, fallback, retry }: { error?: unknown; fallback: string; retry?: () => void }) {
  if (error instanceof ConfirmationCancelled) return null
  const reason = error instanceof Error && /^[a-z_]+$/.test(error.message) ? plexReason(error.message, fallback) : ''
  return (
    <div role="alert" className="rounded-lg border border-orange-900 bg-orange-950/25 p-3 text-sm text-orange-200">
      <p>{reason || fallback}</p>
      {retry && (
        <button type="button" onClick={retry} className={`${secondaryButton} mt-3`}>
          Try again
        </button>
      )}
    </div>
  )
}
