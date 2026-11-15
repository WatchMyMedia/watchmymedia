import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, serverQuery } from '../../../api/queries'
import { useUpdateLibrary } from '../../../api/settings'
import { FlowFrame } from '../../../components/settings/Flow'
import { Field, LoadingRows, Message, fieldInput } from '../../../components/settings/Frame'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { useGo } from '../../../components/settings/hooks'
import { libraryRefusal, type Refusal } from '../../../lib/librariesSettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { FolderField, FolderResultMessage } from './FolderField'
import { useFolderCheck } from './useFolderCheck'
import { leaveFlash } from './flash'
import { RefusalMessage } from './parts'
import type { components } from '../../../api/schema'

type Library = components['schemas']['Library']

/** Name and folder (board lib-edit): a one-step task with Cancel and Save.
 * Only a folder change asks for the password away from home. */
export function EditLibraryFlow({ id }: { id: number }) {
  const libraries = useQuery(librariesQuery)
  const lib = libraries.data?.items.find((l) => l.id === id)
  if (!lib) {
    return (
      <FlowFrame
        tab="libraries"
        crumbs={settingsCrumbs('libraries', { label: 'Name and folder' })}
        title="Name and folder"
        back={{ label: 'Cancel', href: settingsHref('libraries') }}
      >
        {libraries.isPending ? <LoadingRows rows={2} /> : (
          <Message tone="info" title="This library isn’t here">It may have been removed. The other libraries are on the Libraries page.</Message>
        )}
      </FlowFrame>
    )
  }
  return <EditForm lib={lib} />
}

function EditForm({ lib }: { lib: Library }) {
  const go = useGo()
  const platform = useQuery(serverQuery).data?.platform
  const update = useUpdateLibrary()
  const page = settingsHref('libraries', lib.id)
  const [name, setName] = useState(lib.name)
  const [path, setPath] = useState(lib.path)
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  // Saved: the leave check is off, then the page opens with its banner.
  const [saved, setSaved] = useState(false)
  useEffect(() => { if (saved) go(page, { replace: true }) }, [go, page, saved])
  const folder = useFolderCheck()
  const folderChanged = path.trim() !== lib.path
  const dirty = name !== lib.name || folderChanged
  const save = () => {
    setRefusal(null)
    const body = { id: lib.id, name: name.trim() || lib.name, path: path.trim() }
    update.mutate(body, {
      onSuccess: (updated) => {
        leaveFlash(page, folderChanged
          ? { tone: 'ok', title: 'Saved', text: `${updated.name} now reads ${updated.path}, and a scan has started. Files that aren’t there show as missing.` }
          : { tone: 'ok', title: 'Saved', text: `The library is called ${updated.name} in the top bar and on Home.` })
        setSaved(true)
      },
      onError: (e) => setRefusal(libraryRefusal(e, body.path, 'save')),
    })
  }
  return (
    <FlowFrame
      tab="libraries"
      crumbs={settingsCrumbs('libraries', { label: lib.name, href: page }, { label: 'Name and folder' })}
      title="Name and folder"
      back={{ label: 'Cancel', href: page }}
      next={{ label: 'Save', onClick: save, guarded: folderChanged, busy: update.isPending, busyLabel: 'Saving…', disabled: !dirty || !path.trim(), reason: !path.trim() ? 'Type a folder, or use Browse to pick one.' : 'Nothing has changed yet.' }}
    >
      <LeaveCheck when={dirty && !saved} what="Your changes to the name and folder aren’t saved." />
      <Field label="Name" hint="Shown in the top bar and on Home.">
        {(props) => <input {...props} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" className={fieldInput} />}
      </Field>
      <div className="mt-4">
        <FolderField type={lib.type} path={path} onPath={setPath} folder={folder}>
          {folder.check && !folder.checking && folderChanged && (
            <FolderResultMessage type={lib.type} check={folder.check} platform={platform} editing onRecheck={() => void folder.run(path)} onUseParent={(p) => { setPath(p); void folder.run(p) }} />
          )}
          {folderChanged && (
            <Message tone="warn" title="Changing the folder rescans the library">
              Files found again at the new folder are matched again, and what people watched stays with the {lib.type === 'tv' ? 'shows' : 'movies'}. Files that aren’t there show as missing.
            </Message>
          )}
        </FolderField>
      </div>
      {refusal && <div className="mt-5"><RefusalMessage refusal={refusal} focus /></div>}
    </FlowFrame>
  )
}
