import type { Section } from '../app/routes'
import { Brand, Icon, type IconName } from './Icon'

const ITEMS: { section: Section; label: string; icon: IconName }[] = [
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'shows', label: 'TV Shows', icon: 'shows' },
  { section: 'movies', label: 'Movies', icon: 'movies' },
  { section: 'live', label: 'Live TV', icon: 'live' },
  { section: 'settings', label: 'Settings', icon: 'settings' },
]

export type RailProps = {
  /** The section on screen: a gold bar beside it. */
  current?: Section
  /** Live TV shows only when the server has it and this account may watch. */
  liveTV: boolean
  /** The Inbox count on Settings, for admins. */
  badge?: number
  /** The account row at the foot: the person's initial, their name and the server's (shown when the menu is open), and its name for a screen reader. */
  account: { initial: string; name: string; server: string; label: string }
  /** OK on the account row: Servers. */
  onAccount(): void
  /** Servers is open (it opens from the left over the shade, and the rail stays closed under it). */
  accountOpen?: boolean
  onOpen(section: Section): void
  /** Goes up by one each time a run of ◀ stops at a row's first card: the current section pulses. */
  pulse: number
}

/**
 * The menu down the left edge, inside the safe area: icons only, the
 * current section marked by a gold bar. With focus it opens into a drawer
 * with labels, the name and who is signed in to which server, over a shade.
 * Only ◀ from the screen enters it (at the current section); ▶ or Return
 * leaves it back to where focus was. While a screen loads, focus waits on
 * the current section with the menu closed (`data-parked`, set by the
 * focus engine).
 */
export function Rail({ current, liveTV, badge, account, onAccount, accountOpen, onOpen, pulse }: RailProps) {
  return <nav className="tv-rail" data-scope="rail" aria-label="Sections">
    <span className="tv-rail__shade" aria-hidden="true"/>
    <div className="tv-rail__inner">
      <div className="tv-rail__brand" aria-hidden="true"><Brand/></div>
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
      <button type="button" data-focus="rail/account" className="tv-rail__item tv-rail__account" aria-haspopup="dialog" aria-expanded={!!accountOpen} aria-label={account.label} onClick={onAccount}>
        <span className="tv-rail__icon"><span className="tv-rail__avatar">{account.initial}</span></span>
        <span className="tv-rail__who"><b>{account.name}</b><span>{account.server}</span></span>
        <span className="tv-rail__chevron"><Icon name="right"/></span>
      </button>
    </div>
  </nav>
}
