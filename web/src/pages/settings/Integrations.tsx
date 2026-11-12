import { visibleConfirmationError } from '../../api/withConfirm'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  appName,
  integrationQuery,
  integrationsQuery,
  testIntegration,
  useConnectWebhook,
  useCreateIntegration,
  useDeleteIntegration,
  useUpdateIntegration,
  type Integration,
  type IntegrationKind,
  type PathMapping,
  type RequestTarget,
} from '../../api/integrations'
import { librariesQuery } from '../../api/queries'
import { requestSettingsQuery, useUpdateRequestSettings } from '../../api/requests'
import { PageHeader, Panel, Row, primaryButton, secondaryButton, textInput } from '../../components/SettingsPanel'
import { cleanMappings, clock, connectionStatus, defaultAddress, freshSuggestions, itemWord, tileLetters, unmappedRoots } from '../../lib/integrations'
import { ServerOnly } from './SettingsLayout'

type Editing = { id: number } | { kind: IntegrationKind } | null

/** Settings › Integrations: Radarr and Sonarr connections, their path mappings and webhooks, and Seerr (PLAN-ARR.md §3, §5, §7.3). */
export function IntegrationsSettings() {
  const [editing, setEditing] = useState<Editing>(null)
  const list = useQuery(integrationsQuery)
  const hasSeerr = list.data?.items.some((c) => c.kind === 'seerr') ?? true
  return (
    <>
      <PageHeader
        title="Integrations"
        actions={
          <>
            <button type="button" className={secondaryButton} onClick={() => setEditing({ kind: 'radarr' })}>
              Add Radarr
            </button>
            <button type="button" className={secondaryButton} onClick={() => setEditing({ kind: 'sonarr' })}>
              Add Sonarr
            </button>
            {!hasSeerr && (
              <button type="button" className={secondaryButton} onClick={() => setEditing({ kind: 'seerr' })}>
                Connect Seerr
              </button>
            )}
          </>
        }
      >
        Radarr and Sonarr tell this server the moment they import, upgrade, rename or delete something, so it shows up here at once, and the files they
        imported match exactly. Seerr, when connected, takes over requests.
      </PageHeader>
      <ServerOnly>
        <Connections editing={editing} setEditing={setEditing} />
      </ServerOnly>
    </>
  )
}

function Connections({ editing, setEditing }: { editing: Editing; setEditing: (e: Editing) => void }) {
  const list = useQuery(integrationsQuery)
  if (list.isPending) return <p className="text-neutral-400">Loading…</p>
  if (list.isError) return <p className="text-red-400">{list.error.message}</p>
  const items = list.data.items
  const open = editing && 'id' in editing ? items.find((c) => c.id === editing.id) : undefined
  return (
    <div className="space-y-6">
      <Panel title="Connections">
        {items.length === 0 && <p className="border-t border-line px-5 py-4 text-sm text-neutral-400">Nothing is connected yet. Add Radarr or Sonarr above, or connect Seerr.</p>}
        {items.map((c) => (
          <ConnectionRow key={c.id} c={c} editing={open?.id === c.id} onEdit={() => setEditing(open?.id === c.id ? null : { id: c.id })} />
        ))}
      </Panel>
      {editing && 'kind' in editing && <NewConnection kind={editing.kind} onDone={setEditing} />}
      {open && open.kind === 'seerr' && <EditSeerr key={open.id} c={open} onClose={() => setEditing(null)} />}
      {open && open.kind !== 'seerr' && <EditConnection key={open.id} c={open} addresses={list.data.serverAddresses} onClose={() => setEditing(null)} />}
      {items.length > 0 && <RequestsPanel seerr={items.some((c) => c.kind === 'seerr')} />}
    </div>
  )
}

/** Who needs approval; with Seerr connected, approval happens there instead. */
function RequestsPanel({ seerr }: { seerr: boolean }) {
  if (seerr) {
    return (
      <Panel title="Requests" description="What happens when someone asks for a movie or a season.">
        <p className="flex items-center gap-2.5 border-t border-line px-5 py-4 text-[15px] text-neutral-200">
          <InfoIcon />
          Requests go to Seerr. Approval, quotas and notifications happen there.
        </p>
      </Panel>
    )
  }
  return <ApprovalPanel />
}

function ApprovalPanel() {
  const settings = useQuery(requestSettingsQuery)
  const update = useUpdateRequestSettings()
  const approval = settings.data?.approval
  return (
    <Panel title="Requests" description="What happens when someone asks for a movie or a season.">
      <fieldset className="space-y-1 border-t border-line px-5 py-4">
        <legend className="sr-only">Who needs approval</legend>
        {(
          [
            ['admins', 'Admins’ requests go straight through; others wait for approval'],
            ['everyone', 'Everyone’s requests go straight through'],
          ] as const
        ).map(([v, label]) => (
          <label key={v} className="flex min-h-[44px] cursor-pointer items-center gap-3 text-sm">
            <input type="radio" name="request-approval" value={v} checked={approval === v} disabled={update.isPending} onChange={() => update.mutate(v)} className="h-[18px] w-[18px] accent-accent" />
            {label}
          </label>
        ))}
        {update.isError && visibleConfirmationError(update.error) && <p className="text-sm text-orange-300">{update.error.message}</p>}
      </fieldset>
    </Panel>
  )
}

function ConnectionRow({ c, editing, onEdit }: { c: Integration; editing: boolean; onEdit: () => void }) {
  const status = connectionStatus(c)
  const tone = { ok: 'text-emerald-300', warn: 'text-orange-300', off: 'text-neutral-400' }[status.tone]
  const dot = { ok: 'bg-emerald-400', warn: 'bg-warn', off: 'bg-neutral-500' }[status.tone]
  return (
    <div className="flex flex-wrap items-center gap-4 border-t border-line px-5 py-4 first:border-t-0">
      <span aria-hidden="true" className="grid h-10 w-10 shrink-0 place-items-center rounded-lg border border-line-2 bg-surface-2 text-[13px] font-bold">
        {tileLetters(c.kind)}
      </span>
      <div className="flex min-w-0 flex-[999_1_18rem] flex-col gap-0.5">
        <span className="text-[15px]">
          <span className="font-semibold">{c.name}</span>
          {c.status.version && <span className="text-neutral-400"> · v{c.status.version.split('.').slice(0, 3).join('.')}</span>}
        </span>
        <span className={`inline-flex items-center gap-2 text-[13px] ${tone}`}>
          <span aria-hidden="true" className={`h-2 w-2 rounded-full ${dot}`} />
          {status.text}
        </span>
      </div>
      <button type="button" className={editing ? primaryButton : secondaryButton} aria-expanded={editing} onClick={onEdit}>
        Edit
      </button>
    </div>
  )
}

/** Name, address and key, with Test. */
function AppFields({
  kind,
  id,
  name,
  url,
  apiKey,
  keyHint,
  set,
}: {
  kind: IntegrationKind
  id?: number
  name: string
  url: string
  apiKey: string
  keyHint?: string
  set: (f: { name?: string; url?: string; apiKey?: string }) => void
}) {
  const [test, setTest] = useState<{ ok: boolean; message: string } | null>(null)
  const [testing, setTesting] = useState(false)
  const app = appName[kind]
  const prefix = `${kind}-${id ?? 'new'}`
  return (
    <>
      <Row label="Name" htmlFor={`${prefix}-name`}>
        <input id={`${prefix}-name`} value={name} onChange={(e) => set({ name: e.target.value })} className={`${textInput} w-64`} autoComplete="off" />
      </Row>
      <Row label="URL" hint={`With any URL base ${app} runs under.`} htmlFor={`${prefix}-url`}>
        <input
          id={`${prefix}-url`}
          value={url}
          onChange={(e) => set({ url: e.target.value })}
          placeholder={defaultAddress(kind)}
          className={`${textInput} w-80 font-mono`}
          autoComplete="off"
          spellCheck={false}
        />
      </Row>
      <Row label="API key" hint={`${app} › Settings › General › API Key.`} htmlFor={`${prefix}-key`}>
        <input
          id={`${prefix}-key`}
          value={apiKey}
          onChange={(e) => set({ apiKey: e.target.value })}
          placeholder={keyHint ? `•••• ${keyHint}` : ''}
          className={`${textInput} w-80 font-mono`}
          autoComplete="off"
          spellCheck={false}
        />
        <button
          type="button"
          className={secondaryButton}
          disabled={testing || !url}
          onClick={() => {
            setTesting(true)
            testIntegration({ kind, url, apiKey: apiKey || undefined, id })
              .then(setTest, (e: Error) => { if (visibleConfirmationError(e)) setTest({ ok: false, message: e.message }) })
              .finally(() => setTesting(false))
          }}
        >
          {testing ? 'Testing…' : 'Test'}
        </button>
        {test && (
          <span role="status" className={`text-sm ${test.ok ? 'text-emerald-300' : 'text-orange-300'}`}>
            {test.ok ? test.message.replace(/^(\S+) (\d+\.\d+\.\d+).*$/, '$1 · v$2') : test.message}
          </span>
        )}
      </Row>
    </>
  )
}

function NewConnection({ kind, onDone }: { kind: IntegrationKind; onDone: (e: Editing) => void }) {
  const [f, setF] = useState({ name: appName[kind], url: '', apiKey: '' })
  const create = useCreateIntegration()
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        create.mutate({ kind, ...f }, { onSuccess: (c) => onDone({ id: c.id }) })
      }}
    >
      <Panel
        title={`${kind === 'seerr' ? 'Connect' : 'Add'} ${appName[kind]}`}
        description={`Checked when you save: WatchMyMedia asks ${appName[kind]} for its ${kind === 'seerr' ? 'version' : 'name and version'}.`}
      >
        <AppFields kind={kind} {...f} set={(p) => setF({ ...f, ...p })} />
        <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-4">
          <button type="submit" className={primaryButton} disabled={create.isPending || !f.url || !f.apiKey}>
            {create.isPending ? 'Checking…' : 'Save'}
          </button>
          <button type="button" className={secondaryButton} onClick={() => onDone(null)}>
            Cancel
          </button>
          {create.isError && visibleConfirmationError(create.error) && <span className="text-sm text-orange-300">{create.error.message}</span>}
        </div>
      </Panel>
    </form>
  )
}

function EditConnection({ c, addresses, onClose }: { c: Integration; addresses: string[]; onClose: () => void }) {
  const detail = useQuery(integrationQuery(c.id))
  const update = useUpdateIntegration(c.id)
  const remove = useDeleteIntegration()
  const [f, setF] = useState({ name: c.name, url: c.url, apiKey: '' })
  const [maps, setMaps] = useState<PathMapping[]>(c.pathMap.length ? c.pathMap : [{ from: '', to: '' }])
  const [targets, setTargets] = useState<RequestTarget[]>(c.targets)
  const libraries = useQuery(librariesQuery)
  const app = appName[c.kind]
  const details = detail.data?.details
  const suggestions = details ? freshSuggestions(details.suggestions, maps) : []
  const ownLibraries = (libraries.data?.items ?? []).filter((l) => l.type === (c.kind === 'radarr' ? 'movie' : 'tv'))
  const setTarget = (libraryId: number, t: Partial<RequestTarget> | null) => {
    const rest = targets.filter((x) => x.libraryId !== libraryId)
    if (t === null) return setTargets(rest)
    const cur = targets.find((x) => x.libraryId === libraryId) ?? { libraryId, rootFolder: details?.rootFolders[0]?.path ?? '', qualityProfileId: details?.qualityProfiles[0]?.id ?? 0 }
    setTargets([...rest, { ...cur, ...t }])
  }
  const save = () =>
    update.mutate(
      { name: f.name, url: f.url, apiKey: f.apiKey || undefined, pathMap: cleanMappings(maps), targets: targets.filter((t) => t.rootFolder && t.qualityProfileId > 0) },
      { onSuccess: () => setF({ ...f, apiKey: '' }) },
    )
  return (
    <Panel title={`Edit ${c.name}`} description={`Checked when you save: WatchMyMedia asks ${app} for its name and version.`}>
      <AppFields kind={c.kind} id={c.id} {...f} keyHint={c.apiKeyHint} set={(p) => setF({ ...f, ...p })} />

      <div className="border-t border-line px-5 py-4">
        <h4 className="font-medium">Path mappings</h4>
        <p className="mt-0.5 text-sm text-neutral-400">Where this server finds the folders {app} names. Leave them empty when both see the same paths.</p>
        <div className="mt-3 space-y-2">
          {maps.map((m, i) => (
            <div key={i} className="flex flex-wrap items-center gap-2">
              <input
                aria-label={`${app} sees`}
                value={m.from}
                placeholder={c.kind === 'radarr' ? '/movies' : '/tv'}
                onChange={(e) => setMaps(maps.map((x, j) => (j === i ? { ...x, from: e.target.value } : x)))}
                className={`${textInput} min-w-0 flex-1 basis-48 font-mono`}
              />
              <span aria-hidden="true" className="text-neutral-500">
                →
              </span>
              <input
                aria-label="WatchMyMedia sees"
                value={m.to}
                placeholder="/Volumes/Media"
                onChange={(e) => setMaps(maps.map((x, j) => (j === i ? { ...x, to: e.target.value } : x)))}
                className={`${textInput} min-w-0 flex-1 basis-48 font-mono`}
              />
              <button type="button" aria-label="Remove mapping" className={secondaryButton} onClick={() => setMaps(maps.filter((_, j) => j !== i))}>
                ✕
              </button>
            </div>
          ))}
          {suggestions.map((s) => (
            <p key={s.from + s.to} className="flex flex-wrap items-center gap-2 text-sm text-neutral-300">
              Suggested: <code className="font-mono">{s.from}</code> → <code className="font-mono">{s.to}</code>
              <button type="button" className={secondaryButton} onClick={() => setMaps([...cleanMappings(maps), s])}>
                Use
              </button>
            </p>
          ))}
          {details &&
            unmappedRoots(details.rootFolders).map((r) => (
              <p key={r.path} className="rounded-lg border border-orange-900 bg-orange-950/50 px-3.5 py-2.5 text-sm text-orange-300">
                <code className="font-mono">{r.path}</code> isn&rsquo;t inside any library ({itemWord(c.kind, r.items)})
              </p>
            ))}
          {detail.data?.detailsError && <p className="text-sm text-orange-300">Couldn&rsquo;t read {app}&rsquo;s root folders: {detail.data.detailsError}</p>}
          <button type="button" className={secondaryButton} onClick={() => setMaps([...maps, { from: '', to: '' }])}>
            Add mapping
          </button>
        </div>
      </div>

      {details &&
        ownLibraries.map((l) => {
          const t = targets.find((x) => x.libraryId === l.id)
          return (
            <Row key={l.id} label={`Requests for ${l.name} go to`} hint={`Where ${app} adds what people ask for. The root folder should be inside ${l.name}.`}>
              <select
                aria-label={`Root folder for ${l.name}`}
                value={t?.rootFolder ?? ''}
                onChange={(e) => setTarget(l.id, e.target.value ? { rootFolder: e.target.value } : null)}
                className={`${textInput} px-2 font-mono`}
              >
                <option value="">Don&rsquo;t take requests</option>
                {details.rootFolders.map((r) => (
                  <option key={r.path} value={r.path}>
                    {r.path}
                  </option>
                ))}
              </select>
              <select
                aria-label={`Quality profile for ${l.name}`}
                value={t?.qualityProfileId ?? ''}
                disabled={!t}
                onChange={(e) => setTarget(l.id, { qualityProfileId: Number(e.target.value) })}
                className={`${textInput} px-2`}
              >
                {details.qualityProfiles.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </Row>
          )
        })}

      <WebhookRow c={c} addresses={addresses} />

      <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-4">
        <button type="button" className={primaryButton} disabled={update.isPending} onClick={save}>
          {update.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className={secondaryButton} onClick={onClose}>
          Cancel
        </button>
        {update.isError && visibleConfirmationError(update.error) && <span className="text-sm text-orange-300">{update.error.message}</span>}
        {update.isSuccess && !update.isPending && <span className="text-sm text-emerald-300">Saved</span>}
        <button
          type="button"
          className={`${secondaryButton} ml-auto`}
          disabled={remove.isPending}
          onClick={() => {
            if (window.confirm(`Remove ${c.name}? Its webhook stops working; remove it in ${app} too.`)) remove.mutate(c.id, { onSuccess: onClose })
          }}
        >
          Remove {c.name}
        </button>
      </div>
    </Panel>
  )
}

/** Seerr has no folders or webhook here: its address and key, and where people are linked. */
function EditSeerr({ c, onClose }: { c: Integration; onClose: () => void }) {
  const update = useUpdateIntegration(c.id)
  const remove = useDeleteIntegration()
  const [f, setF] = useState({ name: c.name, url: c.url, apiKey: '' })
  return (
    <Panel title={`Edit ${c.name}`} description="Checked when you save: WatchMyMedia asks Seerr for its version.">
      <AppFields kind="seerr" id={c.id} {...f} keyHint={c.apiKeyHint} set={(p) => setF({ ...f, ...p })} />
      <div className="border-t border-line px-5 py-4">
        <h4 className="font-medium">People</h4>
        <p className="mt-0.5 text-sm text-neutral-400">
          Requests go to Seerr as each person&rsquo;s Seerr user, so Seerr&rsquo;s permissions and quotas apply. Link people under{' '}
          <Link to="/settings/users" className="text-neutral-200 underline">
            Users
          </Link>
          ; people who aren&rsquo;t linked can&rsquo;t request.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-4">
        <button
          type="button"
          className={primaryButton}
          disabled={update.isPending}
          onClick={() => update.mutate({ name: f.name, url: f.url, apiKey: f.apiKey || undefined }, { onSuccess: () => setF({ ...f, apiKey: '' }) })}
        >
          {update.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className={secondaryButton} onClick={onClose}>
          Cancel
        </button>
        {update.isError && visibleConfirmationError(update.error) && <span className="text-sm text-orange-300">{update.error.message}</span>}
        {update.isSuccess && !update.isPending && <span className="text-sm text-emerald-300">Saved</span>}
        <button
          type="button"
          className={`${secondaryButton} ml-auto`}
          disabled={remove.isPending}
          onClick={() => {
            if (window.confirm(`Remove ${c.name}? Requests go to Radarr and Sonarr again, and people's Seerr links are kept for later.`))
              remove.mutate(c.id, { onSuccess: onClose })
          }}
        >
          Remove {c.name}
        </button>
      </div>
    </Panel>
  )
}

function InfoIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-neutral-400">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h.01" />
    </svg>
  )
}

function WebhookRow({ c, addresses }: { c: Integration; addresses: string[] }) {
  const app = appName[c.kind]
  const connect = useConnectWebhook(c.id)
  const [typed, setAddress] = useState<string | null>(null)
  const address = typed ?? addresses[0] ?? ''
  const w = c.status.webhook
  const listId = `webhook-addresses-${c.id}`
  return (
    <div className="border-t border-line px-5 py-4">
      <h4 className="font-medium">Webhook</h4>
      <p className="mt-0.5 text-sm text-neutral-400">{app} tells this server the moment it imports, upgrades, renames or deletes something.</p>
      <div className="mt-3 flex flex-wrap items-end gap-2">
        <label className="flex min-w-0 flex-1 basis-72 flex-col gap-1 text-sm">
          <span>Address {app} reaches this server at</span>
          <input
            list={listId}
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            className={`${textInput} font-mono`}
            autoComplete="off"
            spellCheck={false}
          />
          <datalist id={listId}>
            {addresses.map((a) => (
              <option key={a} value={a} />
            ))}
          </datalist>
        </label>
        <button type="button" className={secondaryButton} disabled={!address || connect.isPending} onClick={() => connect.mutate(address)}>
          {connect.isPending ? 'Connecting…' : 'Connect webhook'}
        </button>
      </div>
      <div className="mt-2 space-y-2 text-sm">
        {connect.isError && visibleConfirmationError(connect.error) && <p className="text-orange-300">{connect.error.message}</p>}
        {w.lastTestAt && <p className="text-emerald-300">Connected · test event received {clock(w.lastTestAt)}</p>}
        {w.outside > 0 && (
          <p className="rounded-lg border border-orange-900 bg-orange-950/50 px-3.5 py-2.5 text-orange-300">
            {w.outside === 1 ? '1 event was' : `${w.outside} events were`} for folders outside your libraries. Check the path mappings.
          </p>
        )}
      </div>
    </div>
  )
}
