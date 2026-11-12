import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { librariesQuery, mediaListSourcesQuery, mediaListsQuery, rowLayoutQuery, useSaveRowLayout } from '../../api/queries'
import { settingsQuery } from '../../api/settings'
import { RowLayoutEditor } from '../../components/rows/RowLayoutEditor'
import { MDBListSection } from '../../components/SettingsSections'
import { PageHeader, Panel, StatusPill, primaryButton, secondaryButton } from '../../components/SettingsPanel'
import { relativeTime } from '../../lib/format'
import { holdsWords, listFromLine, refreshWords, screenName, sourceWords } from '../../lib/mediaLists'
import { mergeDraft, placements, samePlacement, type RowLayoutEntry } from '../../lib/rowLayout'
import { libraryScreen } from '../../lib/screenRows'
import { ServerOnly } from './SettingsLayout'

/**
 * Settings › Server › Lists (PLAN-ROWS.md §5, design/mockups/web/SettingsLists):
 * where lists come from, the household's lists, and the rows everyone
 * starts with on Home and each library.
 */
export function ListsSettings() {
  return (
    <>
      <PageHeader title="Lists">Where rows made from lists come from, the household’s lists, and the rows everyone starts with.</PageHeader>
      <ServerOnly>
        <div className="space-y-6">
          <TMDBStatus />
          <MDBListSection />
          <HouseholdLists />
          <DefaultRows />
        </div>
      </ServerOnly>
    </>
  )
}

function TMDBStatus() {
  const settings = useQuery(settingsQuery)
  const s = settings.data
  return (
    <Panel
      title="TMDB"
      description={
        <>
          Lists from TMDB use the key in{' '}
          <Link to="/settings/general" className="text-neutral-200 underline">
            Settings › General
          </Link>
          : its charts, trending, your own searches, TMDB lists and collections. Everyone can make them.
        </>
      }
      actions={s && (s.tmdbConfigured ? <StatusPill tone="ok">{s.tmdbKeyHint ? `Ready · key ${s.tmdbKeyHint}` : 'Ready'}</StatusPill> : <StatusPill tone="warn">No key</StatusPill>)}
    />
  )
}

function HouseholdLists() {
  const lists = useQuery(mediaListsQuery)
  const libraries = useQuery(librariesQuery)
  const sources = useQuery(mediaListSourcesQuery)
  const libs = libraries.data?.items ?? []
  const household = (lists.data?.items ?? []).filter((l) => l.owner === 'everyone')
  return (
    <Panel
      title="The household’s lists"
      description="Lists made For everyone. Members can add them to their screens; only admins change them. Members’ own lists are theirs alone and aren’t shown here."
      actions={
        <Link to="/lists/new" search={{ screen: 'home', back: 'settings' }} className={`${secondaryButton} inline-flex items-center gap-2`}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Make a list for everyone
        </Link>
      }
    >
      {lists.isPending && <p className="px-5 pb-4 text-sm text-neutral-400">Loading…</p>}
      {lists.isError && <p className="px-5 pb-4 text-sm text-warn">{lists.error.message}</p>}
      {lists.data && household.length === 0 && <p className="border-t border-line px-5 py-4 text-sm text-neutral-400">No lists for everyone yet.</p>}
      {household.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold text-neutral-400">
                <th scope="col" className="whitespace-nowrap border-t border-line py-2.5 pl-5 pr-3">List</th>
                <th scope="col" className="whitespace-nowrap border-t border-line px-3 py-2.5">In your library</th>
                <th scope="col" className="whitespace-nowrap border-t border-line px-3 py-2.5">Updated</th>
                <th scope="col" className="whitespace-nowrap border-t border-line px-3 py-2.5">First shown on</th>
                <th scope="col" className="border-t border-line py-2.5 pl-3 pr-5 text-right">
                  <span className="sr-only">Open</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {household.map((l) => {
                const { fetched, error } = refreshWords(l)
                const updated = error ? `Couldn’t refresh${l.fetchedAt ? ` · last worked ${relativeTime(l.fetchedAt)}` : ''}` : l.source.kind === 'library' ? 'Follows the library' : fetched
                return (
                  <tr key={l.id}>
                    <td className="border-t border-line py-3 pl-5 pr-3 align-middle">
                      <span className="font-medium">{l.name}</span>
                      <span className="mt-0.5 block text-xs text-neutral-400">{listFromLine(l, libs)}</span>
                    </td>
                    <td className="border-t border-line px-3 py-3 align-middle">{holdsWords(l)}</td>
                    <td className="border-t border-line px-3 py-3 align-middle">
                      <span className={error ? 'text-orange-300' : ''} title={error}>
                        {updated}
                      </span>
                    </td>
                    <td className="border-t border-line px-3 py-3 align-middle">{screenName(l.screen, libs)}</td>
                    <td className="border-t border-line py-3 pl-3 pr-5 text-right align-middle">
                      <Link to="/lists/$listId" params={{ listId: String(l.id) }} aria-label={`Open ${l.name}`} className={`${secondaryButton} inline-flex items-center`}>
                        Open
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {sources.data && <p className="border-t border-line px-5 py-3 text-[13px] text-neutral-400">Each person can make {sources.data.maxLists} lists of their own; household lists are the admins’.</p>}
    </Panel>
  )
}

function DefaultRows() {
  const libraries = useQuery(librariesQuery)
  const [screen, setScreen] = useState('home')
  const libs = libraries.data?.items ?? []
  const screens = [{ value: 'home', label: 'Home' }, ...libs.map((l) => ({ value: libraryScreen(l.id), label: l.name }))]
  return (
    <Panel
      title="Default rows"
      description="The rows everyone starts with, in this order, until they arrange their own. People who have arranged theirs keep their order; a row new to them goes where this puts it."
    >
      <div className="px-5 pb-4">
        <div role="radiogroup" aria-label="Screen" className="inline-flex flex-wrap rounded-lg border border-line-2 bg-ground p-0.5">
          {screens.map((s) => (
            <button
              key={s.value}
              type="button"
              role="radio"
              aria-checked={screen === s.value}
              onClick={() => setScreen(s.value)}
              className={`min-h-[40px] rounded-md px-3.5 text-sm ${screen === s.value ? 'bg-surface-2 text-neutral-100 ring-1 ring-inset ring-neutral-700' : 'text-neutral-400 hover:text-neutral-100'}`}
            >
              {s.label}
            </button>
          ))}
        </div>
      </div>
      <DefaultRowsEditor key={screen} screen={screen} name={screenName(screen, libs)} />
    </Panel>
  )
}

/** The household's order for one screen: arrows and Hide, then Save, or back to the built-in order. */
function DefaultRowsEditor({ screen, name }: { screen: string; name: string }) {
  const layout = useQuery(rowLayoutQuery('default', screen))
  const lists = useQuery(mediaListsQuery)
  const { save, reset } = useSaveRowLayout('default', screen)
  const [draft, setDraft] = useState<RowLayoutEntry[] | null>(null)
  const [note, setNote] = useState('')
  const saved = layout.data?.rows ?? []
  const rows = draft ? mergeDraft(draft, saved) : saved
  const dirty = !!draft && !samePlacement(rows, saved)
  const busy = save.isPending || reset.isPending
  const byId = new Map((lists.data?.items ?? []).map((l) => [l.id, l]))
  const lineOf = (r: RowLayoutEntry) => {
    if (r.kind !== 'list') return r.style === 'live' ? 'Built-in · drawn in the TV and phone apps' : 'Built-in'
    const l = r.listId ? byId.get(r.listId) : undefined
    return ['For everyone', l && sourceWords(l.source)].filter(Boolean).join(' · ')
  }
  const error = save.error ?? reset.error
  return (
    <>
      {layout.isPending && <p className="px-5 pb-4 text-sm text-neutral-400">Loading rows…</p>}
      {layout.isError && (
        <div role="alert" className="space-y-3 px-5 pb-4 text-sm text-warn">
          <p>Couldn’t load the rows: {layout.error.message}</p>
          <button type="button" onClick={() => void layout.refetch()} className={secondaryButton}>
            Try again
          </button>
        </div>
      )}
      {layout.data && (
        <div className="px-5">
          <RowLayoutEditor
            compact
            rows={rows}
            lineOf={lineOf}
            screenName={name}
            label={`Default rows on ${name}, in order`}
            onChange={(next) => {
              setDraft(next)
              setNote('')
            }}
            disabled={busy}
          />
        </div>
      )}
      {error && (
        <p role="alert" className="px-5 pt-3 text-sm text-warn">
          {error.message}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-5 pb-5 pt-4">
        <button
          type="button"
          disabled={!dirty || busy}
          onClick={() =>
            save.mutate(placements(rows), {
              onSuccess: () => {
                setDraft(null)
                setNote('Saved. Everyone who follows the defaults sees this order now.')
              },
            })
          }
          className={primaryButton}
        >
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
        <button
          type="button"
          disabled={busy || !layout.data}
          onClick={() =>
            reset.mutate(undefined, {
              onSuccess: () => {
                setDraft(null)
                setNote('Back to the built-in order, with the household’s lists after it.')
              },
            })
          }
          className="min-h-[40px] px-1.5 text-sm text-neutral-300 underline underline-offset-[3px] hover:text-accent disabled:opacity-50"
        >
          Back to the built-in order
        </button>
        <span role="status" className="text-[13px] text-neutral-400">
          {note || (dirty ? 'Changes not saved yet.' : screen === 'home' ? 'Each library has its own defaults.' : '')}
        </span>
      </div>
    </>
  )
}
