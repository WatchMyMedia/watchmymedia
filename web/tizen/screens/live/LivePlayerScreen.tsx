import { useBack, useNav } from '../../app/hooks'
import type { PlayRequest } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/**
 * The Live TV player (LivePlayer on the canvas), until the Live TV feature
 * draws it over the engine's LivePlayback. The player route hands it live
 * requests.
 */
export function LivePlayerScreen({ play }: { play: Extract<PlayRequest, { kind: 'live' }> }) {
  const nav = useNav()
  useBack(() => { nav.back(); return true })
  return <Placeholder full kicker="Live TV player · placeholder" title={`Channel ${play.channelId}`} />
}
