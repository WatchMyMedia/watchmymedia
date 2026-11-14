import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { plexConnectionsQuery, plexExclusionsQuery, plexRunsQuery, useDisconnectPlex, usePlexSync, type PlexConnection } from '../../../api/plex'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, NextStep, Section, SetupGuide, type GuideItem, type PillTone } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { plexHasApprovedImport, plexRunActive } from '../../../lib/plex'
import {
  canRetry,
  count,
  disconnectMessage,
  nameList,
  originHost,
  plexGuide,
  plexHubState,
  plexNextStep,
  pauseNeedsDialog,
  problemBanner,
  reconnectBanner,
  retryUntil,
  syncLine,
  syncMessage,
  type PlexMessage,
} from '../../../lib/plexSettings'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { PlexNote } from './parts'
import { EarlierImports, LeftOutItems, RunDetails, RunSummary } from './Runs'
import { forgetConnection, keptImport, plexHref, useClock, useLinkedNames, usePlexConnection } from './state'

/** A result that stays at the top of the hub until it is closed (board plex-banners). */
type Banner = { kind: 'disconnected'; name: string } | { kind: 'paused' } | { kind: 'message'; message: PlexMessage; retry?: () => void }

const LEDE = 'Bring your watched movies and episodes across from a Plex server you own. Your choices in WatchMyMedia come first, and Plex stays as it is.'

/** The Plex tab's hub (§5.1): not connected, connected with links or an
 * import still to do, or set up and keeping up to date. */
export function PlexHub() {
  const { query, connection } = usePlexConnection()
  const [banner, setBanner] = useState<Banner | null>(null)
  const crumbs = settingsCrumbs('plex')
  if (query.isPending)
    return (
      <HubPage crumbs={crumbs} title="Plex" lede="Checking your Plex connection…">
        <LoadingRows rows={3} />
      </HubPage>
    )
  if (query.isError)
    return (
      <HubPage crumbs={crumbs} title="Plex">
        <Message tone="err" title="Couldn’t check your Plex connection" actions={<Button small onClick={() => void query.refetch()}>Try again</Button>}>
          Nothing was changed. Check this server’s connection, then try again.
        </Message>
      </HubPage>
    )
  if (!connection) return <NotConnected banner={banner} onClose={() => setBanner(null)} />
  return <Connected key={connection.id} connection={connection} banner={banner} setBanner={setBanner} />
}

function Closable({ tone, title, children, actions, onClose }: { tone: 'ok' | 'warn' | 'err'; title: ReactNode; children: ReactNode; actions?: ReactNode; onClose: () => void }) {
  return (
    <Message tone={tone} title={title} actions={<>{actions}<Button small variant="quiet" onClick={onClose}>Close</Button></>}>
      {children}
    </Message>
  )
}

function NotConnected({ banner, onClose }: { banner: Banner | null; onClose: () => void }) {
  const go = useGo()
  const guide = plexGuide(undefined, false)
  return (
    <HubPage
      crumbs={settingsCrumbs('plex')}
      title="Plex"
      lede={LEDE}
      pill={{ tone: 'off', label: 'Not connected' }}
      banner={banner?.kind === 'disconnected' && (
        <Closable tone="ok" title={`${banner.name} is disconnected`} onClose={onClose}>
          Everything imported stays. To remove this server from Plex’s device list, open Plex › Settings › Authorized devices. You can connect again any time.
        </Closable>
      )}
    >
      <NextStep title="Connect your Plex server" action={<Button variant="primary" guarded onClick={() => go(plexHref('connect'))}>Connect Plex</Button>}>
        Sign in on Plex’s own page, then pick the server to read from. About two minutes. Nothing is imported yet.
      </NextStep>
      <SetupGuide
        done={guide.done}
        total={guide.total}
        items={[
          { key: 'connect', title: 'Connect', description: 'Sign in to Plex and choose one server.', state: guide.state('connect') },
          { key: 'link', title: 'Link people and libraries', description: 'Say which Plex person is which account here, and which library is which.', state: guide.state('link'), waits: 'After connecting' },
          { key: 'import', title: 'Check and import', description: 'See what would change, then import once or keep up to date.', state: guide.state('import'), waits: 'After linking' },
        ]}
      />
      <Section title="What stays the same">
        <Card padded>
          <ul className="list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
            <li>Nothing is changed or played on Plex.</li>
            <li>Anything you already marked here, watched or unwatched, is kept.</li>
            <li>Earlier viewings from Plex are kept apart, and don’t count as plays here.</li>
            <li>You can undo an import afterwards.</li>
          </ul>
        </Card>
      </Section>
    </HubPage>
  )
}

function Connected({ connection, banner, setBanner }: { connection: PlexConnection; banner: Banner | null; setBanner: (b: Banner | null) => void }) {
  const go = useGo()
  const qc = useQueryClient()
  const id = connection.id
  const name = connection.name
  const runs = useQuery(plexRunsQuery(id))
  const exclusions = useQuery({ ...plexExclusionsQuery(id), enabled: connection.linkedUsers > 0 })
  const names = useLinkedNames(connection)
  const sync = usePlexSync(id)
  const disconnect = useDisconnectPlex(id)
  const [dialog, setDialog] = useState<'pause' | 'disconnect' | null>(null)
  const [open, setOpen] = useState<'details' | 'left-out' | 'imports' | null>(null)
  const now = useClock(30_000)
  // A check left running in this tab is picked up rather than started again.
  const [resume] = useState(() => !!keptImport(id)?.previewId)

  const items = runs.data?.items ?? []
  const activeRun = runs.data?.active ?? items.find((r) => plexRunActive(r.status))
  const importing = !!activeRun
  const latest = activeRun ?? items[0]
  const lastFinished = items.find((r) => r.finishedAt)
  const approved = plexHasApprovedImport(connection, items)
  const state = plexHubState(connection, approved)
  const guide = plexGuide(connection, approved)
  const next = plexNextStep(connection, approved, importing)
  const ready = connection.status === 'connected'

  // A finished import changes the connection (approved, keeping up to date).
  const latestId = items[0]?.id
  const latestStatus = items[0]?.status
  useEffect(() => {
    if (latestId && latestStatus && !plexRunActive(latestStatus)) void qc.invalidateQueries({ queryKey: plexConnectionsQuery.queryKey })
  }, [latestId, latestStatus, qc])

  const setSync = (enabled: boolean) => {
    setBanner(null)
    sync.mutate(
      { generation: connection.generation, enabled },
      {
        onSuccess: () => {
          setDialog(null)
          setBanner(enabled ? null : { kind: 'paused' })
        },
        onError: (error) => {
          if (dialog) return
          const message = syncMessage(error, !enabled, name)
          if (message) setBanner({ kind: 'message', message, retry: () => setSync(enabled) })
        },
      },
    )
  }
  const pause = () => (pauseNeedsDialog(connection.syncEnabled, importing) ? setDialog('pause') : setSync(false))

  // One banner at a time: what the admin just did, then what went wrong later.
  const stopped = latest && ['failed', 'partial'].includes(latest.status) && canRetry(latest, connection, now).ok ? latest : undefined
  let top: ReactNode = null
  if (banner?.kind === 'paused')
    top = (
      <Closable tone="ok" title="Updates from Plex are paused" onClose={() => setBanner(null)} actions={approved && ready && <Button small busy={sync.isPending} onClick={() => setSync(true)}>Resume</Button>}>
        Completed imports stay. Nothing more comes across until you choose Resume.
      </Closable>
    )
  else if (banner?.kind === 'message')
    top = <PlexNote message={banner.message} focus busy={sync.isPending} on={{ 'try-again': banner.retry, 'check-again': () => go(plexHref('import')) }} />
  else if (!ready) top = <PlexNote message={reconnectBanner(name)} />
  else if (connection.problem) top = <PlexNote message={problemBanner(connection.problem)} />
  else if (stopped)
    top = (
      <PlexNote
        message={{
          tone: 'warn',
          title: 'The last import stopped part-way',
          body: `${count(stopped.counts.marked, 'watched mark')} ${stopped.counts.marked === 1 ? 'was' : 'were'} imported and ${stopped.counts.marked === 1 ? 'is' : 'are'} kept. You can retry until ${retryUntil(stopped.startedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'long' })}.`,
          fix: { do: 'see-import', label: 'See the import' },
        }}
        on={{ 'see-import': () => go(plexHref('import', 'import', stopped.id)) }}
      />
    )

  const pill: { tone: PillTone; label: string } = !ready
    ? { tone: 'warn', label: 'Needs you to sign in again' }
    : importing
      ? { tone: 'now', label: 'Importing' }
      : state === 'set'
        ? connection.syncEnabled ? { tone: 'ok', label: 'Keeping up to date' } : { tone: 'off', label: 'Paused' }
        : { tone: 'ok', label: 'Connected' }

  const lede = state === 'set'
    ? `${name} · ${count(connection.linkedUsers, 'person', 'people')} · ${count(connection.linkedLibraries, 'library', 'libraries')}`
    : `${name} · connected`

  const guideItems: GuideItem[] = [
    {
      key: 'connect',
      title: 'Connect',
      description: `${name} at ${originHost(connection.origin)}.`,
      state: guide.state('connect'),
      action: <Button small guarded onClick={() => go(plexHref('connect'))}>Reconnect</Button>,
    },
    {
      key: 'link',
      title: 'Link people and libraries',
      description: guide.state('link') === 'done'
        ? names.ready ? `${nameList(names.accounts)} · ${nameList(names.libraries)}.` : `${count(connection.linkedUsers, 'person', 'people')} · ${count(connection.linkedLibraries, 'library', 'libraries')}.`
        : 'Not linked yet.',
      state: guide.state('link'),
      action: guide.state('link') === 'done' && <Button small onClick={() => go(plexHref('link'))}>Change links</Button>,
    },
    {
      key: 'import',
      title: 'Check and import',
      description: guide.state('import') === 'done' ? 'Run again any time. It only adds.' : 'See what would change, then import.',
      state: guide.state('import'),
      waits: 'After linking',
      action: guide.state('import') === 'done' && <Button small disabled={importing} onClick={() => go(plexHref('import'))}>Check again</Button>,
    },
  ]

  const leftOut = exclusions.data?.items.length ?? 0
  return (
    <HubPage crumbs={settingsCrumbs('plex')} title="Plex" lede={lede} pill={pill} banner={top}>
      {next === 'running' && activeRun && (
        <NextStep title="An import is running" action={<Button variant="primary" onClick={() => go(plexHref('import', 'import'))}>See the import</Button>}>
          {count(activeRun.counts.marked, 'watched mark')} so far. You can leave it running; it carries on.
        </NextStep>
      )}
      {next === 'link' && (
        <NextStep title="Link people and libraries" action={<Button variant="primary" onClick={() => go(plexHref('link'))}>Link people and libraries</Button>}>
          {names.peopleFound !== undefined && names.sectionsFound !== undefined
            ? `${count(names.peopleFound, 'person', 'people')} and ${count(names.sectionsFound, 'library', 'libraries')} were found on ${name}. `
            : ''}
          Nothing is read until you link them.
        </NextStep>
      )}
      {next === 'check' && (
        <NextStep title="Check what will change" action={<Button variant="primary" onClick={() => go(resume ? plexHref('import', 'check') : plexHref('import'))}>Check what will change</Button>}>
          It reads Plex and writes nothing. Then import once, or import and keep up to date.
        </NextStep>
      )}

      {(state === 'set' || connection.syncEnabled || latest) && (
        <Card>
          {(state === 'set' || connection.syncEnabled) && (
            connection.syncEnabled && ready ? (
              <ItemRow
                title="Keeping up to date"
                detail={syncLine(lastFinished?.finishedAt, now)}
                control={<Button small busy={sync.isPending && !dialog} onClick={pause}>Pause</Button>}
              />
            ) : (
              <ItemRow
                title="Updates from Plex are paused"
                detail={approved ? 'Completed imports stay. Nothing more comes across until you choose Resume.' : 'Choose Import and keep up to date on your next import to turn updates on.'}
                control={approved && (
                  <Button small busy={sync.isPending} disabled={!ready || importing} onClick={() => setSync(true)}>Resume</Button>
                )}
              />
            )
          )}
          {latest && (
            <ItemRow
              title={importing ? 'Import running' : 'Last import'}
              detail={<RunSummary run={latest} />}
              control={importing ? (
                <Button small onClick={() => go(plexHref('import', 'import'))}>See the import</Button>
              ) : (
                <>
                  {latest.counts.marked > 0 && <Button small onClick={() => go(plexHref('undo', latest.id))}>Undo…</Button>}
                  <Button small aria-expanded={open === 'details'} onClick={() => setOpen(open === 'details' ? null : 'details')}>{open === 'details' ? 'Hide details' : 'Details'}</Button>
                </>
              )}
            >
              {open === 'details' && !importing && <RunDetails connection={connection} run={latest} />}
            </ItemRow>
          )}
          {runs.isError && (
            <div className="px-5 py-3.5">
              <Message tone="err" title="Couldn’t read the imports" actions={<Button small onClick={() => void runs.refetch()}>Try again</Button>}>The saved results are still there.</Message>
            </div>
          )}
        </Card>
      )}

      <SetupGuide done={guide.done} total={guide.total} items={guideItems} />

      <Section title="More">
        <Card>
          {connection.linkedUsers > 0 && (
            <ItemRow
              title="Items you left out"
              detail={exclusions.data ? (leftOut ? `${count(leftOut, 'item stays', 'items stay')} unwatched here.` : 'None yet.') : 'Items you chose to leave unwatched.'}
              control={<Button small aria-expanded={open === 'left-out'} onClick={() => setOpen(open === 'left-out' ? null : 'left-out')}>{open === 'left-out' ? 'Hide' : 'Show'}</Button>}
            >
              {open === 'left-out' && <LeftOutItems connection={connection} importing={importing} />}
            </ItemRow>
          )}
          {items.length > 0 && (
            <ItemRow
              title="Earlier imports"
              detail="Every import, with its details and Undo."
              control={<Button small aria-expanded={open === 'imports'} onClick={() => setOpen(open === 'imports' ? null : 'imports')}>{open === 'imports' ? 'Hide' : 'Show'}</Button>}
            >
              {open === 'imports' && <EarlierImports connection={connection} runs={runs} />}
            </ItemRow>
          )}
          <ItemRow
            href="/activity/plex-history"
            title="Earlier viewings from Plex"
            detail={connection.historyCapability === 'available'
              ? 'Read during imports, for people who record history. Kept apart from plays here.'
              : connection.historyCapability === 'unavailable'
                ? `${name} doesn’t share earlier viewings with this server. Watched marks can still come across.`
                : 'Not checked yet. Read during imports, for people who record history.'}
          />
          {ready && (approved || connection.problem === 'source_identity_changed') && (
            <ItemRow href={plexHref('restore')} title="Did you restore or rebuild Plex?" detail="Check a restored server before it is read again." />
          )}
          <ItemRow
            title="Disconnect Plex"
            detail={`Stop reading ${name}. Anything imported stays.`}
            control={<Button small variant="danger" guarded onClick={() => { disconnect.reset(); setDialog('disconnect') }}>Disconnect…</Button>}
          />
        </Card>
      </Section>

      {dialog === 'pause' && (
        <ConfirmDialog
          title="Pause updates from Plex?"
          lede={`An import is running. Pausing cancels it. The ${count(activeRun?.counts.marked ?? 0, 'watched mark')} it has already made ${activeRun?.counts.marked === 1 ? 'is' : 'are'} kept.`}
          cancelLabel="Keep running"
          confirmLabel="Pause and cancel import"
          danger={false}
          busy={sync.isPending}
          error={syncMessage(sync.error, true, name)?.body}
          onConfirm={() => setSync(false)}
          onClose={() => { sync.reset(); setDialog(null) }}
        >
          <p className="text-sm text-neutral-300">Nothing more comes across from Plex until you choose Resume.</p>
        </ConfirmDialog>
      )}
      {dialog === 'disconnect' && (
        <ConfirmDialog
          title={`Disconnect ${name}?`}
          stays={['Every watched mark and earlier viewing already imported.']}
          deleted={[`The links for ${nameList(names.accounts) || 'the people you linked'}, and the library links.`]}
          stops={['Updates from Plex.']}
          confirmLabel="Disconnect"
          guarded
          busy={disconnect.isPending}
          error={disconnectMessage(disconnect.error, name)?.body}
          onConfirm={() =>
            disconnect.mutate(undefined, {
              onSuccess: () => {
                forgetConnection(id)
                setDialog(null)
                setBanner({ kind: 'disconnected', name })
              },
            })
          }
          onClose={() => setDialog(null)}
        >
          <p className="mt-4 text-sm text-neutral-400">Plex keeps listing this server as a device until you remove it there, under Plex › Settings › Authorized devices.</p>
        </ConfirmDialog>
      )}
    </HubPage>
  )
}
