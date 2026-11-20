import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../../../api/queries'
import { CommandLine } from '../../../components/CommandLine'
import { FolderPicker } from '../../../components/FolderPicker'
import type { useFolderCheck } from './useFolderCheck'
import { Button, Field, Message, fieldInput } from '../../../components/settings/Frame'
import { folderResult, videoCount, type FolderResult } from '../../../lib/librariesSettings'
import { packageFolderAccess } from '../../../lib/packageInstall'
import { libraryPathExample } from '../../../lib/transcoder'
import type { LibraryType } from '../../../lib/setupRules'
import type { components } from '../../../api/schema'

type PathCheck = components['schemas']['PathCheck']

/**
 * The server folder field with Browse… and its check.
 * The check runs when a folder is picked, when the field is
 * left, and from Check again; one result shows at a time.
 */
export function FolderField({ type, path, onPath, folder, label = 'Server folder', error, children }: {
  type: LibraryType
  path: string
  onPath: (path: string) => void
  folder: ReturnType<typeof useFolderCheck>
  label?: string
  error?: ReactNode
  children?: ReactNode
}) {
  const platform = useQuery(serverQuery).data?.platform
  const [picking, setPicking] = useState(false)
  const change = (p: string) => {
    folder.clear()
    onPath(p)
  }
  return (
    <>
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-0 flex-[1_1_16rem]">
          <Field label={label} error={error}>
            {(props) => (
              <input
                {...props}
                value={path}
                onChange={(e) => change(e.target.value)}
                onBlur={() => { if (path.trim() && !folder.check && !folder.checking) void folder.run(path) }}
                placeholder={libraryPathExample(type, platform)}
                autoComplete="off"
                spellCheck={false}
                className={`${fieldInput} font-mono`}
              />
            )}
          </Field>
        </div>
        <Button onClick={() => setPicking(true)}>Browse…</Button>
      </div>
      <div className="mt-4 space-y-3" aria-live="polite">
        {folder.checking && <p className="text-sm text-neutral-400">Checking {path.trim()}…</p>}
        {folder.failed && (
          <Message tone="err" title="Couldn’t check this folder" actions={<Button small onClick={() => void folder.run(path)}>Check again</Button>}>
            Check your connection to the server, then check again.
          </Message>
        )}
        {children}
      </div>
      {picking && (
        <FolderPicker
          initial={path.trim() || undefined}
          onPick={(p) => {
            setPicking(false)
            change(p)
            void folder.run(p)
          }}
          onClose={() => setPicking(false)}
        />
      )}
    </>
  )
}

/**
 * What the check found, worded as the boards draw it. `onUseParent` offers
 * the folder above one show's folder; `onChooseTV` goes back to Type.
 * `install` is ServerInfo.install: a package install's server runs as its
 * own user, so a folder it can't read gets that user's name and a command.
 */
export function FolderResultMessage({ type, check, platform, install, onRecheck, onUseParent, onChooseTV, editing }: {
  type: LibraryType
  check: PathCheck
  platform?: string
  install?: string
  onRecheck: () => void
  onUseParent?: (parent: string) => void
  onChooseTV?: () => void
  /** On Name and folder, where the type is set. */
  editing?: boolean
}) {
  const r: FolderResult = folderResult(type, check)
  const path = check.path
  if (r.kind === 'missing') {
    return (
      <Message tone="err" title={`${path} wasn’t found on the server`}>
        Check the spelling, or use Browse to pick it. In Docker, the folder has to be mounted into the container first.
      </Message>
    )
  }
  if (r.kind === 'unreadable') {
    const access = packageFolderAccess(install, path)
    return (
      <Message tone="err" title={`The server isn’t allowed to read ${path}`} actions={<Button small onClick={onRecheck}>Check again</Button>}>
        {access ? (
          <>
            {access.text}
            <CommandLine>{access.command}</CommandLine>
            <span className="mt-1.5 block">{access.after}</span>
          </>
        ) : platform === 'windows'
          ? 'Give the Windows account the server runs as access to the folder, and to the network share if it is one. Then check again.'
          : 'In Docker, set PUID and PGID to the user that owns the folder. On Windows, give the account the server runs as access to the folder. Then check again.'}
      </Message>
    )
  }
  if (r.kind === 'empty') {
    return (
      <Message tone="warn" title={`No video files in ${path}`}>
        You can still {editing ? 'use it' : 'add it'}, and files put there later will be found. If that’s a surprise, check it’s the right folder.
      </Message>
    )
  }
  if (r.kind === 'oneShow') {
    return (
      <Message
        tone="warn"
        title="This looks like one show, not a folder of shows"
        actions={r.parent && onUseParent && <Button small onClick={() => onUseParent(r.parent!)}>Use {r.parent}</Button>}
      >
        {r.parent ? `Choose the folder above it, ${r.parent}, so each show is found.` : 'Choose the folder that holds all your shows, so each show is found.'}
      </Message>
    )
  }
  if (r.kind === 'looksTV') {
    return (
      <Message tone="warn" title="This looks like a TV folder">
        Its folders are named like seasons.{' '}
        {onChooseTV ? (
          <>
            <button type="button" onClick={onChooseTV} className="underline">Go back and choose TV Shows</button>, or carry on if these really are movies.
          </>
        ) : (
          'Choose your movies folder, or add this one as a TV Shows library.'
        )}
      </Message>
    )
  }
  return (
    <Message tone="ok" title={`Found ${videoCount(r.count, r.truncated)}`}>
      {editing ? 'The folder can be read.' : `The folder can be read and looks like a ${type === 'tv' ? 'TV Shows' : 'Movies'} folder.`}
    </Message>
  )
}
