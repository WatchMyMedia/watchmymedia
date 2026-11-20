import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { LibrariesHub } from './libraries/Hub'
import { AddLibraryFlow } from './libraries/AddFlow'
import { LibraryPage } from './libraries/LibraryPage'
import { EditLibraryFlow } from './libraries/EditFlow'
import { ReviewUnmatched } from './libraries/ReviewQueue'

/**
 * Settings › Libraries: the hub, one page per
 * library, and the tasks under them — Add a library, Name and folder, and
 * Review unmatched. Flows draw their own kept-out and member screens.
 */
export function LibrariesSettings() {
  const page = useSettingsPage()
  const id = Number(page?.params.id)
  if (page?.task === 'add') return <AddLibraryFlow step={page.params.step} />
  if (page?.task === 'edit') return <EditLibraryFlow id={id} />
  if (page?.task === 'unmatched') return <ReviewUnmatched id={id} step={page.params.step} />
  if (page?.task === 'library') return <ServerOnly tab="libraries"><LibraryPage id={id} /></ServerOnly>
  return <ServerOnly tab="libraries"><LibrariesHub /></ServerOnly>
}
