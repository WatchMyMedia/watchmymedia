import type { ScreenProps } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/** A movie's page, until the details feature replaces it (Movie on the Samsung TV canvas). */
export function MovieScreen({ route }: ScreenProps<'movie'>) {
  return <Placeholder kicker="Movie · placeholder" title={`Movie ${route.id}`}>
    Resume or Play, Play from start, Trailer, Mark watched, versions, audio and subtitles, cast, the collection and More like this.
  </Placeholder>
}
