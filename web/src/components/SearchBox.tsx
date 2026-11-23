import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { searchQuery } from '../api/queries'
import { seCode } from '../lib/format'
import { matchRank, moveActive, suggestions, SUGGEST_DELAY_MS, SUGGEST_MIN_CHARS, type Suggestion } from '../lib/searchSuggestions'
import { Poster } from './Poster'

function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return settled
}

const styles = {
  bar: {
    form: 'relative ml-auto flex min-h-10 min-w-0 flex-[0_1_420px] items-center gap-2 rounded-[10px] border border-line-2 bg-surface pl-3 pr-2.5 text-neutral-400 focus-within:border-line-3',
    input: 'min-w-0 flex-1 border-0 bg-transparent text-sm text-neutral-100 outline-none placeholder:text-neutral-400',
  },
  page: {
    form: 'relative sm:hidden',
    input: 'min-h-[44px] w-full rounded-md border border-line-2 bg-surface px-3 text-base placeholder:text-neutral-500',
  },
}

/** Whether a key press landed in something that takes typing, where / is a character, not a shortcut. */
function typingIn(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  return !!el && (el.isContentEditable || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT')
}

/**
 * Search with suggestions from the library as you type. A suggestion opens
 * its title or episode; Enter, or the last row, opens the Search page for the
 * words typed, which draws from the same request, so it is there at once.
 * `bar` is the app bar's box, at every width, which / focuses from anywhere
 * on the page; `page` is the Search page's own box.
 */
export function SearchBox({ variant, initial = '', liveTV = false }: { variant: 'bar' | 'page'; initial?: string; liveTV?: boolean }) {
  const navigate = useNavigate()
  const [text, setText] = useState(initial)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const input = useRef<HTMLInputElement>(null)
  const id = useId()
  const listId = `${id}-list`
  const optionId = (i: number) => `${id}-option-${i}`

  const typed = text.trim()
  const term = useDebounced(typed, SUGGEST_DELAY_MS)
  const showList = open && typed.length >= SUGGEST_MIN_CHARS
  const results = useQuery({ ...searchQuery(term), enabled: showList && term.length >= SUGGEST_MIN_CHARS, placeholderData: keepPreviousData })
  // Until the reply for these exact words comes, the last one's rows stay only where they still match.
  const stale = results.isPlaceholderData || term !== typed
  const all = results.data ? suggestions(results.data, typed) : []
  const rows = stale ? all.filter((s) => matchRank(s.title, typed) < 4) : all
  const failed = results.isError && !stale
  const waiting = !failed && (!results.data || stale)
  const count = rows.length + 1 // and "Search for"

  useEffect(() => {
    if (active >= 0) document.getElementById(`${id}-option-${active}`)?.scrollIntoView({ block: 'nearest' })
  }, [active, id])

  // / goes to the bar's box from anywhere that isn't taking typing.
  useEffect(() => {
    if (variant !== 'bar') return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || typingIn(e.target)) return
      e.preventDefault()
      input.current?.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [variant])

  const close = () => {
    setOpen(false)
    setActive(-1)
  }
  const picked = () => {
    close()
    setText('')
    input.current?.blur()
  }

  return (
    <form
      role="search"
      className={styles[variant].form}
      onSubmit={(e) => {
        e.preventDefault()
        if (showList && active >= 0) {
          document.getElementById(optionId(active))?.click()
          return
        }
        if (!typed) return
        close()
        void navigate({ to: '/search', search: { q: typed } })
      }}
    >
      {variant === 'bar' && (
        <svg width="17" height="17" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="shrink-0">
          <circle cx="8.5" cy="8.5" r="5.5" />
          <path d="m13 13 4.5 4.5" />
        </svg>
      )}
      <input
        ref={input}
        type="search"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
          setActive(-1)
        }}
        onFocus={() => setOpen(true)}
        onBlur={close}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault()
            if (!showList) setOpen(true)
            else setActive(moveActive(active, count, e.key === 'ArrowDown' ? 1 : -1))
          } else if (e.key === 'Escape' && showList) {
            e.preventDefault()
            close()
          } else if (e.key === 'Escape' && variant === 'bar') {
            input.current?.blur()
          }
        }}
        placeholder={variant === 'bar' ? (liveTV ? 'Search your library and live TV' : 'Search your library') : 'Search'}
        aria-label="Search titles"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={showList}
        aria-controls={listId}
        aria-activedescendant={showList && active >= 0 ? optionId(active) : undefined}
        autoComplete="off"
        autoFocus={variant === 'page' && !initial}
        enterKeyHint="search"
        className={styles[variant].input}
      />
      {variant === 'bar' && (
        <kbd aria-hidden="true" className="hidden h-[22px] min-w-[22px] place-items-center rounded-[5px] border border-line-3 px-1.5 font-sans text-[11px] font-semibold text-neutral-400 md:inline-grid">
          /
        </kbd>
      )}
      {showList && (
        // Pressing a row mustn't take focus from the box, or it closes before the click lands.
        <div
          onMouseDown={(e) => e.preventDefault()}
          className="absolute right-0 top-full z-40 mt-1 max-h-[calc(100vh-5rem)] w-full overflow-y-auto rounded-lg border border-line-2 bg-surface p-1 text-sm shadow-xl sm:min-w-[26rem]"
        >
          {rows.length === 0 && (
            <p role="status" className="px-3 py-2.5 text-neutral-400">
              {failed ? 'Couldn’t search the library.' : waiting ? 'Searching…' : 'Nothing in your library matches.'}
            </p>
          )}
          <div id={listId} role="listbox" aria-label="In your library">
            {rows.map((s, i) => (
              <SuggestionRow key={`${s.kind}-${s.id}`} s={s} id={optionId(i)} active={active === i} onHover={() => setActive(i)} onPick={picked} />
            ))}
            <Link
              to="/search"
              search={{ q: typed }}
              id={optionId(rows.length)}
              role="option"
              aria-selected={active === rows.length}
              tabIndex={-1}
              onMouseEnter={() => setActive(rows.length)}
              onClick={close}
              className={`${rowClass(active === rows.length)} ${rows.length > 0 ? 'mt-1 border-t border-line' : ''}`}
            >
              <span className="grid w-8 shrink-0 place-items-center text-neutral-400">
                <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                  <circle cx="8.5" cy="8.5" r="5.5" />
                  <path d="m13 13 4.5 4.5" />
                </svg>
              </span>
              <span className="min-w-0 truncate">
                Search for “{typed}”
              </span>
            </Link>
          </div>
        </div>
      )}
    </form>
  )
}

const rowClass = (active: boolean) =>
  `flex min-h-[44px] items-center gap-3 rounded-md px-2 py-1.5 ${active ? 'bg-white/10 text-neutral-100 ring-1 ring-inset ring-accent' : 'text-neutral-200'}`

function SuggestionRow({ s, id, active, onHover, onPick }: { s: Suggestion; id: string; active: boolean; onHover: () => void; onPick: () => void }) {
  const option = { id, role: 'option', 'aria-selected': active, tabIndex: -1, onMouseEnter: onHover, onClick: onPick, className: rowClass(active) } as const
  switch (s.kind) {
    case 'movie':
      return (
        <Link to="/movies/$movieId" params={{ movieId: String(s.id) }} {...option}>
          <Picture path={s.posterPath} />
          <Lines title={s.title}>Movie{s.year ? ` · ${s.year}` : ''}</Lines>
        </Link>
      )
    case 'show':
      return (
        <Link to="/shows/$showId" params={{ showId: String(s.id) }} {...option}>
          <Picture path={s.posterPath} />
          <Lines title={s.title}>Show{s.year ? ` · ${s.year}` : ''}</Lines>
        </Link>
      )
    case 'episode':
      return (
        <Link
          to="/shows/$showId/seasons/$number/episodes/$episodeId"
          params={{ showId: String(s.showId), number: String(s.seasonNumber), episodeId: String(s.id) }}
          {...option}
        >
          <Picture path={s.picturePath} wide />
          <Lines title={s.title}>
            {s.showTitle} · {seCode(s.seasonNumber, s.number)}
          </Lines>
        </Link>
      )
  }
}

/** A poster, or an episode's still, beside the words; the words name it, so it has no alt text. */
function Picture({ path, wide = false }: { path?: string; wide?: boolean }) {
  return (
    <div className={`shrink-0 ${wide ? 'w-14' : 'w-8'}`}>
      <Poster path={path} size={wide ? 'w185' : 'w92'} alt="" ratio={wide ? 'aspect-video' : 'aspect-[2/3]'} className="rounded" />
    </div>
  )
}

function Lines({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="truncate font-medium">{title}</div>
      <div className="truncate text-xs text-neutral-500">{children}</div>
    </div>
  )
}
