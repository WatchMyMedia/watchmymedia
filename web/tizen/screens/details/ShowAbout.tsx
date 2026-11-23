import type { components } from '../../../src/api/schema'
import type { WatchlistTarget } from '../../../src/api/watchlists'
import { WatchlistButton } from '../watchlists/WatchlistButton'
import { Button } from '../../ui/Button'
import { PeopleRow } from './DetailParts'
import { Glyph } from './Glyph'
import { FOLLOW_TV_LINE, followLabel, followSpoken } from './showLabels'
import { showFactLine } from './titleWords'

type ShowDetail = components['schemas']['ShowDetail']

export type AboutProps = {
  show: ShowDetail
  /** Notify me: whether the person follows the show; undefined hides the bell. */
  following?: boolean
  /** Watchlist beside it, where the server keeps watchlists: the show, and the ids of the watchlists holding it. */
  watchlist?: { target: WatchlistTarget; holding: readonly number[] }
  onWatchlistProblem(words: string): void
  mark?: { label: string; watched: boolean }
  marking: boolean
  /** Show missing episodes, where the server can say why episodes are missing. */
  canShowMissing: boolean
  showMissing: boolean
  /** Request missing seasons: the seasons to ask for, none hides it. */
  askable: number[]
  /** The episode on top is in view: About waits below it, unseen, for focus. */
  away: boolean
  onFollow(): void
  onMark(watched: boolean): void
  onShowMissing(on: boolean): void
  onRequest(): void
}

/**
 * About the show, past the episodes: its title, one line of facts with
 * whether it still airs, three lines of story, then Following, Watchlist,
 * Mark show watched (and Show missing episodes and Request missing seasons
 * where the server can say why episodes are missing), what following does,
 * and the cast as a row of people cards.
 */
export function ShowAbout(p: AboutProps) {
  return <>
    <section className={`dt-about${p.away ? ' dt-about--away' : ''}`} aria-label={`About ${p.show.title}`} data-zone="about">
      <p className="tv-kicker">About the show</p>
      <h2 className="tv-title dt-about__title">{p.show.title}</h2>
      <p className="dt-factline">{showFactLine(p.show)}</p>
      {p.show.overview && <p className="dt-story dt-story--three">{p.show.overview}</p>}
      <div className="dt-actions" data-group="about">
        {p.following !== undefined && <span className={p.following ? 'dt-on' : undefined}>
          <Button id="about/follow" label={followSpoken(p.following, p.show.title)} pressed={p.following} onPress={p.onFollow}><Glyph name={p.following ? 'bellOn' : 'bell'}/>{followLabel(p.following)}</Button>
        </span>}
        {p.watchlist && <WatchlistButton id="about/watchlist" target={p.watchlist.target} holding={p.watchlist.holding} title={p.show.title} onProblem={p.onWatchlistProblem}/>}
        {p.mark && <Button id="about/mark" busy={p.marking} onPress={() => p.onMark(p.mark!.watched)}>{p.mark.label}</Button>}
        {p.canShowMissing && <Button id="about/missing" onPress={() => p.onShowMissing(!p.showMissing)}>{p.showMissing ? 'Hide missing episodes' : 'Show missing episodes'}</Button>}
        {p.askable.length > 0 && <Button id="about/request" onPress={p.onRequest}>Request missing seasons</Button>}
      </div>
      {p.following !== undefined && <p className="tv-note dt-about__note">{FOLLOW_TV_LINE}</p>}
    </section>
    {p.show.credits.length > 0 && <section className={`dt-below dt-below--about${p.away ? ' dt-about--away' : ''}`} aria-label="Cast" data-zone="cast">
      <h2 className="tv-head">Cast</h2>
      <PeopleRow cast={p.show.credits.slice(0, 12)} group="cast"/>
    </section>}
  </>
}
