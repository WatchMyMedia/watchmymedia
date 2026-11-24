import { memo, useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import type { components } from '../../../src/api/schema'
import { rowWindow } from '../../app/focusRules'
import { routeOn, useGroupMemory, useLevel34, useMediaUrls, useNav, usePageSource } from '../../app/hooks'
import { Card } from '../../ui/Card'
import { Banner, SidePanel } from '../../ui/Overlays'
import { placeholderTone } from '../../ui/tones'
import { Row } from '../../ui/Row'
import { Glyph, type GlyphName } from './Glyph'
import { castLabel } from './movieLabels'
import type { WatchLine } from './titleWords'
import { titleCard, type RelatedRow } from './relatedCards'
import { initials, toneStyle, type Notice } from './detailWords'
import { castPress, PERSON_UNKNOWN } from './personWords'
import { showNote } from '../../ui/notes'

// The parts the movie page and the show page share (the Android TV app's
// DetailParts.kt and ShowPageMore.kt), drawn with the kit's tokens.

type Credit = components['schemas']['Credit']

/** A picture that leaves the placeholder showing when it doesn't load. */
export function Picture({ src, className }: { src?: string; className?: string }) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) return null
  return <img className={className ?? 'dt-img'} src={src} alt="" decoding="async" draggable={false} onError={() => setFailed(true)}/>
}

/** The picture behind a page, filling the screen under a scrim that keeps the text on the left readable; its tone while it loads or without one. */
export const Backdrop = memo(function Backdrop({ path, seed, kind, size = 'w1280' }: { path?: string; seed: string; kind: 'movie' | 'show'; size?: 'w780' | 'w1280' }) {
  const media = useMediaUrls()
  return <div className={`dt-backdrop dt-backdrop--${kind}`} style={toneStyle(seed)} aria-hidden="true">
    <Picture key={path} src={media.imageUrl(path, size)}/>
    <span className="dt-backdrop__fade"/>
  </div>
})

export type MenuItem = { id: string; label: string; icon: GlyphName; danger?: boolean; onPress(): void }

/**
 * A menu of actions from the right, for what other screens open (a card's
 * Hold OK): the title, a line over it, and the rows with their icons, the
 * dangerous ones last under a line. Return closes it, and focus goes back
 * to what opened it.
 */
export function MorePanel({ kicker, title, items, note, start, label, onClose }: {
  kicker: string
  title: string
  items: MenuItem[]
  note?: string
  /** The item focus starts on; the first without one. */
  start?: string
  /** The menu's name for a screen reader: "More for …" without one. */
  label?: string
  onClose(): void
}) {
  const firstDanger = items.findIndex((i) => i.danger)
  return <SidePanel title={title} sub={kicker} onClose={onClose} foot={note && <p className="dt-panel-note">{note}</p>}>
    <div role="menu" aria-label={label ?? `More for ${title}`} className="dt-menu">
      {items.map((item, i) => <div key={item.id}>
        {i === firstDanger && i > 0 && <span role="separator" className="dt-menu__line"/>}
        <button type="button" role="menuitem" data-focus={item.id} data-autofocus={(start ? item.id === start : i === 0) || undefined} className="dt-menu__item" onClick={item.onPress}>
          <Glyph name={item.icon}/>{item.label}
        </button>
      </div>)}
    </div>
  </SidePanel>
}

/** The notice the page's useNotice holds, at the top right: it takes no focus, and Return goes on to the screen, so it still leaves in one step. */
export function NoticeBanner({ notice }: { notice?: Notice }) {
  return notice ? <Banner key={notice.n} icon={notice.problem ? 'alert' : 'check'} title={notice.title}>{notice.body}</Banner> : null
}

/** How many people fit across beside the page's left edge, whole. */
const PEOPLE_IN_VIEW = 8

/**
 * The cast as a row of people cards: a round picture, the name and the part
 * played, a focus stop each, ◀ ▶ along it and stopping at its ends. OK opens
 * that person's movies and shows on this server (API level 34). A credit the
 * server hasn't matched to a person yet is dimmed and says why; below level
 * 34 OK does nothing, as before.
 */
export const PeopleRow = memo(function PeopleRow({ cast, group, autoFocus }: { cast: Credit[]; group: string; autoFocus?: boolean }) {
  const media = useMediaUrls()
  const nav = useNav()
  const source = usePageSource()
  // A person's titles on the server the page is from: its own level decides.
  const level34 = useLevel34() && (!source?.peer || (source.apiLevel ?? 0) >= 34)
  const remembered = useGroupMemory(group)
  const [focus, setFocus] = useState(() => Math.max(0, cast.findIndex((_, i) => `${group}/${i}` === remembered)))
  // The person before the focused one stays in view, and the row never moves past its last full screen.
  const { start } = rowWindow(focus, cast.length, PEOPLE_IN_VIEW)
  const onFocus = (e: FocusEvent) => {
    const i = Number((e.target as HTMLElement).closest?.('[data-index]')?.getAttribute('data-index') ?? -1)
    if (i >= 0 && i !== focus) setFocus(i)
  }
  return <div className="dt-people-view" data-clip>
    <div className="dt-people" data-row data-group={group} onFocus={onFocus} style={{ transform: `translateX(calc(${-start} * (var(--person-w) + 1.75rem)))` }}>
      {cast.map((c, i) => {
        const [a, b] = placeholderTone(c.name)
        const press = castPress(c, level34)
        const onPress = press?.kind === 'open' ? () => nav.push(routeOn(press.route, source)) : press?.kind === 'unknown' ? () => showNote(PERSON_UNKNOWN, 'info') : undefined
        return <button key={`${i}${c.name}`} type="button" data-index={i} data-focus={`${group}/${i}`} data-autofocus={(autoFocus && i === 0) || undefined} className="tv-person"
          aria-label={castLabel(c)} aria-disabled={press?.kind === 'unknown' || undefined} onClick={onPress}>
          <span className="tv-person__pic" style={{ '--ph-a': a, '--ph-b': b } as CSSProperties}>
            <span className="tv-person__initials" aria-hidden="true">{initials(c.name)}</span>
            <Picture src={media.imageUrl(c.profilePath, 'w185')}/>
          </span>
          <span className="tv-person__name">{c.name}</span>
          {c.character && <span className="tv-person__part">{c.character}</span>}
        </button>
      })}
    </div>
  </div>
})

/** How far it got and when it would end: the gold bar while partway, "1 h 12 min left" in bold, "Ends at 9:24 PM" after it. */
export function WatchBar({ line }: { line: WatchLine }) {
  return <div className="dt-watch">
    {line.progress !== undefined && <span className="tv-bar dt-watch__bar" aria-hidden="true"><i style={{ width: `${Math.round(line.progress * 100)}%` }}/></span>}
    <span className="dt-watch__where">{line.where}</span>
    {line.ends && <span className="dt-watch__ends">{line.ends}</span>}
  </div>
}

/** One related row: Home's poster cards; OK opens the title's page on top of this one, and Return comes back to the card. */
export const RelatedSection = memo(function RelatedSection({ row, hint }: { row: RelatedRow; hint?: ReactNode }) {
  const media = useMediaUrls()
  const nav = useNav()
  const source = usePageSource()
  const cards = row.items.map(titleCard)
  return <div className="dt-related" data-vitem data-zone={`related/${row.id}`}>
    <Row id={`related/${row.id}`} title={row.title} subtitle={row.subtitle} variant="poster" items={cards} itemKey={(c) => c.key}
      renderItem={(c, id) => <Card id={id} variant="poster" title={c.title} subtitle={c.subtitle} image={media.imageUrl(c.posterPath, 'w342')} badges={c.badges} done={c.done}
        progress={c.progress} label={c.label} onPress={() => nav.push(routeOn(c.route, source))}/>}/>
    {hint}
  </div>
})
