import { useState } from 'react'
import { keyRows, MAX_QUERY, typeKey, type KeyboardMode, type KeyPress } from './keyboard'
import './search.css'

/**
 * The on-screen keyboard: Search's, and the one a watchlist's name is typed
 * with. Its keys remember the last one focused (`group`); focus starts on q
 * where `autoFocus`. Search's (`layout="search"`) is four rows of ten and
 * Space, Delete, Clear and TV keyboard under them, since case doesn't
 * matter to a search; a name's keeps Shift and the symbols. The TV's own
 * keyboard, for accents, other alphabets and the voice key, is one key away.
 */
export function Keyboard({ group, text, setText, max = MAX_QUERY, layout = 'full', autoFocus = true, note, onTvKeyboard }: {
  group: string
  text: string
  setText(t: string): void
  max?: number
  layout?: 'search' | 'full'
  autoFocus?: boolean
  note?: string
  onTvKeyboard(): void
}) {
  const [mode, setMode] = useState<KeyboardMode>('letters')
  const [shift, setShift] = useState(false)
  const press = (p: KeyPress) => setText(typeKey(text, p, max))
  const rows = keyRows(layout === 'search' ? 'letters' : mode, layout === 'search' ? false : shift)
  if (layout === 'search') {
    return <div className="search-kb" role="group" aria-label="Keyboard" data-group={group}>
      {rows.map((row, r) => row.map((c, k) => <button key={`${r}-${k}`} type="button" data-focus={`key/${r}-${k}`} data-autofocus={(autoFocus && r === 1 && k === 0) || undefined}
        className="search-kb__key" onClick={() => press({ kind: 'char', char: c })}>{c}</button>))}
      <button type="button" data-focus="key/space" className="search-kb__key search-kb__key--3" onClick={() => press({ kind: 'space' })}>Space</button>
      <button type="button" data-focus="key/delete" className="search-kb__key search-kb__key--2" onClick={() => press({ kind: 'delete' })}>Delete</button>
      <button type="button" data-focus="key/clear" className="search-kb__key search-kb__key--2" onClick={() => press({ kind: 'clear' })}>Clear</button>
      <button type="button" data-focus="key/tv" className="search-kb__key search-kb__key--3" onClick={onTvKeyboard}>TV keyboard</button>
      {note && <p className="search-kb__note">{note}</p>}
    </div>
  }
  return <div className="search-keys" aria-label="Keyboard" data-group={group}>
    {rows.map((row, r) => <div key={r} className="search-keys__row">
      {row.map((c, k) => <button key={k} type="button" data-focus={`key/${r}-${k}`} data-autofocus={(autoFocus && r === 1 && k === 0) || undefined} className="search-key"
        onClick={() => { press({ kind: 'char', char: c }); if (shift) setShift(false) }}>{c}</button>)}
    </div>)}
    <div className="search-keys__row">
      <button type="button" data-focus="key/shift" className="search-key search-key--fn" style={{ width: '6.5rem' }} aria-pressed={shift} disabled={mode === 'symbols'}
        onClick={() => setShift((s) => !s)}>{shift ? '⇧ ABC' : '⇧ abc'}</button>
      <button type="button" data-focus="key/mode" className="search-key search-key--fn" style={{ width: '6.5rem' }} onClick={() => { setMode((m) => m === 'letters' ? 'symbols' : 'letters'); setShift(false) }}>
        {mode === 'letters' ? '#+=' : 'abc'}</button>
      <button type="button" data-focus="key/space" className="search-key search-key--fn" style={{ width: '12.5rem' }} onClick={() => press({ kind: 'space' })}>Space</button>
      <button type="button" data-focus="key/delete" className="search-key" style={{ width: '6.5rem' }} aria-label="Delete" onClick={() => press({ kind: 'delete' })}>⌫</button>
      <button type="button" data-focus="key/clear" className="search-key search-key--fn" style={{ width: '10.5rem' }} onClick={() => press({ kind: 'clear' })}>Clear</button>
    </div>
    <button type="button" data-focus="key/tv" className="search-key search-key--wide" onClick={onTvKeyboard}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M2 6h20v12H2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/></svg>
      Use the TV’s keyboard
    </button>
    {note && <p className="search-keys__note">{note}</p>}
  </div>
}
