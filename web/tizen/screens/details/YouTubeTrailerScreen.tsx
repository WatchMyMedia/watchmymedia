import { useBack, useNav } from '../../app/hooks'
import type { PlayRequest } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/**
 * A YouTube trailer inside the app (TrailerPlayer on the canvas), until the
 * details feature draws it. The player route hands it trailer requests that
 * carry a YouTube key; trailer files play in the player.
 */
export function YouTubeTrailerScreen({ play }: { play: Extract<PlayRequest, { kind: 'trailer' }> }) {
  const nav = useNav()
  useBack(() => { nav.back(); return true })
  return <Placeholder full kicker="Trailer · placeholder" title={play.title ?? 'A trailer'} />
}
