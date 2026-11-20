import { useState } from 'react'
import type { components } from '../../../src/api/schema'
import { Button } from '../../ui/Button'
import { Hint, Poster } from './DetailParts'
import { Glyph } from './Glyph'
import { FOLLOW_TV_LINE, followLabel, followSpoken, showFacts, showStatus } from './showLabels'

type ShowDetail = components['schemas']['ShowDetail']

export type AboutProps = {
  show: ShowDetail
  today: Date
  /** Notify me: whether the person follows the show; undefined hides the bell. */
  following?: boolean
  mark?: { label: string; watched: boolean }
  marking: boolean
  /** Show missing episodes, where the server can say why episodes are missing. */
  canShowMissing: boolean
  showMissing: boolean
  /** Request missing seasons: the seasons to ask for, none hides it. */
  askable: number[]
  hasRelated: boolean
  focused: boolean
  /** The episode on top is in view: About waits below it, unseen, for focus. */
  away: boolean
  onFollow(): void
  onMark(watched: boolean): void
  onShowMissing(on: boolean): void
  onRequest(): void
  onDetails(): void
}

/**
 * About the show (ShowPage's About, past the row): the poster, title, facts
 * and status, the overview, then Notify me, Mark show watched, Show missing
 * episodes, Request missing seasons and Cast & details, with who notifies
 * under them while the bell has focus.
 */
export function ShowAbout(p: AboutProps) {
  const [bell, setBell] = useState(false)
  const status = showStatus(p.show, p.today)
  return <section className={`dt-about${p.away ? ' dt-about--away' : ''}`} aria-label={`About ${p.show.title}`} data-zone="about">
    <Poster path={p.show.posterPath} seed={p.show.title} className="dt-about__poster"/>
    <div className="dt-about__text">
      <h2 className="dt-about__title">{p.show.title}</h2>
      <p className="dt-facts"><span>{showFacts(p.show)}</span>{status && <span className={`dt-pill${status.active ? ' dt-pill--on' : ''}`}>{status.text}</span>}</p>
      {p.show.overview && <p className="dt-overview" style={{ maxWidth: '68.75rem' }}>{p.show.overview}</p>}
      <div className="dt-actions">
        {p.following !== undefined && <span className={p.following ? 'dt-on' : undefined} onFocus={() => setBell(true)} onBlur={() => setBell(false)}>
          <Button id="about/follow" label={followSpoken(p.following, p.show.title)} onPress={p.onFollow}><Glyph name={p.following ? 'bellOn' : 'bell'}/>{followLabel(p.following)}</Button>
        </span>}
        {p.mark && <Button id="about/mark" busy={p.marking} onPress={() => p.onMark(p.mark!.watched)}>{p.mark.label}</Button>}
        {p.canShowMissing && <Button id="about/missing" onPress={() => p.onShowMissing(!p.showMissing)}>{p.showMissing ? 'Hide missing episodes' : 'Show missing episodes'}</Button>}
        {p.askable.length > 0 && <Button id="about/request" onPress={p.onRequest}>Request missing seasons</Button>}
        <Button id="about/details" onPress={p.onDetails}><Glyph name="info"/>Cast &amp; details</Button>
      </div>
      {bell && <p className="dt-note">{FOLLOW_TV_LINE}</p>}
      {p.focused && <Hint items={[{ key: '▲', label: 'back to the episodes' }, ...(p.hasRelated ? [{ key: '▼', label: 'More like this' }] : []), { key: 'Return', label: `leaves ${p.show.title}` }]}/>}
    </div>
  </section>
}
