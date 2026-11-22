import { useEffect, useState } from 'react'
import { useWatchlistPress, type WatchlistTarget } from '../../../src/api/watchlists'
import { onlyName } from '../../../src/lib/watchlists'
import { Button } from '../../ui/Button'
import { Glyph } from '../details/Glyph'
import { SavePanel } from './WatchlistPanels'
import { buttonHint, changeFailed } from './watchlistTv'

/**
 * Watchlist on a movie's or a show's page: a bookmark, filled and gold once
 * the title is on a watchlist ("On Watchlist", "On 2 watchlists"). With one
 * watchlist or none, OK saves or removes at once and the label changing is
 * all that says so; with two or more it opens Save to watchlist. Draw it
 * only where the server keeps watchlists and the title has a TMDB match.
 */
export function WatchlistButton({ id, target, holding, title, onProblem, onHint }: {
  id: string
  target: WatchlistTarget
  /** The ids of the watchlists holding the title, from its page. */
  holding: readonly number[]
  title: string
  /** A save or a removal that didn't happen, in our words. */
  onProblem(words: string): void
  /** What OK does, while the button has focus. */
  onHint?(words: string | undefined): void
}) {
  const wl = useWatchlistPress(target, { holding }, (e) => onProblem(changeFailed(e, false)))
  const [picking, setPicking] = useState(false)
  const [focused, setFocused] = useState(false)
  const hint = buttonHint(wl.picker, wl.saved, onlyName(wl.lists))
  useEffect(() => { onHint?.(focused ? hint : undefined) }, [onHint, focused, hint])

  const press = async () => {
    const r = await wl.press()
    if (r.kind === 'picker') setPicking(true)
    // A TV says nothing after a change, so nothing waits for Undo.
    else if (r.kind === 'removed') void r.commit()
    else if (r.kind === 'failed') onProblem(changeFailed(r.error, true))
  }
  return <>
    <span className={wl.saved ? 'wl-on' : undefined} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}>
      <Button id={id} pressed={wl.saved} onPress={() => void press()}><Glyph name={wl.saved ? 'bookmarkOn' : 'bookmark'}/>{wl.label}</Button>
    </span>
    {picking && <SavePanel target={target} title={title} onClose={() => setPicking(false)}/>}
  </>
}
