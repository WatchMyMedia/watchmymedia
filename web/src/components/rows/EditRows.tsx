import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, mediaListsQuery, rowLayoutQuery, useSaveRowLayout } from '../../api/queries'
import { editorLine } from '../../lib/mediaLists'
import { addRow, addable, countWords, editTitle, layoutView, mergeDraft, overWords, placements, samePlacement, MAX_ROWS, type RowLayoutEntry } from '../../lib/rowLayout'
import { secondaryButton } from '../SettingsPanel'
import { AddRowDrawer } from './AddRowDrawer'
import { RowLayoutEditor } from './RowLayoutEditor'
import { readRowDraft, writeRowDraft, type RowDraft } from './rowDrafts'

const bigPrimary = 'inline-flex min-h-[44px] items-center justify-center rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50'
const bigSecondary = 'inline-flex min-h-[44px] items-center justify-center rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50'
const linkButton = 'min-h-[44px] px-2 text-sm text-neutral-300 underline underline-offset-[3px] hover:text-accent disabled:opacity-50'

type Props = {
  screen: string
  /** "Home", or the library's name. */
  screenName: string
  /** Add a row is open (it lives in the address, so Make a list can come back to it). */
  adding: boolean
  onAdding: (open: boolean) => void
  onMakeList: () => void
  /** After Done or Cancel. */
  onClose: () => void
  /** What stays fixed above and below the rows, as the board's bars: [wide, phone]. */
  above?: [string, string]
  below?: [string, string]
}

/**
 * Edit rows (PLAN-ROWS.md §5, design/mockups/web/HomeEditRows and
 * HomeEditRowsPhone): a screen's rows folded into a list in place of the
 * screen. Done saves the person's order; Cancel discards it; Reset to
 * default goes back to the household's order, which Cancel undoes. Add a
 * row and Make a list keep the draft for the visit.
 */
export function EditRows({ screen, screenName, adding, onAdding, onMakeList, onClose, above, below }: Props) {
  const kind = 'me'
  const layout = useQuery(rowLayoutQuery(kind, screen))
  const lists = useQuery(mediaListsQuery)
  const libraries = useQuery(librariesQuery)
  const { save, reset } = useSaveRowLayout(kind, screen)
  const storeKey = `${kind}:${screen}`
  const [store, setStoreState] = useState<RowDraft>(() => readRowDraft(storeKey))
  const [resetDone, setResetDone] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)

  const setStore = (next: RowDraft) => {
    writeRowDraft(storeKey, next)
    setStoreState(next)
  }
  const setDraft = (rows: RowLayoutEntry[] | null) => setStore({ ...store, rows })

  // The rows fold into the editor: start at its heading.
  useEffect(() => {
    window.scrollTo({ top: 0 })
    heading.current?.focus({ preventScroll: true })
  }, [])

  const saved = layout.data?.rows ?? []
  // The latest layout comes into the draft: a list just made, a list deleted elsewhere.
  const rows = store.rows ? mergeDraft(store.rows, saved) : saved
  const changed = !!store.rows && !samePlacement(rows, saved)
  const view = layoutView(rows, saved)
  const busy = save.isPending || reset.isPending
  const error = save.error ?? reset.error
  const byId = new Map((lists.data?.items ?? []).map((l) => [l.id, l]))
  const libs = libraries.data?.items ?? []
  const lineOf = (r: RowLayoutEntry) => editorLine(r, r.listId ? byId.get(r.listId) : undefined, screen, libs)

  const finish = () => {
    writeRowDraft(storeKey, null)
    onClose()
  }
  const done = () => {
    if (!changed) return finish()
    save.mutate(placements(rows), { onSuccess: finish })
  }
  const cancel = () => {
    save.reset()
    // Reset already went to the server: put the person's own order back.
    if (store.restore) return save.mutate(store.restore, { onSuccess: finish })
    finish()
  }
  const toDefault = () => {
    setResetDone(false)
    if (!layout.data?.customized) {
      setStore({ ...store, rows: null })
      setResetDone(true)
      return
    }
    const restore = store.restore ?? placements(saved)
    reset.mutate(undefined, {
      onSuccess: () => {
        setStore({ rows: null, restore })
        setResetDone(true)
      },
    })
  }

  const editor =
    layout.isPending ? (
      <p className="text-sm text-neutral-400">Loading rows…</p>
    ) : layout.isError ? (
      <div role="alert" className="space-y-3 text-sm text-warn">
        <p>Couldn’t load the rows: {layout.error.message}</p>
        <button type="button" onClick={() => void layout.refetch()} className={secondaryButton}>
          Try again
        </button>
      </div>
    ) : rows.length ? (
      <RowLayoutEditor rows={rows} view={view} lineOf={lineOf} screenName={screenName} onChange={setDraft} disabled={busy} />
    ) : (
      <p className="text-sm text-neutral-400">This screen has no rows to arrange yet.</p>
    )
  const drawer = adding && (
    <AddRowDrawer
      screen={screen}
      screenName={screenName}
      rows={addable(rows)}
      onAdd={(id) => setDraft(addRow(rows, id))}
      onMake={onMakeList}
      onClose={() => onAdding(false)}
    />
  )
  const status = (
    <>
      {resetDone && (
        <p role="status" className="rounded-[10px] border border-line-2 px-4 py-3 text-sm text-neutral-300">
          {store.restore ? 'Back to the household’s default rows. Done saves it; Cancel keeps your own order.' : 'Your rows follow the household’s default.'}
        </p>
      )}
      {view.over > 0 && (
        <div role="status" className="flex items-start gap-3 rounded-[10px] border border-orange-900 bg-orange-950/25 px-4 py-3.5 text-sm text-neutral-200">
          <WarnIcon />
          <span>
            {screenName} draws {MAX_ROWS} rows at most. {overWords(view.over)} Hide a row above them, or move them up.
          </span>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm text-warn">
          {error.message} Your changes are still here.
        </p>
      )}
    </>
  )
  const addButton = (
    <button
      type="button"
      disabled={!layout.data || busy}
      onClick={() => onAdding(true)}
      className="flex min-h-[56px] w-full items-center justify-center gap-2 rounded-[10px] border border-dashed border-neutral-700 text-[15px] font-medium hover:border-accent hover:text-accent disabled:opacity-50"
    >
      <PlusIcon />
      Add a row
    </button>
  )

  return (
    <div className="mx-auto flex max-w-[60rem] flex-col gap-3 pb-24 sm:gap-4 sm:pb-0">
      <section aria-labelledby={`edit-rows-${screen}`} className="flex flex-wrap items-center justify-between gap-x-6 gap-y-4 sm:rounded-xl sm:border sm:border-line-2 sm:bg-surface sm:px-5 sm:py-4">
        <div className="min-w-0 flex-1 basis-96">
          <h1 ref={heading} tabIndex={-1} id={`edit-rows-${screen}`} className="text-xl font-semibold outline-none">
            {editTitle(screenName)}
          </h1>
          <p className="mt-1 text-sm leading-normal text-neutral-400">
            <span className="hidden sm:inline">Drag a row by its handle, or move it with the arrows.</span>
            <span className="sm:hidden">Hold a row’s handle and drag, or move it with the arrows.</span> Hidden rows stay here, greyed, until you show them.
            <span className="hidden sm:inline"> Your order follows you to every device.</span>
          </p>
        </div>
        <div className="hidden flex-wrap items-center gap-2 sm:flex">
          <button type="button" disabled={busy || !layout.data} onClick={toDefault} className={linkButton}>
            Reset to default
          </button>
          <button type="button" disabled={busy} onClick={cancel} className={bigSecondary}>
            Cancel
          </button>
          <button type="button" disabled={busy || !layout.data} onClick={done} className={bigPrimary}>
            {save.isPending ? 'Saving…' : 'Done'}
          </button>
        </div>
      </section>
      {status}
      {above && <FixedBar wide={above[0]} phone={above[1]} />}
      <div className="flex flex-col gap-1.5">
        {addButton}
        <p className="hidden px-1 text-[13px] text-neutral-400 sm:block">Rows you add go at the top. Make a list there, or add one already made.</p>
      </div>
      <div className="flex items-baseline justify-between gap-3 px-0.5 pt-1 sm:px-1">
        <h2 className="text-[15px] font-semibold">Rows</h2>
        {layout.data && <span className="text-xs text-neutral-400 sm:text-[13px]">{countWords(view)}</span>}
      </div>
      {editor}
      {below && <FixedBar wide={below[0]} phone={below[1]} />}
      <button type="button" disabled={busy || !layout.data} onClick={toDefault} className={`${linkButton} self-start px-0.5 sm:hidden`}>
        Reset to default
      </button>
      <div className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-2 gap-2.5 border-t border-line bg-ground/95 px-4 pb-4 pt-3 sm:hidden">
        <button type="button" disabled={busy} onClick={cancel} className={bigSecondary}>
          Cancel
        </button>
        <button type="button" disabled={busy || !layout.data} onClick={done} className={bigPrimary}>
          {save.isPending ? 'Saving…' : 'Done'}
        </button>
      </div>
      {drawer}
    </div>
  )
}

/** What stays where it is, such as the hero above the rows and the totals below them. */
function FixedBar({ wide, phone }: { wide: string; phone: string }) {
  const [name, ...rest] = wide.split(' · ')
  return (
    <div className="flex min-h-[52px] items-center gap-3 rounded-[10px] border border-line px-4 py-2 text-sm text-neutral-400">
      <LockIcon />
      <span className="flex-1">
        <span className="font-medium text-neutral-300">{name}</span>
        <span className="hidden sm:inline"> · {rest.join(' · ')}</span>
        <span className="sm:hidden"> · {phone}</span>
      </span>
    </div>
  )
}

function LockIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

function PlusIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <path d="M12 5v14M5 12h14" />
    </svg>
  )
}

function WarnIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0 text-warn">
      <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
      <path d="M12 9v4" />
      <path d="M12 17h.01" />
    </svg>
  )
}
