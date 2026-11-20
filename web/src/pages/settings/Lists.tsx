import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, mediaListSourcesQuery, mediaListsQuery, rowLayoutQuery, useSaveRowLayout } from '../../api/queries'
import { settingsQuery, useUpdateSettings } from '../../api/settings'
import { RowLayoutEditor } from '../../components/rows/RowLayoutEditor'
import { ConfirmDialog, LeaveCheck } from '../../components/settings/Dialogs'
import { Button, Card, Field, HrefLink, HubPage, ItemRow, LoadingRows, Message, Pill, SaveBar, Section, fieldInput } from '../../components/settings/Frame'
import { Dialog } from '../../components/Dialog'
import { errorText } from '../../components/settings/hooks'
import { relativeTime } from '../../lib/format'
import { mdblistSaveError, rowsSaveError, type ListsSaveError } from '../../lib/listsSettings'
import { holdsWords, listFromLine, refreshWords, screenName, sentence, sourceWords } from '../../lib/mediaLists'
import { mergeDraft, placements, samePlacement, type RowLayoutEntry } from '../../lib/rowLayout'
import { libraryScreen } from '../../lib/screenRows'
import { settingsCrumbs, settingsHref } from '../../lib/settingsRoutes'
import { ServerOnly } from './SettingsLayout'

/**
 * Settings › Server › Lists:
 * where list rows come from, the household's lists, and the default rows,
 * a draft with the save bar; Back to built-in asks first.
 */
export function ListsSettings() {
  return (
    <ServerOnly tab="lists">
      <HubPage crumbs={settingsCrumbs('lists')} title="Lists" lede="Where list rows come from, and the rows people see before they arrange their own.">
        <Sources />
        <HouseholdLists />
        <DefaultRows />
      </HubPage>
    </ServerOnly>
  )
}

function Sources() {
  const settings = useQuery(settingsQuery)
  const sources = useQuery(mediaListSourcesQuery)
  const lists = useQuery(mediaListsQuery)
  const update = useUpdateSettings()
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  const s = settings.data
  if (!s) return <Section title="Sources"><LoadingRows rows={2} /></Section>
  const on = !!s.listsMdblistConfigured
  const mdblist = sources.data?.sources.find((x) => x.kind === 'mdblist')
  const refused = on && !!mdblist && !mdblist.ready
  const using = (lists.data?.items ?? []).filter((l) => l.source.kind === 'mdblist')
  return (
    <Section title="Sources">
      <Card>
        <ItemRow
          title="TMDB"
          pill={s.tmdbConfigured ? <Pill tone="ok">Key saved</Pill> : <Pill tone="warn">No key</Pill>}
          detail={<>Charts, trending, searches, TMDB lists and collections. Set in <HrefLink href={settingsHref('general')} className="underline">General</HrefLink>.</>}
        />
        <ItemRow
          title="MDBList"
          pill={refused ? <Pill tone="warn">Key refused</Pill> : on ? <Pill tone="ok">Key saved{s.listsMdblistKeyHint ? ` · ${s.listsMdblistKeyHint}` : ''}</Pill> : <Pill tone="off">No key</Pill>}
          detail={on
            ? `${using.length} ${using.length === 1 ? 'list uses' : 'lists use'} MDBList. Lists are fetched again daily.`
            : 'Optional. Adds MDBList to Make a list: lists that follow IMDb’s, Rotten Tomatoes’ and Letterboxd’s charts. Lists are fetched again daily.'}
          control={on ? <>
            <Button small onClick={() => { update.reset(); setAdding(true) }}>Replace key</Button>
            <Button small variant="danger" onClick={() => { update.reset(); setRemoving(true) }}>Remove key…</Button>
          </> : <Button small onClick={() => { update.reset(); setAdding(true) }}>Add a key</Button>}
        >
          {refused && <p className="mt-1 text-sm text-warn">{sentence(mdblist?.reason) || 'MDBList refused the key.'} Lists from MDBList keep the titles they had until it accepts a key again.</p>}
          {done && <p role="status" className="settings-fade mt-1 text-sm text-emerald-300">{done}</p>}
        </ItemRow>
      </Card>
      {adding && <MdblistKeyDialog replacing={on} onClose={() => setAdding(false)} onSaved={() => { setAdding(false); setDone('✓ Saved. Make a list offers MDBList now.') }} />}
      {removing && (
        <ConfirmDialog
          title="Remove the MDBList key?"
          stops={['Lists from MDBList stop being fetched again, and Make a list no longer offers MDBList.']}
          stays={['The titles those lists already hold.']}
          confirmLabel="Remove key"
          busy={update.isPending}
          error={update.isError ? errorText(update.error) : undefined}
          onClose={() => setRemoving(false)}
          onConfirm={() => update.mutate({ listsMdblistKey: '' }, { onSuccess: () => { setRemoving(false); setDone('✓ Removed. Make a list no longer offers MDBList.') } })}
        />
      )}
    </Section>
  )
}

/** The MDBList key: one field, checked with MDBList before it is kept. */
function MdblistKeyDialog({ replacing, onClose, onSaved }: { replacing: boolean; onClose: () => void; onSaved: () => void }) {
  const update = useUpdateSettings()
  const [key, setKey] = useState('')
  const trimmed = key.trim()
  const refused = update.isError ? mdblistSaveError(update.error, errorText) : null
  return (
    <Dialog title={replacing ? 'Replace the MDBList key' : 'Add an MDBList key'} onClose={() => { if (!update.isPending) onClose() }} width="max-w-lg">
      <form className="px-6 pt-2 pb-6" onSubmit={(e) => { e.preventDefault(); if (trimmed) update.mutate({ listsMdblistKey: trimmed }, { onSuccess: onSaved }) }}>
        <p className="text-sm text-neutral-300">
          Make a free account at <a href="https://mdblist.com" target="_blank" rel="noreferrer" className="underline">mdblist.com</a>, then copy the API key from your preferences there. The server checks it with MDBList before saving it.
        </p>
        <div className="mt-4">
          <Field label="MDBList API key">
            {(p) => <input {...p} value={key} maxLength={200} autoComplete="off" spellCheck={false} onChange={(e) => { setKey(e.target.value); update.reset() }} className={`${fieldInput} font-mono`} />}
          </Field>
        </div>
        {refused && <Message tone="err" focus className="mt-4" title={refused.title}>{refused.body}</Message>}
        <div className="mt-5 flex flex-wrap justify-end gap-3 border-t border-line pt-4">
          <Button onClick={onClose} disabled={update.isPending} className="flex-1 sm:flex-none">Cancel</Button>
          <Button type="submit" variant="primary" busy={update.isPending} busyLabel="Checking with MDBList…" disabled={!trimmed} className="flex-1 sm:flex-none">Save key</Button>
        </div>
      </form>
    </Dialog>
  )
}

function HouseholdLists() {
  const lists = useQuery(mediaListsQuery)
  const libraries = useQuery(librariesQuery)
  const sources = useQuery(mediaListSourcesQuery)
  const libs = libraries.data?.items ?? []
  const household = (lists.data?.items ?? []).filter((l) => l.owner === 'everyone')
  return (
    <Section title="The household’s lists" aside={<HrefLink href="/lists/new?screen=home&back=settings" className="text-sm text-neutral-300 underline hover:text-neutral-100">Make a list for everyone</HrefLink>}>
      {lists.isPending ? <LoadingRows rows={2} /> : lists.isError ? <Message tone="err" title="Couldn’t load the lists">{errorText(lists.error)}</Message> : (
        <Card>
          {household.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No lists for everyone yet. Members can add these to their screens; only admins change them.</p>}
          {household.map((l) => {
            const { fetched, error } = refreshWords(l)
            const updated = error ? `Couldn’t refresh${l.fetchedAt ? `, last worked ${relativeTime(l.fetchedAt)}` : ''}` : l.source.kind === 'library' ? 'Follows the library' : fetched
            return (
              <ItemRow key={l.id} href={`/lists/${l.id}`} title={l.name}
                detail={<>{listFromLine(l, libs)} · {holdsWords(l)} · <span className={error ? 'text-orange-300' : ''} title={error}>{updated}</span> · first shown on {screenName(l.screen, libs)}</>} />
            )
          })}
          {sources.data && <p className="px-5 py-3 text-[13px] text-neutral-400">Each person can make {sources.data.maxLists} lists of their own; household lists are the admins’.</p>}
        </Card>
      )}
    </Section>
  )
}

function DefaultRows() {
  const libraries = useQuery(librariesQuery)
  const [screen, setScreen] = useState('home')
  const [dirty, setDirty] = useState(false)
  const libs = libraries.data?.items ?? []
  const screens = [{ value: 'home', label: 'Home' }, ...libs.map((l) => ({ value: libraryScreen(l.id), label: l.name }))]
  return (
    <Section title="Default rows">
      <Card>
        <ItemRow
          title="Screen"
          detail={dirty ? 'Save or discard the changes to this screen first.' : 'Applies to people who haven’t arranged this screen themselves. A row new to them goes where this puts it.'}
          control={
            <select aria-label="Screen" value={screen} disabled={dirty} onChange={(e) => setScreen(e.target.value)} className={`${fieldInput} min-w-[8rem]`}>
              {screens.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
          }
        />
        <DefaultRowsEditor key={screen} screen={screen} name={screenName(screen, libs)} onDirty={setDirty} />
      </Card>
    </Section>
  )
}

/** The household's order for one screen: a draft held by the save bar; Back to built-in asks first. */
function DefaultRowsEditor({ screen, name, onDirty }: { screen: string; name: string; onDirty: (dirty: boolean) => void }) {
  const layout = useQuery(rowLayoutQuery('default', screen))
  const lists = useQuery(mediaListsQuery)
  const { save, reset } = useSaveRowLayout('default', screen)
  const [draft, setDraftState] = useState<RowLayoutEntry[] | null>(null)
  const [note, setNote] = useState('')
  const [refused, setRefused] = useState<ListsSaveError | null>(null)
  const [resetting, setResetting] = useState(false)
  const saved = layout.data?.rows ?? []
  const rows = draft ? mergeDraft(draft, saved) : saved
  const dirty = !!draft && !samePlacement(rows, saved)
  const setDraft = (next: RowLayoutEntry[] | null) => {
    setDraftState(next)
    onDirty(!!next && !samePlacement(mergeDraft(next, saved), saved))
  }
  const busy = save.isPending || reset.isPending
  const byId = new Map((lists.data?.items ?? []).map((l) => [l.id, l]))
  const lineOf = (r: RowLayoutEntry) => {
    if (r.kind !== 'list') return r.style === 'live' ? 'Built-in · drawn in the TV and phone apps' : 'Built-in'
    const l = r.listId ? byId.get(r.listId) : undefined
    return ['For everyone', l && sourceWords(l.source)].filter(Boolean).join(' · ')
  }
  const doSave = () => {
    setRefused(null)
    save.mutate(placements(rows), {
      onSuccess: () => { setDraft(null); setNote('✓ Saved. Everyone who follows the defaults sees this order now.') },
      onError: (error) => {
        const e = rowsSaveError(error, rows, errorText)
        setRefused(e)
        if (e.drop) {
          setDraft(rows.filter((r) => r.id !== e.drop))
          void layout.refetch()
        }
      },
    })
  }
  if (layout.isPending) return <div className="px-5 py-4"><LoadingRows rows={3} /></div>
  if (layout.isError) {
    return <div className="px-5 py-4"><Message tone="err" title="Couldn’t load the rows" actions={<Button small onClick={() => void layout.refetch()}>Try again</Button>}>{errorText(layout.error)}</Message></div>
  }
  return (
    <>
      <LeaveCheck when={dirty} what={`Your changes to the default rows for ${name} aren’t saved.`} />
      <div className="px-5 pt-4">
        <SaveBar dirty={dirty} saving={save.isPending} onDiscard={() => { setDraft(null); setRefused(null) }} onSave={doSave} />
        <RowLayoutEditor compact rows={rows} lineOf={lineOf} screenName={name} label={`Default rows on ${name}, in order`} disabled={busy}
          onChange={(next) => { setDraft(next); setNote(''); setRefused(null) }} />
        {refused && <Message tone="err" focus className="mt-3" title={refused.title}>{refused.body}</Message>}
        {note && !dirty && <p role="status" className="settings-fade mt-3 text-sm text-emerald-300">{note}</p>}
      </div>
      <ItemRow
        title={<span className="font-normal text-neutral-300">{layout.data.customized ? 'Go back to the order the app ships with.' : `${name} uses the order the app ships with.`}</span>}
        control={layout.data.customized ? <Button small disabled={busy} onClick={() => { reset.reset(); setResetting(true) }}>Back to built-in…</Button> : undefined}
      />
      {resetting && (
        <ConfirmDialog
          title={`Go back to the built-in rows for ${name}?`}
          lede={`Your default order for ${name} is discarded. People who arranged ${name} themselves keep their own.`}
          confirmLabel="Use built-in rows"
          danger={false}
          busy={reset.isPending}
          error={reset.isError ? errorText(reset.error) : undefined}
          onClose={() => setResetting(false)}
          onConfirm={() => reset.mutate(undefined, { onSuccess: () => { setResetting(false); setDraft(null); setNote('✓ Back to the built-in order, with the household’s lists after it.') } })}
        />
      )}
    </>
  )
}
