import type { Route } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/** See all and list pages, until the browsing feature replaces them (ListPage on the Samsung TV canvas). */
export function ListScreen({ route }: { route: Extract<Route, { name: 'seeAll' } | { name: 'list' }> }) {
  return <Placeholder kicker="See all and lists · placeholder" title={route.name === 'list' ? `List ${route.id}` : route.title}>
    A row’s titles sorted its way, or a list’s entries in rank order. The browsing feature draws it with the kit’s Grid.
  </Placeholder>
}
