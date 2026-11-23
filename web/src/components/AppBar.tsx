import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useRouterState, type LinkProps } from '@tanstack/react-router'
import { librariesQuery, serverQuery } from '../api/queries'
import { useEventState } from '../api/events'
import { useAuth } from '../authContext'
import { arrivedWords } from '../lib/arrivals'
import { libraryStatusLine } from '../lib/libraryStatus'
import { sectionOf, type Section } from '../lib/sections'
import { BrandLockup, BrandMark } from './BrandMark'
import { CastButton } from './Cast'
import { GetAppMenu } from './GetApp'
import { SearchBox } from './SearchBox'
import { StatusPanel } from './StatusPanel'
import { markArrivalsSeen, useArrivals } from './useArrivals'

export type Sections = {
  home: boolean
  movies: boolean
  tv: boolean
  live: boolean
  activity: boolean
  /** Admins see a dot on Activity while anything plays. */
  playing: number
}

const LINKS: { id: Section; to: '/' | '/movies' | '/tv' | '/live-tv' | '/activity'; label: string }[] = [
  { id: 'home', to: '/', label: 'Home' },
  { id: 'movies', to: '/movies', label: 'Movies' },
  { id: 'tv', to: '/tv', label: 'TV Shows' },
  { id: 'live', to: '/live-tv', label: 'Live TV' },
  { id: 'activity', to: '/activity', label: 'Activity' },
]

/**
 * The app bar: the mark, the sections (from md up; phones have them in the
 * foot bar), search, Get the app on computers, Cast, and the account menu,
 * which holds Settings and the admin's Library status.
 */
export function AppBar({ sections, castHere, getApp, search }: { sections: Sections; castHere: boolean; getApp: boolean; search: boolean }) {
  const path = useRouterState({ select: (s) => s.location.pathname })
  const active = sectionOf(path)
  const [status, setStatus] = useState(false)
  return (
    <header className="sticky top-0 z-30 border-b border-line bg-ground/92 backdrop-blur">
      <div className="wrap relative flex min-h-[54px] items-center gap-2 md:min-h-[60px]">
        <Link to="/" className="mr-0 flex shrink-0 items-center md:mr-3" aria-label="WatchMyMedia home">
          <BrandMark size={28} className="md:hidden" />
          <span className="hidden md:inline">
            <BrandLockup size={28} />
          </span>
        </Link>
        <nav aria-label="Sections" className="hidden items-center gap-0.5 md:flex">
          {LINKS.filter((l) => sections[l.id]).map((l) => (
            <Link
              key={l.id}
              to={l.to}
              aria-current={active === l.id ? 'page' : undefined}
              className={`relative inline-flex min-h-[60px] items-center whitespace-nowrap px-[13px] text-[14.5px] hover:text-neutral-100 ${active === l.id ? 'font-semibold text-neutral-100' : 'font-medium text-neutral-400'}`}
            >
              {l.label}
              {l.id === 'activity' && sections.playing > 0 && (
                <span role="img" aria-label={`, ${sections.playing} playing now`} className="ml-1.5 size-[7px] rounded-full bg-accent" />
              )}
              {active === l.id && <span aria-hidden="true" className="absolute inset-x-[13px] -bottom-px h-0.5 rounded-full bg-accent" />}
            </Link>
          ))}
        </nav>
        {search ? <SearchBox variant="bar" liveTV={sections.live} /> : <span className="ml-auto" />}
        {getApp && <GetAppMenu />}
        {castHere && <CastButton />}
        <AccountMenu onStatus={() => setStatus((s) => !s)} />
        {status && <StatusPanel onClose={() => setStatus(false)} />}
      </div>
    </header>
  )
}

/** The account menu: the person, their history, lists and requests, Settings, the admin's Library status, Sign out. */
function AccountMenu({ onStatus }: { onStatus: () => void }) {
  const auth = useAuth()
  const user = auth.user
  const admin = user?.isAdmin ?? false
  const server = useQuery(serverQuery)
  const libraries = useQuery(librariesQuery)
  const events = useEventState()
  const { offered, arrived, inbox, manage } = useArrivals()
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const id = useId()
  const libs = libraries.data?.items ?? []
  const statusLine = libraryStatusLine(libs, events.scans, events.matches)

  useEffect(() => {
    if (!open) return
    const away = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
  }, [open])

  const close = (refocus = false) => {
    setOpen(false)
    if (refocus) button.current?.focus()
  }
  const items = () => Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
  const focusItem = (i: number) => {
    const all = items()
    if (all.length) all[(i + all.length) % all.length].focus()
  }

  const name = user?.name ?? ''
  const initial = name.charAt(0).toUpperCase()
  const serverName = server.data?.name
  const role = [admin ? 'Admin' : '', serverName ?? ''].filter(Boolean).join(' · ')
  const arrivedLabel = arrivedWords(arrived.length)
  return (
    <div
      ref={root}
      className="relative shrink-0"
      onBlur={(e) => {
        if (open && e.relatedTarget && !root.current?.contains(e.relatedTarget)) setOpen(false)
      }}
    >
      <button
        ref={button}
        type="button"
        aria-label={`Account: ${name}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => {
          setOpen((o) => !o)
          if (!open) requestAnimationFrame(() => focusItem(0))
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' && !open) {
            e.preventDefault()
            setOpen(true)
            requestAnimationFrame(() => focusItem(0))
          }
        }}
        className="relative grid size-9 place-items-center rounded-full bg-brand text-sm font-semibold text-white ring-1 ring-inset ring-white/20"
      >
        {initial}
        {!events.connected && (
          <span role="img" aria-label="Reconnecting to the server" title="Reconnecting to the server" className="absolute -right-0.5 -top-0.5 size-2.5 rounded-full bg-warn ring-2 ring-ground" />
        )}
      </button>
      {open && (
        <div
          ref={menu}
          id={id}
          role="menu"
          aria-label="Account"
          onKeyDown={(e) => {
            const all = items()
            const at = all.indexOf(document.activeElement as HTMLElement)
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              focusItem(at + 1)
            } else if (e.key === 'ArrowUp') {
              e.preventDefault()
              focusItem(at - 1)
            } else if (e.key === 'Home') {
              e.preventDefault()
              focusItem(0)
            } else if (e.key === 'End') {
              e.preventDefault()
              focusItem(-1)
            } else if (e.key === 'Escape') {
              e.preventDefault()
              close(true)
            } else if (e.key === 'Tab') {
              close()
            }
          }}
          className="menu absolute right-0 top-[calc(100%+8px)] w-[292px] max-w-[calc(100vw-2rem)]"
        >
          <div className="flex min-h-[52px] items-center gap-2.5 px-2.5">
            <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-full bg-brand text-sm font-semibold text-white ring-1 ring-inset ring-white/20">
              {initial}
            </span>
            <span className="flex min-w-0 flex-col leading-tight">
              <b className="truncate font-semibold">{name}</b>
              {role && <span className="truncate text-[12.5px] text-neutral-400">{role}</span>}
            </span>
          </div>
          <hr className="menu-sep" />
          <MenuLink to="/activity/history" search={admin && user ? { user: user.id } : {}} onPick={() => close()}>
            My history
          </MenuLink>
          <MenuLink to="/watchlists" onPick={() => close()}>
            My lists
          </MenuLink>
          {offered && (
            <MenuLink
              to="/settings/requests"
              onPick={() => {
                markArrivalsSeen(arrived.map((r) => r.id))
                close()
              }}
              badge={arrivedLabel && <span className="pill pill-gold ml-auto">{arrivedLabel}</span>}
            >
              My requests
            </MenuLink>
          )}
          {admin && (manage || inbox > 0) && (
            <MenuLink to="/settings/inbox" onPick={() => close()} badge={inbox > 0 && <span className="pill pill-gold ml-auto">{inbox} waiting</span>}>
              Inbox
            </MenuLink>
          )}
          <hr className="menu-sep" />
          <MenuLink to="/settings" onPick={() => close()}>
            Settings
          </MenuLink>
          {admin && libs.length > 0 && (
            <button
              type="button"
              role="menuitem"
              tabIndex={-1}
              onClick={() => {
                close()
                onStatus()
              }}
              className="menu-item min-h-[52px] py-1.5"
            >
              <span className="flex flex-1 flex-col gap-[5px] leading-tight">
                Library status
                <span className="whitespace-normal text-[12.5px] text-neutral-400">{statusLine.text}</span>
                {statusLine.progress !== undefined && (
                  <span className="meter" aria-hidden="true">
                    <i style={{ width: `${statusLine.progress}%` }} />
                  </span>
                )}
              </span>
            </button>
          )}
          <hr className="menu-sep" />
          <button type="button" role="menuitem" tabIndex={-1} onClick={() => void auth.logout()} className="menu-item">
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}

function MenuLink({ to, search, onPick, badge, children }: { to: LinkProps['to']; search?: LinkProps['search']; onPick: () => void; badge?: ReactNode; children: ReactNode }) {
  return (
    <Link to={to} search={search} role="menuitem" tabIndex={-1} onClick={onPick} className="menu-item">
      {children}
      {badge}
    </Link>
  )
}

const FOOT_ICONS: Record<Section, ReactNode> = {
  home: <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />,
  movies: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M7.5 4v16M16.5 4v16M3 9.3h4.5M3 14.7h4.5M16.5 9.3H21M16.5 14.7H21" />
    </>
  ),
  tv: (
    <>
      <rect x="2.5" y="5" width="19" height="13" rx="2" />
      <path d="M8 21h8" />
    </>
  ),
  live: (
    <>
      <circle cx="12" cy="11" r="1.6" />
      <path d="M12 13v8M8.6 7.6a4.8 4.8 0 0 0 0 6.8M15.4 7.6a4.8 4.8 0 0 1 0 6.8M5.8 4.8a8.8 8.8 0 0 0 0 12.4M18.2 4.8a8.8 8.8 0 0 1 0 12.4" />
    </>
  ),
  activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
}

/** Phone width: the sections as a bar at the foot of the window. */
export function FootBar({ sections }: { sections: Sections }) {
  const path = useRouterState({ select: (s) => s.location.pathname })
  const active = sectionOf(path)
  const shown = LINKS.filter((l) => sections[l.id])
  if (shown.length < 2) return null
  return (
    <nav aria-label="Sections" className="grid auto-cols-fr grid-flow-col border-t border-line bg-ground/96 pb-[env(safe-area-inset-bottom)] backdrop-blur md:hidden">
      {shown.map((l) => (
        <Link
          key={l.id}
          to={l.to}
          aria-current={active === l.id ? 'page' : undefined}
          className={`flex min-h-[58px] flex-col items-center justify-center gap-[3px] text-[11px] ${active === l.id ? 'font-semibold text-accent' : 'font-medium text-neutral-400'}`}
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            {FOOT_ICONS[l.id]}
          </svg>
          <span className="relative">
            {l.label}
            {l.id === 'activity' && sections.playing > 0 && <span role="img" aria-label={`, ${sections.playing} playing now`} className="absolute -right-2.5 top-0 size-1.5 rounded-full bg-accent" />}
          </span>
        </Link>
      ))}
    </nav>
  )
}
