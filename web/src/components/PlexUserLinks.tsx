import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { plexConnectionsQuery, plexPeopleQuery } from '../api/plex'
import { usersQuery } from '../api/queries'
import { plexKindLabel, plexPersonStatus } from '../lib/plex'
import { Panel } from './SettingsPanel'

/** Immutable account IDs link Plex watched choices; Seerr request links stay independent. */
export function PlexUserLinks() {
  const connections = useQuery(plexConnectionsQuery)
  const connection = connections.data?.items[0]
  const people = useQuery({ ...plexPeopleQuery(connection?.id ?? 0,connection?.generation??0), enabled: !!connection && connection.status === 'connected' })
  const users = useQuery(usersQuery)
  if (!connection && !connections.isError) return null
  const mapped = people.data?.users.filter((person) => !!person.linkedUserId) ?? []
  return (
    <Panel
      title="Watched choices from Plex"
      description="These links bring watched movies and episodes across. They do not change anyone’s Seerr request account."
      actions={
        <Link to="/settings/plex" className="min-h-[40px] py-2 text-sm text-neutral-200 underline">
          Choose people in Plex settings →
        </Link>
      }
    >
      <div className="px-5 pb-5">
        {connections.isError || people.isError ? (
          <p className="text-sm text-orange-300">Couldn’t read the Plex links. Open Plex settings to check the connection and try again.</p>
        ) : connection?.status !== 'connected' ? (
          <p className="text-sm text-neutral-400">Plex needs to be reconnected before its people can be checked. Existing imported choices stay.</p>
        ) : people.isPending ? (
          <p className="text-sm text-neutral-400">Reading Plex links…</p>
        ) : !mapped.length ? (
          <p className="text-sm text-neutral-400">No one is linked yet. Choose a Plex person and their WatchMyMedia account in Plex settings.</p>
        ) : (
          <ul className="divide-y divide-line">
            {mapped.map((person) => (
              <li key={person.plexUserId} className="flex flex-wrap justify-between gap-2 py-3 text-sm">
                <span className="min-w-0 break-words font-medium">
                  {users.data?.items.find((user) => user.id === person.linkedUserId)?.name ?? 'WatchMyMedia account'}
                </span>
                <span className="min-w-0 break-words text-neutral-400">
                  {person.title} · {plexKindLabel(person.kind)} · {plexPersonStatus(person.status)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Panel>
  )
}
