import { useSettingsPage } from '../../components/settings/hooks'
import { personId } from '../../lib/usersSettings'
import { ServerOnly } from './SettingsLayout'
import { AddPersonFlow } from './users/AddFlow'
import { UsersHub } from './users/Hub'
import { PersonPage } from './users/Person'

/**
 * Settings › Users: the hub with everyone and
 * what everyone can do, a page for each person, and Add a person as a flow.
 */
export function UsersSettings() {
  const page = useSettingsPage()
  if (page?.kind === 'flow') return <AddPersonFlow step={page.params.step} />
  return (
    <ServerOnly tab="users">
      {page?.kind === 'item' ? <PersonPage key={page.params.id} id={personId(page.params.id)} /> : <UsersHub />}
    </ServerOnly>
  )
}
