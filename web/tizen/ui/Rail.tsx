import type { Section } from '../app/routes'
import { Icon, type IconName } from './Icon'

const ITEMS: { section: Section; label: string; icon: IconName }[] = [
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'shows', label: 'TV Shows', icon: 'shows' },
  { section: 'movies', label: 'Movies', icon: 'movies' },
  { section: 'live', label: 'Live TV', icon: 'live' },
  { section: 'settings', label: 'Settings', icon: 'settings' },
]

export type RailProps = {
  /** The section on screen, in gold. */
  current?: Section
  /** Live TV shows only when the server has it and this account may watch. */
  liveTV: boolean
  /** The Inbox count on Settings, for admins. */
  badge?: number
  userName: string
  onOpen(section: Section): void
  /** Goes up by one each time a run of ◀ stops at a row's first card: the current section pulses. */
  pulse: number
}

/**
 * The section rail down the left edge, icons only until it has focus, when
 * it opens with labels. Only ◀ from the screen enters it (at the current
 * section); ▶ leaves it back to where focus was.
 */
export function Rail({ current, liveTV, badge, userName, onOpen, pulse }: RailProps) {
  return <nav className="tv-rail" data-scope="rail" aria-label="Sections">
    {ITEMS.filter((i) => i.section !== 'live' || liveTV).map((i) => {
      const on = i.section === current
      const count = i.section === 'settings' ? badge : undefined
      return <button key={on ? `${i.section}-${pulse}` : i.section} type="button" data-focus={`rail/${i.section}`}
        className={`tv-rail__item${on && pulse ? ' tv-rail__item--pulse' : ''}`} aria-current={on ? 'page' : undefined}
        aria-label={count ? `${i.label}, ${count} in the Inbox` : i.label} onClick={() => onOpen(i.section)}>
        <span className="tv-rail__icon"><Icon name={i.icon}/>{count ? <span className="tv-rail__badge">{count}</span> : null}</span>
        <span className="tv-rail__label">{i.label}</span>
      </button>
    })}
    <span className="tv-rail__spacer"/>
    <button type="button" data-focus="rail/account" className="tv-rail__item" aria-label={`${userName}, Settings`} onClick={() => onOpen('settings')}>
      <span className="tv-rail__icon"><span className="tv-rail__avatar">{userName.slice(0, 1).toUpperCase()}</span></span>
      <span className="tv-rail__label">{userName}</span>
    </button>
  </nav>
}
