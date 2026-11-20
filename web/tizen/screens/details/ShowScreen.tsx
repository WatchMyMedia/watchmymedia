import type { ScreenProps } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/** A show's page, until the details feature replaces it (ShowPage on the Samsung TV canvas). */
export function ShowScreen({ route }: ScreenProps<'show'>) {
  return <Placeholder kicker="Show · placeholder" title={`Show ${route.id}${route.season ? ` · season ${route.season}` : ''}`}>
    The episode on top, season tabs above the episodes row, Follow, missing episodes with Request, and More like this.
  </Placeholder>
}
