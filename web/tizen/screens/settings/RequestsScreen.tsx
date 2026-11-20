import type { ScreenProps } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/** Your requests and the admins' Inbox, until the requests feature replaces them (Requests on the Samsung TV canvas). */
export function RequestsScreen({ route }: ScreenProps<'requests'>) {
  return <Placeholder kicker="Requests · placeholder" title={route.inbox ? 'Inbox' : 'Your requests'}>
    Requests with Cancel request; for admins, the Inbox of requests and problems to decide.
  </Placeholder>
}
