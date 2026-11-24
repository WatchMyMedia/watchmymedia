import type { FocusEvent } from 'react'
import { timeOfDay } from '../../../src/lib/liveGuide'
import type { ScreenRow } from '../../../src/lib/screenRows'
import { useMediaFor } from '../../app/hooks'
import { Card } from '../../ui/Card'
import { Row } from '../../ui/Row'
import { useCardActions, useNow } from './browseHooks'
import { rowGroup } from './homePages'
import { holdHint, okWords, rowHeading, rowVariant, type RowCard } from './rowCards'

/** The boards' glyphs the kit's icons don't have, in the same 24 × 24 two-pixel line. */
const GLYPHS = {
  grid: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
  guide: 'M3 5h18v14H3zM3 10h18M9 10v9',
  sliders: 'M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M16 4v4M10 10v4M18 16v4',
  plus: 'M12 5v14M5 12h14',
  up: 'M6 15l6-6 6 6',
  notFound: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6M12 17h.01',
} as const

export type GlyphName = keyof typeof GLYPHS

export function Glyph({ name }: { name: GlyphName }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={GLYPHS[name]}/></svg>
}

/** A row's last card in words: See all (a library's grid or a list's page) or Live TV's Full guide. */
function MoreCard({ id, card, onPress }: { id: string; card: RowCard; onPress?: () => void }) {
  const more = card.more!
  return <button type="button" data-focus={id} className={`tv-card tv-card--${card.variant} browse-more`} aria-label={card.label} onClick={onPress}>
    <span className="tv-card__art browse-more__art">
      <span className="browse-more__glyph"><Glyph name={more.glyph}/></span>
      <span className="browse-more__title">{more.title}</span>
      {more.detail && <span className="browse-more__detail">{more.detail}</span>}
    </span>
  </button>
}

/**
 * The end of a row filled from every server: a server still being asked,
 * which takes no focus, or one that didn't answer, with Try again.
 */
function TileCard({ id, card, onPress }: { id: string; card: RowCard; onPress?: () => void }) {
  const tile = card.tile!
  if (tile.kind === 'wait') {
    return <span className={`tv-card tv-card--${card.variant} browse-tile`} role="status">
      <span className="tv-card__art browse-tile__art"><span className="tv-spinner" aria-hidden="true"/><span className="browse-tile__text">{tile.text}</span></span>
    </span>
  }
  return <button type="button" data-focus={id} className={`tv-card tv-card--${card.variant} browse-tile browse-tile--retry`} aria-label={`${tile.text} Try again`} onClick={onPress}>
    <span className="tv-card__art browse-tile__art"><span className="browse-tile__text">{tile.text}</span><span className="tv-btn browse-tile__btn">Try again</span></span>
  </button>
}

/** One card of a row, with what OK and Hold OK do. */
export function RowCardView({ card, id, imageUrl }: { card: RowCard; id: string; imageUrl?: string }) {
  const { run } = useCardActions()
  const press = card.press && (() => run(card.press))
  if (card.tile) return <span className="browse-card"><TileCard id={id} card={card} onPress={press}/></span>
  return <span className="browse-card" data-hint={okWords(card.press)} data-hold={holdHint(card.hold)}>
    {card.more ? <MoreCard id={id} card={card} onPress={press}/>
      : <Card id={id} variant={card.variant} title={card.title} subtitle={card.subtitle} image={imageUrl} progress={card.progress} badges={card.badges} done={card.done} live={card.live}
        label={card.label} onPress={press} onHold={card.hold && (() => run(card.hold))}/>}
  </span>
}

/**
 * A server row drawn with the kit's Row: its heading and the words beside
 * it, the counter ("Row 2 of 7"), and its cards. `onFocusIndex` hears which
 * card has focus (Home's panel follows it).
 */
export function RowView({ row, cards, counter, listings, onFocusIndex, dimPast }: {
  row: ScreenRow; cards: readonly RowCard[]; counter?: string; listings: boolean; onFocusIndex?: (index: number) => void
  /** Dims the cards before the focused one (Home's row screens). */
  dimPast?: boolean
}) {
  const mediaFor = useMediaFor()
  const now = useNow(row.style === 'live' && listings)
  const [title, small] = rowHeading(row, listings, row.style === 'live' ? timeOfDay(now) : undefined)
  const onFocus = onFocusIndex && ((e: FocusEvent) => {
    const cell = (e.target as HTMLElement).closest?.('[data-index]')
    if (cell) onFocusIndex(Number(cell.getAttribute('data-index')))
  })
  return <div className="browse-row" data-home-row onFocus={onFocus}>
    <Row id={rowGroup(row)} title={title} subtitle={small} counter={counter} variant={rowVariant(row)} items={cards} itemKey={(c) => c.key} dimPast={dimPast}
      renderItem={(c, id) => <RowCardView card={c} id={id} imageUrl={c.image && mediaFor(c.server).imageUrl(c.image.path, c.image.size)}/>}/>
  </div>
}
