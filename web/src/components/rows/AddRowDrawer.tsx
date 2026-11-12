import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, mediaListSourcesQuery, mediaListsQuery } from '../../api/queries'
import { catalogueLine, notFoundWords } from '../../lib/mediaLists'
import type { RowLayoutEntry } from '../../lib/rowLayout'
import { useSettingsAccess } from '../../pages/settings/shared'
import { Dialog } from '../Dialog'

/**
 * Add a row (PLAN-ROWS.md §5, design/mockups/web/AddRow): a drawer of the
 * screen's hidden rows, the household's lists and the person's own (lists
 * made for other screens among them), and Make a list. An added row goes to
 * the top of the draft; Done saves it.
 */
export function AddRowDrawer({ screen, screenName, rows, onAdd, onMake, onClose }: {
  screen: string
  screenName: string
  rows: readonly RowLayoutEntry[]
  onAdd: (id: string) => void
  onMake: () => void
  onClose: () => void
}) {
  const lists = useQuery(mediaListsQuery)
  const sources = useQuery(mediaListSourcesQuery)
  const libraries = useQuery(librariesQuery)
  const { admin } = useSettingsAccess()
  // Added rows stay listed, marked, until the drawer closes.
  const [added, setAdded] = useState<RowLayoutEntry[]>([])
  const byId = new Map((lists.data?.items ?? []).map((l) => [l.id, l]))
  const libs = libraries.data?.items ?? []
  const all = [...rows, ...added.filter((a) => !rows.some((r) => r.id === a.id))]
  const builtin = all.filter((r) => r.kind !== 'list')
  const house = all.filter((r) => r.kind === 'list' && r.owner === 'everyone')
  const mine = all.filter((r) => r.kind === 'list' && r.owner !== 'everyone')
  const s = sources.data
  const atMax = !!s && s.maxLists > 0 && s.lists >= s.maxLists
  const add = (r: RowLayoutEntry) => {
    setAdded((a) => [...a, r])
    onAdd(r.id)
  }
  const item = (r: RowLayoutEntry) => {
    const list = r.listId ? byId.get(r.listId) : undefined
    const done = added.some((a) => a.id === r.id)
    return (
      <li key={r.id} className="flex min-h-[72px] items-center gap-3 border-t border-line px-3 py-2.5 first:border-t-0">
        <span aria-hidden="true" className="flex w-[58px] shrink-0">
          {[0, 1, 2].map((i) => (
            <span key={i} className="-mr-[7px] block h-9 w-6 rounded-[3px] bg-neutral-700 ring-1 ring-ground" />
          ))}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="truncate text-[15px] font-semibold">{r.title}</span>
          <span className="text-[13px] leading-snug text-neutral-400">{catalogueLine(r, list, screen, libs)}</span>
          {!!list?.notFound && <span className="text-[13px] leading-snug text-orange-300">{notFoundWords(list.notFound)}</span>}
        </span>
        {done ? (
          <span role="status" className="inline-flex min-h-11 min-w-[84px] shrink-0 items-center justify-center rounded-md border border-emerald-900 bg-emerald-950/60 px-3.5 text-sm font-semibold text-emerald-300">
            ✓ Added
          </span>
        ) : (
          <button
            type="button"
            onClick={() => add(r)}
            aria-label={`Add ${r.title}`}
            className="inline-flex min-h-11 min-w-[84px] shrink-0 items-center justify-center rounded-md border border-neutral-600 px-3.5 text-sm font-semibold hover:border-accent hover:text-accent"
          >
            Add
          </button>
        )}
      </li>
    )
  }
  const footer = (
    <div className="flex flex-col gap-2.5 border-t border-line px-6 pb-6 pt-4">
      <button type="button" disabled={atMax} onClick={onMake} className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        Make a list
      </button>
      <p className={`text-[13px] leading-snug ${atMax ? 'text-orange-300' : 'text-neutral-400'}`}>
        {atMax
          ? `You’ve made ${s?.maxLists} lists, the most you can. Delete one on its page to make another.`
          : admin
            ? 'From TMDB, MDBList, your library or a list you import, for you or for everyone.'
            : 'From TMDB, MDBList, your library or a list you import. Only you will see it.'}
      </p>
    </div>
  )
  return (
    <Dialog title={`Add a row to ${screenName}`} subtitle={`Rows you add go at the top of ${screenName}. Move them from there.`} onClose={onClose} side="right" width="max-w-[30rem]" footer={footer}>
      <div className="flex flex-col gap-6 px-6 pb-6 pt-2">
        {builtin.length > 0 && <Group title="Built-in rows">{builtin.map(item)}</Group>}
        <Group title="For everyone" caption={admin ? 'Lists made for the whole household. Change or delete them on their pages.' : 'Lists an admin made for the whole household.'}>
          {house.length ? house.map(item) : <Empty>{admin ? 'No lists for everyone yet. Make one, and choose For everyone.' : 'No lists for everyone yet.'}</Empty>}
        </Group>
        <Group
          title="Your lists"
          count={s ? `${s.lists} of ${s.maxLists}` : undefined}
          caption="Only you see these. Lists made on other screens can go here too."
        >
          {mine.length ? (
            mine.map(item)
          ) : (
            <Empty>You haven’t made a list yet. A list can follow a chart on TMDB or MDBList, a filter on your library, or a list you’ve exported from another site.</Empty>
          )}
        </Group>
      </div>
    </Dialog>
  )
}

function Group({ title, count, caption, children }: { title: string; count?: string; caption?: string; children: React.ReactNode }) {
  const list = Array.isArray(children)
  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-[13px] font-semibold uppercase tracking-wider text-neutral-400">{title}</h3>
        {count && <span className="text-[13px] text-neutral-400">{count}</span>}
      </div>
      {caption && <p className="text-[13px] leading-snug text-neutral-400">{caption}</p>}
      {list ? <ul className="overflow-hidden rounded-[10px] border border-line">{children}</ul> : children}
    </section>
  )
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="rounded-[10px] border border-dashed border-line-2 px-3 py-3.5 text-sm leading-snug text-neutral-400">{children}</p>
}
