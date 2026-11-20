import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  integrationQuery,
  integrationsQuery,
  seerrUsersQuery,
  testIntegration,
  useConnectWebhook,
  useCreateIntegration,
  useLinkSeerrUser,
  useUpdateIntegration,
  type Integration,
  type IntegrationKind,
} from '../../../api/integrations'
import { librariesQuery, usersQuery } from '../../../api/queries'
import { FlowFrame } from '../../../components/settings/Flow'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Field, HrefLink, LoadingRows, Message, Pill, fieldInput } from '../../../components/settings/Frame'
import { errorText } from '../../../components/settings/hooks'
import { sameValue } from '../../../lib/drafts'
import { clock, defaultAddress } from '../../../lib/integrations'
import {
  APP,
  connectProblem,
  defaultName,
  firstTargetChoice,
  folderRows,
  foldersProblem,
  foldersToSave,
  linkProblem,
  linksToSave,
  mappingProblem,
  peopleDraft,
  rootsLeftOut,
  rowLibrary,
  targetProblem,
  targetToSave,
  testedWording,
  things,
  webhookProblem,
  type FolderRow,
  type Problem,
} from '../../../lib/integrationsSettings'
import { settingsHref } from '../../../lib/settingsRoutes'
import { ProblemMessage, TestCaption } from './parts'
import { toProblem, useAfterSave, type StepFrame } from './shared'

type StepProps<T> = { frame: StepFrame; nextLabel: string; onSaved: (result: T) => void; leaveWhat?: string }

const capital = (s: string) => s[0].toUpperCase() + s.slice(1)

// ---- Connect ------------------------------------------------------------------

/**
 * Connect: name, address and API key, with Test (which saves nothing). The
 * button checks the app again and saves: a new connection, or the changes
 * to the saved one (Back never undoes a save, it reopens it).
 */
export function ConnectStep({ kind, connection, frame, nextLabel, onSaved, leaveWhat }: StepProps<Integration> & { kind: IntegrationKind; connection?: Integration }) {
  const app = APP[kind]
  const list = useQuery(integrationsQuery)
  const [initial] = useState(() => ({
    name: connection?.name ?? defaultName(kind, list.data?.items.map((c) => c.name) ?? []),
    url: connection?.url ?? '',
  }))
  const [name, setName] = useState(initial.name)
  const [url, setUrl] = useState(initial.url)
  const [apiKey, setApiKey] = useState('')
  const [missing, setMissing] = useState<Partial<Record<'name' | 'url' | 'apiKey', string>>>({})
  const [problem, setProblem] = useState<Problem | null>(null)
  const [test, setTest] = useState<{ ok: boolean; problem: Problem } | null>(null)
  const [testing, setTesting] = useState(false)
  const create = useCreateIntegration()
  const update = useUpdateIntegration(connection?.id ?? 0)
  const after = useAfterSave()
  const dirty = !after.saved && (name !== initial.name || url !== initial.url || apiKey !== '')
  const arr = kind !== 'seerr'
  const word = (m: string) => connectProblem(kind, m)

  const runTest = () => {
    setTesting(true)
    setTest(null)
    testIntegration({ kind, url: url.trim(), apiKey: apiKey.trim() || undefined, id: connection?.id })
      .then(
        (r) => setTest({ ok: r.ok, problem: r.ok ? testedWording(r.message) : word(r.message) }),
        (e: unknown) => {
          const p = toProblem(e, word)
          if (p) setTest({ ok: false, problem: p })
        },
      )
      .finally(() => setTesting(false))
  }

  const save = () => {
    const m: typeof missing = {}
    if (arr && !name.trim()) m.name = 'Give the connection a name.'
    if (!url.trim()) m.url = `Enter the address this server reaches ${app} at.`
    if (!connection && !apiKey.trim()) m.apiKey = `Paste ${app}’s API key.`
    setMissing(m)
    setProblem(null)
    if (Object.keys(m).length) return
    const fail = (e: unknown) => setProblem(toProblem(e, word))
    if (connection) {
      const body = {
        ...(name.trim() !== connection.name && arr ? { name: name.trim() } : {}),
        ...(url.trim() !== connection.url ? { url: url.trim() } : {}),
        ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
      }
      if (!Object.keys(body).length) return after.done(() => onSaved(connection))
      update.mutate(body, { onSuccess: (c) => after.done(() => onSaved(c)), onError: fail })
    } else {
      create.mutate({ kind, name: arr ? name.trim() : 'Seerr', url: url.trim(), apiKey: apiKey.trim() }, { onSuccess: (c) => after.done(() => onSaved(c)), onError: fail })
    }
  }

  const field = problem?.field ?? (test && !test.ok ? test.problem.field : undefined)
  return (
    <FlowFrame
      {...frame}
      title={`Connect ${app}`}
      lede={kind === 'seerr'
        ? 'Once connected, requests go through Seerr and are approved there. Requests made before today stay in the Inbox.'
        : `The address this server reaches ${app} at, and an API key from ${app} › Settings › General.`}
      help={
        <>
          <h4>Where’s the API key?</h4>
          <p>{kind === 'seerr' ? 'In Seerr, open Settings › General and copy API Key.' : `In ${app}, open Settings › General and copy API Key under Security.`}</p>
          <h4>Which address?</h4>
          <p>The one this server uses to reach {app}, not the one your browser uses. In Docker that’s often the container’s name.</p>
        </>
      }
      next={{ label: nextLabel, onClick: save, guarded: true, busy: create.isPending || update.isPending, busyLabel: 'Checking…' }}
    >
      <LeaveCheck when={dirty} what={leaveWhat} />
      {arr && (
        <Field label="Name" hint="Shown in Settings and the Inbox." error={missing.name}>
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} className={`${fieldInput} sm:max-w-sm`} autoComplete="off" />}
        </Field>
      )}
      <div className={`grid gap-x-4 sm:grid-cols-2 ${arr ? 'mt-4' : ''}`}>
        <div>
          <Field label="Address" error={missing.url}>
            {(p) => (
              <input {...p} aria-invalid={p['aria-invalid'] || field === 'url' || undefined} value={url} onChange={(e) => setUrl(e.target.value)} placeholder={defaultAddress(kind)}
                className={`${fieldInput} font-mono`} autoComplete="off" spellCheck={false} inputMode="url" />
            )}
          </Field>
        </div>
        <div className="mt-4 sm:mt-0">
          <Field label="API key" error={missing.apiKey} hint={connection ? 'Leave it empty to keep the saved key.' : undefined}>
            {(p) => (
              <input {...p} aria-invalid={p['aria-invalid'] || field === 'apiKey' || undefined} value={apiKey} onChange={(e) => setApiKey(e.target.value)}
                placeholder={connection?.apiKeyHint ? `•••• ${connection.apiKeyHint}` : kind === 'seerr' ? 'From Seerr › Settings › General' : ''}
                className={`${fieldInput} font-mono`} autoComplete="off" spellCheck={false} />
            )}
          </Field>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button guarded busy={testing} busyLabel="Testing…" disabled={!url.trim()} onClick={runTest}>Test</Button>
        <TestCaption />
      </div>
      {test && (
        <div className="mt-4">
          {test.ok ? <Message tone="ok" title={test.problem.title}>{test.problem.body}</Message> : <ProblemMessage problem={test.problem} />}
        </div>
      )}
      <ProblemMessage problem={problem} className="mt-4" />
    </FlowFrame>
  )
}

// ---- Folders ------------------------------------------------------------------

/**
 * Folders (path mappings): why they exist, the saved ones, the suggestions
 * the server works out, and each root folder outside every library, which
 * may be mapped or left.
 */
export function FoldersStep({ connection: c, frame, nextLabel, onSaved, leaveWhat }: StepProps<Integration> & { connection: Integration }) {
  const app = APP[c.kind]
  const detail = useQuery(integrationQuery(c.id))
  const libraries = useQuery(librariesQuery)
  const [rows, setRows] = useState<FolderRow[] | null>(null)
  const [initial, setInitial] = useState<FolderRow[] | null>(null)
  const [problem, setProblem] = useState<Problem | null>(null)
  const update = useUpdateIntegration(c.id)
  const after = useAfterSave()
  const listId = useId()
  if (rows === null && !detail.isPending) {
    const first = folderRows(c.pathMap, detail.data?.details)
    setRows(first)
    setInitial(first)
  }
  const libs = libraries.data?.items ?? []
  const details = detail.data?.details
  const dirty = !after.saved && rows !== null && !sameValue(rows, initial)
  const set = (i: number, patch: Partial<FolderRow>) => setRows((r) => r && r.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  const left = rows && details ? rootsLeftOut(rows, details.rootFolders, libs) : []

  const save = () => {
    if (!rows) return
    const half = mappingProblem(rows)
    setProblem(half)
    if (half) return
    const pathMap = foldersToSave(rows)
    if (sameValue(pathMap, c.pathMap)) return after.done(() => onSaved(c))
    update.mutate({ pathMap }, { onSuccess: (saved) => after.done(() => onSaved(saved)), onError: (e) => setProblem(toProblem(e, foldersProblem)) })
  }

  return (
    <FlowFrame
      {...frame}
      title={`Where ${app}’s folders are on this server`}
      lede={`${app} and this server can see the same files under different folder names. Line them up so a file ${app} imports is matched to the exact ${things(c.kind).one}, without guessing from its name.`}
      next={{ label: nextLabel, onClick: save, guarded: true, busy: update.isPending, busyLabel: 'Saving…', disabled: rows === null }}
    >
      <LeaveCheck when={dirty} what={leaveWhat} />
      {rows === null ? (
        <LoadingRows rows={2} />
      ) : (
        <div className="space-y-4">
          {c.status.webhook.outside > 0 && (
            <Message tone="warn" title={`${c.status.webhook.outside === 1 ? '1 event was' : `${c.status.webhook.outside} events were`} for folders outside your libraries`}>
              {app} sent {c.status.webhook.outside === 1 ? 'it' : 'them'} for a folder this server can’t place. Check the mappings below.
            </Message>
          )}
          {detail.data?.detailsError && (
            <Message tone="warn" title={`Couldn’t read ${app}’s root folders`} actions={<Button small onClick={() => void detail.refetch()}>Try again</Button>}>
              {errorText(new Error(detail.data.detailsError))} You can still type mappings.
            </Message>
          )}
          <div className="rounded-xl border border-line bg-surface">
            <div aria-hidden="true" className="hidden grid-cols-[minmax(0,1fr)_minmax(0,1fr)_11rem_2.75rem] gap-3 border-b border-line px-4 py-2.5 text-xs font-semibold tracking-wide text-neutral-400 uppercase sm:grid">
              <span>{app} sees</span><span>This server sees</span><span /><span />
            </div>
            <ul className="divide-y divide-line">
              {rows.map((row, i) => {
                const lib = rowLibrary(row, libs)
                return (
                  <li key={i} className="grid gap-x-3 gap-y-2 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_11rem_2.75rem] sm:items-center">
                    <label className="block min-w-0">
                      <span className="mb-1 block text-xs text-neutral-400 sm:sr-only">{app} sees</span>
                      <input value={row.from} onChange={(e) => set(i, { from: e.target.value })} placeholder={c.kind === 'radarr' ? '/movies' : '/tv'}
                        className={`${fieldInput} font-mono`} autoComplete="off" spellCheck={false} />
                    </label>
                    <label className="block min-w-0">
                      <span className="mb-1 block text-xs text-neutral-400 sm:sr-only">This server sees</span>
                      <input value={row.to} list={listId} onChange={(e) => set(i, { to: e.target.value })} placeholder="Choose a folder…"
                        className={`${fieldInput} font-mono`} autoComplete="off" spellCheck={false} />
                    </label>
                    <span className="flex min-w-0 items-center">
                      {lib ? <Pill tone="ok">{row.suggested ? 'Suggested · ' : ''}in {lib.name}</Pill> : (row.to.trim() || row.root) ? <Pill tone="warn">Outside every library</Pill> : null}
                    </span>
                    <span className="flex justify-end">
                      <Button small variant="quiet" aria-label={`Remove the mapping for ${row.from || 'this row'}`} onClick={() => setRows(rows.filter((_, j) => j !== i))}>✕</Button>
                    </span>
                  </li>
                )
              })}
            </ul>
            <div className="border-t border-line px-4 py-3">
              <Button small onClick={() => setRows([...rows, { from: '', to: '' }])}>Add a mapping</Button>
            </div>
          </div>
          <datalist id={listId}>{libs.map((l) => <option key={l.id} value={l.path}>{l.name}</option>)}</datalist>
          {left.map((path) => (
            <Message key={path} tone="warn" title={`${path} isn’t in any library here`}>
              {capital(things(c.kind).many)} {app} puts there won’t show up. Map it to a folder inside a library, or{' '}
              <HrefLink href={settingsHref('libraries', 'add')}>add a library</HrefLink> for it and come back. You can also leave it.
            </Message>
          ))}
          <ProblemMessage problem={problem} />
        </div>
      )}
    </FlowFrame>
  )
}

// ---- Requests -----------------------------------------------------------------

/** Requests: the one target that is used, and it says so. */
export function RequestsStep({ connection: c, frame, nextLabel, onSaved, inFlow, leaveWhat }: StepProps<Integration> & { connection: Integration; inFlow: boolean }) {
  const app = APP[c.kind]
  const t = things(c.kind)
  const detail = useQuery(integrationQuery(c.id))
  const libraries = useQuery(librariesQuery)
  const details = detail.data?.details
  const [choice, setChoice] = useState<{ rootFolder: string; qualityProfileId: number } | null>(null)
  const [initial, setInitial] = useState<typeof choice>(null)
  const [problem, setProblem] = useState<Problem | null>(null)
  const update = useUpdateIntegration(c.id)
  const after = useAfterSave()
  if (choice === null && details) {
    const first = firstTargetChoice(c.targets, details.rootFolders, details.qualityProfiles)
    setChoice(first)
    setInitial(first)
  }
  const dirty = !after.saved && choice !== null && !sameValue(choice, initial)
  const libs = libraries.data?.items ?? []
  const ownLibraries = libs.filter((l) => l.type === (c.kind === 'radarr' ? 'movie' : 'tv'))

  const save = () => {
    if (!choice || !details) return
    const p = targetProblem(c.kind, choice, inFlow)
    setProblem(p)
    if (p) return
    const targets = targetToSave(c.targets, choice, details.rootFolders, ownLibraries.map((l) => l.id))
    if (sameValue(targets, c.targets)) return after.done(() => onSaved(c))
    update.mutate({ targets }, {
      onSuccess: (saved) => after.done(() => onSaved(saved)),
      onError: (e) => setProblem(toProblem(e, (m) => (/root folder and a quality/i.test(m) ? targetProblem(c.kind, { rootFolder: '', qualityProfileId: 0 }, true)! : { title: 'The request target wasn’t saved', body: errorText(e) }))),
    })
  }

  return (
    <FlowFrame
      {...frame}
      title={`Where requested ${t.many} go`}
      lede={`When someone asks for a ${t.one}, ${app} adds it to this folder with this quality. Without a target, people can’t request ${t.many}.`}
      next={{
        label: nextLabel, onClick: save, guarded: true, busy: update.isPending, busyLabel: 'Saving…',
        disabled: !details, reason: detail.isPending ? undefined : !details ? `Needs ${app}’s folders and quality profiles.` : undefined,
      }}
    >
      <LeaveCheck when={dirty} what={leaveWhat} />
      {detail.isPending ? (
        <LoadingRows rows={1} />
      ) : !details || !choice ? (
        <Message tone="warn" title={`Couldn’t read ${app}’s folders and quality profiles`} actions={<Button small onClick={() => void detail.refetch()}>Try again</Button>}>
          {detail.data?.detailsError ? errorText(new Error(detail.data.detailsError)) : detail.isError ? errorText(detail.error) : ''} Check {app} is running, then try again.
        </Message>
      ) : (
        <div className="space-y-5">
          <div className="grid gap-x-4 sm:grid-cols-2">
            <div>
              <Field label={`${app} folder`} hint={`${app}’s root folders.`}>
                {(p) => (
                  <select {...p} value={choice.rootFolder} onChange={(e) => setChoice({ ...choice, rootFolder: e.target.value })} className={`${fieldInput} font-mono`}>
                    <option value="">{!inFlow && c.targets.length ? 'Don’t take requests' : 'Choose a folder…'}</option>
                    {details.rootFolders.map((r) => {
                      const lib = libs.find((l) => l.id === r.libraryId)
                      return <option key={r.path} value={r.path}>{r.path}{lib ? ` · in ${lib.name}` : ''}</option>
                    })}
                  </select>
                )}
              </Field>
            </div>
            <div className="mt-4 sm:mt-0">
              <Field label="Quality" hint={`${app}’s quality profiles.`}>
                {(p) => (
                  <select {...p} value={choice.qualityProfileId || ''} disabled={!choice.rootFolder && !inFlow && c.targets.length > 0}
                    onChange={(e) => setChoice({ ...choice, qualityProfileId: Number(e.target.value) })} className={fieldInput}>
                    <option value="">Choose a quality…</option>
                    {details.qualityProfiles.map((q) => <option key={q.id} value={q.id}>{q.name}</option>)}
                  </select>
                )}
              </Field>
            </div>
          </div>
          <Message tone="info" title={`Every ${t.one} request uses this target`}>That’s how the server works today, whichever library the request was made from.</Message>
          <ProblemMessage problem={problem} />
        </div>
      )}
    </FlowFrame>
  )
}

// ---- Webhook ------------------------------------------------------------------

/** Webhook: the app sends a test event before anything is saved, so a refusal names the address it couldn't open. */
export function WebhookStep({ connection: c, frame, nextLabel, onSaved, leaveWhat }: StepProps<Integration> & { connection: Integration & { kind: 'radarr' | 'sonarr' } }) {
  const app = APP[c.kind]
  const list = useQuery(integrationsQuery)
  const addresses = list.data?.serverAddresses ?? []
  const [typed, setTyped] = useState<string | null>(null)
  const address = typed ?? addresses[0] ?? ''
  const [problem, setProblem] = useState<Problem | null>(null)
  const [missing, setMissing] = useState('')
  const connect = useConnectWebhook(c.id)
  const after = useAfterSave()
  const listId = useId()
  const dirty = !after.saved && typed !== null && typed !== (addresses[0] ?? '')
  const w = c.status.webhook

  const save = () => {
    setProblem(null)
    if (!address.trim()) return setMissing(`Enter an address ${app} can open.`)
    setMissing('')
    connect.mutate(address.trim(), {
      onSuccess: (saved) => after.done(() => onSaved(saved)),
      onError: (e) => setProblem(toProblem(e, (m, status) => webhookProblem(c.kind, address.trim(), status, m))),
    })
  }

  return (
    <FlowFrame
      {...frame}
      title={`Tell ${app} how to reach this server`}
      lede={`With a webhook, ${app} tells this server the moment it imports or renames a file, and the library updates at once. Without it, new files show up at the next scan.`}
      next={{ label: nextLabel, onClick: save, guarded: true, busy: connect.isPending, busyLabel: 'Connecting…' }}
    >
      <LeaveCheck when={dirty} what={leaveWhat} />
      <div className="space-y-4">
        {(w.lastTestAt || w.lastEventAt) && (
          <Message tone="ok" title="The webhook is connected">
            {w.lastTestAt ? `A test event arrived at ${clock(w.lastTestAt)}.` : ''} Connecting again points it at the address below.
          </Message>
        )}
        <Field label={`This server’s address, as ${app} sees it`} hint={`Usually the home-network address. ${app} must be able to open it.`} error={missing}>
          {(p) => (
            <input {...p} aria-invalid={p['aria-invalid'] || problem?.field === 'address' || undefined} list={listId} value={address} onChange={(e) => setTyped(e.target.value)}
              className={`${fieldInput} font-mono sm:max-w-md`} autoComplete="off" spellCheck={false} inputMode="url" />
          )}
        </Field>
        <datalist id={listId}>{addresses.map((a) => <option key={a} value={a} />)}</datalist>
        <ProblemMessage problem={problem} />
      </div>
    </FlowFrame>
  )
}

// ---- Link people (Seerr) --------------------------------------------------------

export type PeopleSummary = { linked: number; total: number }

/**
 * Link people: who each person requests as in Seerr, with the same
 * suggestions the Users tab offers. Each link saves as the person's own
 * change, which doesn't ask for the password.
 */
export function PeopleStep({ frame, nextLabel, onSaved, skip, leaveWhat }: StepProps<PeopleSummary> & { skip?: StepFrame['skip'] }) {
  const users = useQuery(usersQuery)
  const seerrUsers = useQuery(seerrUsersQuery)
  const link = useLinkSeerrUser()
  const [draft, setDraft] = useState<Record<number, { seerrUserId: number; suggested: boolean }> | null>(null)
  const [touched, setTouched] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)
  const [saving, setSaving] = useState(false)
  const after = useAfterSave()
  const people = users.data?.items ?? []
  const choices = seerrUsers.data?.items
  if (draft === null && users.data && choices) setDraft(peopleDraft(people, choices))

  const save = async () => {
    if (!draft || !choices) return
    setProblem(null)
    setSaving(true)
    for (const change of linksToSave(people, draft)) {
      try {
        await link.mutateAsync(change)
      } catch (e) {
        const person = people.find((p) => p.id === change.id)
        setProblem(toProblem(e, (m) => linkProblem(m, person?.name ?? 'this person', choices)))
        const fresh = await seerrUsers.refetch()
        if (fresh.data) setDraft((d) => d && { ...d, [change.id]: { seerrUserId: person?.seerrUserId ?? 0, suggested: false } })
        await users.refetch()
        setSaving(false)
        return
      }
    }
    setSaving(false)
    const linked = people.filter((p) => (draft[p.id]?.seerrUserId ?? 0) > 0).length
    after.done(() => onSaved({ linked, total: people.length }))
  }

  const failed = users.isError ? users.error : seerrUsers.isError ? seerrUsers.error : null
  return (
    <FlowFrame
      {...frame}
      skip={skip}
      title="Who is who in Seerr?"
      lede="Requests are made as the Seerr user you choose. Anyone without one can’t request."
      next={{ label: nextLabel, onClick: () => void save(), busy: saving, busyLabel: 'Saving…', disabled: !draft }}
    >
      <LeaveCheck when={!after.saved && touched} what={leaveWhat} />
      {failed ? (
        <Message tone="err" title="Couldn’t read Seerr’s users" actions={<Button small onClick={() => { void users.refetch(); void seerrUsers.refetch() }}>Try again</Button>}>
          {errorText(failed)}
        </Message>
      ) : !draft || !choices ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="space-y-4">
          <div className="rounded-xl border border-line bg-surface">
            <div aria-hidden="true" className="hidden grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_9rem] gap-3 border-b border-line px-4 py-2.5 text-xs font-semibold tracking-wide text-neutral-400 uppercase sm:grid">
              <span>Person</span><span>Seerr user</span><span />
            </div>
            <ul className="divide-y divide-line">
              {people.map((p) => {
                const d = draft[p.id] ?? { seerrUserId: 0, suggested: false }
                const chosen = choices.find((s) => s.id === d.seerrUserId)
                return (
                  <li key={p.id} className="grid gap-x-3 gap-y-2 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_9rem] sm:items-center">
                    <div className="min-w-0">
                      <b className="font-semibold break-words">{p.name}</b>
                      {p.isAdmin && <div className="text-[13px] text-neutral-400">Admin</div>}
                    </div>
                    <label className="block min-w-0">
                      <span className="mb-1 block text-xs text-neutral-400 sm:sr-only">Seerr user for {p.name}</span>
                      <select value={d.seerrUserId} onChange={(e) => { setTouched(true); setDraft({ ...draft, [p.id]: { seerrUserId: Number(e.target.value), suggested: false } }) }} className={fieldInput}>
                        <option value={0}>No Seerr user · can’t request</option>
                        {d.seerrUserId > 0 && !chosen && <option value={d.seerrUserId}>Gone from Seerr (#{d.seerrUserId})</option>}
                        {choices.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                      </select>
                    </label>
                    <span className="text-[13px] text-neutral-400">
                      {d.suggested ? <Pill tone="ok">Same name</Pill> : chosen && !chosen.canRequest ? <span className="text-warn">Can’t request in Seerr</span> : d.seerrUserId === 0 ? 'No name matches' : null}
                    </span>
                  </li>
                )
              })}
            </ul>
          </div>
          <ProblemMessage problem={problem} />
        </div>
      )}
    </FlowFrame>
  )
}
