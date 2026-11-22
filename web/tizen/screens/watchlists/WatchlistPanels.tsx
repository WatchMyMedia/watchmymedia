import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useWatchlistPicker, type WatchlistTarget } from '../../../src/api/watchlists'
import { canUseName, limitWords, MAX_NAME, nameProblem, titlesWords, type Watchlist } from '../../../src/lib/watchlists'
import { useFocus } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { Modal } from '../../ui/Overlays'
import { TextField } from '../../ui/TextField'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { Glyph } from '../details/Glyph'
import { Keyboard } from '../search/OnScreenKeyboard'
import { changeFailed, nameFailed } from './watchlistTv'
import './watchlists.css'

const NAME_FOCUS = 'watchlist/name'
const NEW_FOCUS = 'watchlist/new'
const CONFIRM_FOCUS = 'watchlist/confirm'
const optionFocus = (id: number) => `watchlist/${id}`

/** Puts focus on `id` in the open panel once it has drawn, after a step changes. */
function useStepFocus(step: string, id: () => string | undefined) {
  const focus = useFocus()
  const first = useRef(true)
  useLayoutEffect(() => {
    // The panel's own opening focus is the modal's: only later steps move it here.
    if (first.current) { first.current = false; return }
    focus.restore([id()], 'modal')
    // Only as the step changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus, step])
}

/**
 * A watchlist's name, typed with the on-screen keyboard (or the TV's own,
 * from its key): the Name field, why the name can't be used, and Cancel and
 * the step's own button, which waits for a name that can be.
 */
function NameStep({ kicker, name, setName, lists, self, problem, confirm, busy, onConfirm, onCancel, note }: {
  kicker?: string
  name: string
  setName(name: string): void
  lists: readonly Pick<Watchlist, 'id' | 'name'>[]
  self?: Pick<Watchlist, 'id' | 'name'>
  /** Why the server refused it, in our words. */
  problem?: string
  confirm: string
  busy: boolean
  onConfirm(): void
  onCancel(): void
  note: string
}) {
  const focus = useFocus()
  const shown = nameProblem(name, lists, self?.id) ?? problem
  const can = canUseName(name, lists, self)
  return <>
    <div className={`wl-name${shown ? ' wl-name--bad' : ''}`}>
      {kicker && <p className="wl-name__kicker">{kicker}</p>}
      <span className="wl-name__label">Name</span>
      <TextField id={NAME_FOCUS} label="Name" value={name} onChange={setName} onSubmit={() => { if (can) onConfirm() }}/>
      {shown && <p role="alert" className="wl-name__problem"><Icon name="alert"/>{shown}</p>}
    </div>
    <Keyboard group="watchlist/keys" text={name} setText={setName} max={MAX_NAME} onTvKeyboard={() => focus.find(NAME_FOCUS)?.click()}/>
    <div className="wl-name__actions">
      <Button id="watchlist/cancel" onPress={onCancel}>Cancel</Button>
      <Button id={CONFIRM_FOCUS} variant="primary" busy={busy} disabled={!can} onPress={onConfirm}>{confirm}</Button>
    </div>
    <span className="dt-grow"/>
    <p className="wl-panel__note">{note}</p>
  </>
}

/** The right-hand panel the watchlist steps draw in, wider than a settings panel to hold the keyboard. */
function PanelFrame({ title, sub, onReturn, returnFocus, children }: { title: string; sub?: string; onReturn(): void; returnFocus?: () => boolean; children: ReactNode }) {
  return <Modal onClose={onReturn} className="" returnFocus={returnFocus}>
    <aside role="dialog" aria-modal="true" aria-label={title} className="tv-panel wl-panel">
      <div className="wl-panel__head">
        <h2 className="wl-panel__title">{title}</h2>
        {sub && <p className="wl-panel__sub">{sub}</p>}
      </div>
      {children}
    </aside>
  </Modal>
}

/**
 * Save to watchlist, for someone with two or more: each of their watchlists
 * with a check, saved as it is ticked, and New watchlist…, which names one
 * with the keyboard and saves the title to it too. Done or Return closes it,
 * and focus goes back to what opened it; Return while naming goes back to
 * the list.
 */
export function SavePanel({ target, title, onClose }: { target: WatchlistTarget; title: string; onClose(): void }) {
  const picker = useWatchlistPicker(target)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const [problem, setProblem] = useState<string>()
  const [refused, setRefused] = useState<string>()
  const made = useRef<number | undefined>(undefined)
  const items = picker.items
  const atLimit = (items?.length ?? 0) >= picker.max
  useStepFocus(naming ? 'naming' : 'pick', () => naming ? undefined : made.current !== undefined ? optionFocus(made.current) : NEW_FOCUS)

  const toggle = (w: Watchlist) => {
    setProblem(undefined)
    picker.toggle(w).catch((e: unknown) => setProblem(changeFailed(e, { name: w.name, remove: !!w.contains })))
  }
  const startNaming = () => { made.current = undefined; setName(''); setRefused(undefined); setProblem(undefined); setNaming(true) }
  const create = () => picker.create.mutate(name, {
    onSuccess: (w) => { made.current = w.id; setNaming(false) },
    onError: (e) => setRefused(nameFailed(e, name, items ?? [], picker.max)),
  })

  return <PanelFrame title="Save to watchlist" sub={title} onReturn={naming ? () => setNaming(false) : onClose}>
    {naming
      ? <NameStep kicker="New watchlist" name={name} setName={(n) => { setName(n); setRefused(undefined) }} lists={items ?? []} problem={refused}
        confirm="Create" busy={picker.create.isPending} onConfirm={create} onCancel={() => setNaming(false)}
        note={`Create also saves ${title} to it. Return goes back to your watchlists.`}/>
      : <>
        {!items ? <PickerState query={picker.query}/> : <div className="wl-options">
          <VScroll pad={8}>
            <div role="group" aria-label="Your watchlists" className="wl-options__list">
              {items.map((w, i) => <button key={w.id} type="button" role="checkbox" aria-checked={!!w.contains} data-focus={optionFocus(w.id)}
                data-autofocus={i === 0 || undefined} className="wl-option" onClick={() => toggle(w)}>
                <span className={`wl-box${w.contains ? ' wl-box--on' : ''}`} aria-hidden="true">{w.contains && <Glyph name="check"/>}</span>
                <span className="wl-option__name">{w.name}</span>
                <span className="wl-option__count">{titlesWords(w.titles)}</span>
              </button>)}
              {atLimit
                ? <p className="wl-panel__limit">{limitWords(picker.max)}</p>
                : <button type="button" data-focus={NEW_FOCUS} className="wl-option" onClick={startNaming}>
                  <span className="wl-option__glyph" aria-hidden="true"><Glyph name="bookmarkPlus"/></span>
                  <span className="wl-option__name">New watchlist…</span>
                </button>}
            </div>
          </VScroll>
        </div>}
        {problem && <p role="alert" className="wl-name__problem"><Icon name="alert"/>{problem}</p>}
        {/* Not while they load, so the first watchlist takes focus as it comes. */}
        {(items || picker.query.isError) && <div className="wl-panel__done"><Button id="watchlist/done" onPress={onClose}>Done</Button></div>}
        <span className="dt-grow"/>
        <p className="wl-panel__note">Each one is saved as you tick it. Return closes this panel.</p>
      </>}
  </PanelFrame>
}

/** The picker while its watchlists load, or why they didn't. */
function PickerState({ query }: { query: { isError: boolean; error: unknown; refetch(): unknown } }) {
  if (!query.isError) return <p className="tv-note wl-panel__state" role="status"><span className="tv-spinner" aria-hidden="true"/> Loading your watchlists…</p>
  return <div className="wl-panel__state">
    <p className="tv-note">{`Couldn’t load your watchlists. ${problemWords(query.error).title}.`}</p>
    <Button id="watchlist/retry" onPress={() => void query.refetch()}>Try again</Button>
  </div>
}

/**
 * A watchlist named on its own: New watchlist from the Watchlists screen,
 * or Rename on a watchlist's page. `save` sends the name; Return or Cancel
 * closes the panel, and focus goes back to what opened it.
 */
export function NamePanel({ title, sub, initial = '', confirm, note, lists, self, max, save, onClose, returnFocus }: {
  title: string
  sub?: string
  initial?: string
  confirm: string
  note: string
  lists: readonly Pick<Watchlist, 'id' | 'name'>[]
  self?: Pick<Watchlist, 'id' | 'name'>
  max?: number
  save(name: string): Promise<unknown>
  onClose(): void
  /** Where focus goes once it closes, when not back to what opened it (the tile of a watchlist just made). */
  returnFocus?: () => boolean
}) {
  const [name, setName] = useState(initial)
  const [refused, setRefused] = useState<string>()
  const [busy, setBusy] = useState(false)
  const send = () => {
    setBusy(true)
    save(name).then(onClose, (e: unknown) => { setBusy(false); setRefused(nameFailed(e, name, lists, max, self?.id)) })
  }
  return <PanelFrame title={title} sub={sub} onReturn={onClose} returnFocus={returnFocus}>
    <NameStep name={name} setName={(n) => { setName(n); setRefused(undefined) }} lists={lists} self={self} problem={refused}
      confirm={confirm} busy={busy} onConfirm={send} onCancel={onClose} note={note}/>
  </PanelFrame>
}
