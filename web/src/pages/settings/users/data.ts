import { useQuery } from '@tanstack/react-query'
import { usersQuery } from '../../../api/queries'
import { activityUsersQuery } from '../../../api/activity'
import { integrationsQuery, seerrUsersQuery } from '../../../api/integrations'
import { plexConnectionsQuery, plexPeopleQuery } from '../../../api/plex'

/**
 * What the Users pages read: everyone, how many devices each has played on
 * (from Activity, which may be off), and with Seerr and Plex connected, who
 * each person requests as and who they are in Plex.
 */
export function usePeople() {
  const users = useQuery(usersQuery)
  const activity = useQuery({ ...activityUsersQuery, retry: false })
  const integrations = useQuery(integrationsQuery)
  const hasSeerr = integrations.data?.items.some((c) => c.kind === 'seerr') ?? false
  const seerrUsers = useQuery({ ...seerrUsersQuery, enabled: hasSeerr })
  const plexConnections = useQuery(plexConnectionsQuery)
  const plex = plexConnections.data?.items[0]
  const plexPeople = useQuery({ ...plexPeopleQuery(plex?.id ?? 0, plex?.generation ?? 0), enabled: !!plex && plex.status === 'connected' })
  const devices = (id: number) => activity.data?.items.find((a) => a.user.id === id)?.devices
  const plexPerson = (id: number) => plexPeople.data?.users.find((p) => p.linkedUserId === id)
  return { users, hasSeerr, seerrUsers, plex, plexPeople, devices, plexPerson }
}
