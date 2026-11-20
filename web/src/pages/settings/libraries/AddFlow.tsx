import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthQuery, librariesQuery, serverQuery } from '../../../api/queries'
import { useCreateLibrary } from '../../../api/settings'
import { useEventState } from '../../../api/events'
import { FlowFrame, type FooterAction } from '../../../components/settings/Flow'
import { Card, Choice, Field, HrefLink, Message, Outcome, Spinner, fieldInput } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { previousStep } from '../../../lib/flowSteps'
import { ADD_STEPS, addBody, chooseType, addFlowStep, emptyAddDraft, folderReady, libraryRefusal, videoCount, type AddDraft, type Refusal } from '../../../lib/librariesSettings'
import { libraryLabel, type LibraryType } from '../../../lib/setupRules'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { FolderField, FolderResultMessage } from './FolderField'
import { useFolderCheck } from './useFolderCheck'
import { MatchingOff, RefusalMessage } from './parts'

const KEY = 'wmm.addLibrary'

function readDraft(): AddDraft {
  try {
    const saved = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Partial<AddDraft> | null
    if (saved && typeof saved === 'object') return { ...emptyAddDraft(), ...saved }
  } catch {
    // Private windows and blocked storage: start afresh.
  }
  return emptyAddDraft()
}

function writeDraft(draft: AddDraft) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(draft))
  } catch {
    // Without storage the draft lasts until the page reloads.
  }
}

const href = (step: string) => settingsHref('libraries', 'add', step)

/**
 * Add a library: Type → Folder → Name
 * and add → Added. Only Name and add saves, and away from home it asks for
 * the password. What was chosen lasts for this browser tab, so a reload
 * stays on its step.
 */
export function AddLibraryFlow({ step: requested }: { step?: string }) {
  const go = useGo()
  // What was chosen lasts for this browser tab: Exit and a reload keep it,
  // and Add a library carries on from it. Nothing is added before Name and add.
  const [draft, setDraft] = useState<AddDraft>(readDraft)
  useEffect(() => writeDraft(draft), [draft])
  const { step, reset } = addFlowStep(draft, requested)
  // An added library can't be reopened: opening the flow again starts afresh.
  if (reset) setDraft(emptyAddDraft())
  useEffect(() => {
    if (step.key !== requested) go(href(step.key), { replace: true })
  }, [go, requested, step.key])

  const crumbs = settingsCrumbs('libraries', { label: 'Add a library' })
  const back = previousStep(ADD_STEPS, step.key)
  const common = { tab: 'libraries' as const, crumbs, steps: ADD_STEPS, current: step.key, stepHref: href }
  const backAction: FooterAction | undefined = back ? { label: back.label, href: href(back.key) } : undefined

  if (step.key === 'type') return <TypeStep common={common} draft={draft} setDraft={setDraft} />
  if (step.key === 'folder') return <FolderStep common={common} back={backAction} draft={draft} setDraft={setDraft} />
  if (step.key === 'name') return <NameStep common={common} back={backAction} draft={draft} setDraft={setDraft} />
  return <AddedStep common={common} draft={draft} onAnother={() => { setDraft(emptyAddDraft()); go(href('type')) }} />
}

type Common = Pick<Parameters<typeof FlowFrame>[0], 'tab' | 'crumbs' | 'steps' | 'current' | 'stepHref'>
type SetDraft = (next: AddDraft | ((prev: AddDraft) => AddDraft)) => void
type StepProps = { common: Common; back?: FooterAction; draft: AddDraft; setDraft: SetDraft }

function TypeStep({ common, draft, setDraft }: StepProps) {
  const go = useGo()
  const pick = (type: LibraryType) => setDraft((d) => chooseType(d, type))
  return (
    <FlowFrame
      {...common}
      title="What’s in the folder?"
      lede="This decides how files are read and matched. It can’t be changed after the library is added."
      next={{ label: 'Continue to folder', onClick: () => go(href('folder')), disabled: !draft.type, reason: 'Choose Movies or TV Shows.' }}
    >
      <fieldset>
        <legend className="sr-only">Library type</legend>
        <Choice name="library-type" value="movie" checked={draft.type === 'movie'} onChange={() => pick('movie')} title="Movies">
          One movie per file or folder, like <code className="break-all">The Long Field (2019)/The Long Field (2019).mkv</code>.
        </Choice>
        <Choice name="library-type" value="tv" checked={draft.type === 'tv'} onChange={() => pick('tv')} title="TV Shows">
          A folder per show with seasons inside, like <code className="break-all">Harbor Lights/Season 02/Harbor Lights S02E03.mkv</code>.
        </Choice>
      </fieldset>
    </FlowFrame>
  )
}

function FolderStep({ common, back, draft, setDraft }: StepProps) {
  const go = useGo()
  const type = draft.type ?? 'movie'
  const server = useQuery(serverQuery).data
  const platform = server?.platform
  // The check is kept for the folder as typed: the server may write it its own way.
  const folder = useFolderCheck(draft.check, (check) => setDraft((d) => ({ ...d, check: check && { ...check, path: d.path.trim() } })))
  const ready = folderReady(draft)
  // Typing clears the check, so the one kept is for the folder typed now.
  const current = draft.check
  const setPath = (path: string) => setDraft((d) => ({ ...d, path, check: null }))

  const next: FooterAction = {
    label: 'Continue',
    busy: folder.checking,
    busyLabel: 'Checking…',
    disabled: !draft.path.trim(),
    reason: 'Type a folder, or use Browse to pick one.',
    onClick: async () => {
      if (ready) return go(href('name'))
      const result = await folder.run(draft.path)
      if (result && result.exists && result.readable) go(href('name'))
    },
  }
  return (
    <FlowFrame
      {...common}
      title={type === 'tv' ? 'Where are the shows?' : 'Where are the movies?'}
      lede="A folder on the server, as the server sees it. In Docker that’s the path inside the container."
      back={back}
      next={next}
      help={
        <>
          <h4>Which folder?</h4>
          <p>The one that holds all your {type === 'tv' ? 'shows' : 'movies'}, not one {type === 'tv' ? 'show' : 'movie'}’s folder.</p>
          <h4>Running in Docker?</h4>
          <p>Use the path inside the container, such as {type === 'tv' ? '/media/tv' : '/media/movies'}, not the path on the host.</p>
          <h4>On Windows?</h4>
          <p>Network drives need the full \\server\share path. Mapped letters aren’t seen by the server.</p>
        </>
      }
    >
      <FolderField type={type} path={draft.path} onPath={setPath} folder={folder}>
        {current && !folder.checking && (
          <FolderResultMessage
            type={type}
            check={current}
            platform={platform}
            install={server?.install}
            onRecheck={() => void folder.run(draft.path)}
            onUseParent={(parent) => { setPath(parent); void folder.run(parent) }}
            onChooseTV={() => { setDraft((d) => chooseType(d, 'tv')); go(href('type')) }}
          />
        )}
      </FolderField>
    </FlowFrame>
  )
}

function NameStep({ common, back, draft, setDraft }: StepProps) {
  const go = useGo()
  const create = useCreateLibrary()
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const type = draft.type ?? 'movie'
  const body = addBody(draft)
  const add = () => {
    setRefusal(null)
    create.mutate(body, {
      onSuccess: (lib) => {
        setDraft((d) => ({ ...d, added: { id: lib.id, name: lib.name, path: lib.path } }))
        go(href('added'))
      },
      onError: (error) => setRefusal(libraryRefusal(error, body.path, 'add')),
    })
  }
  const count = draft.check ? videoCount(draft.check.videoFiles, draft.check.truncated) : ''
  return (
    <FlowFrame
      {...common}
      title="Name it and add it"
      back={back}
      next={{ label: 'Add library', onClick: add, guarded: true, busy: create.isPending, busyLabel: 'Adding…' }}
    >
      <Field label="Name" hint="Shown in the top bar and on Home.">
        {(props) => (
          <input {...props} value={draft.name} placeholder={libraryLabel(type)} onChange={(e) => { const name = e.target.value; setDraft((d) => ({ ...d, name })) }} autoComplete="off" className={fieldInput} />
        )}
      </Field>
      <Card className="mt-6">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3.5">
          <div className="min-w-0 flex-1">
            <h4 className="font-medium">Type</h4>
            <p className="text-sm text-neutral-400">{libraryLabel(type)} · can’t be changed later</p>
          </div>
          <HrefLink href={href('type')} className="text-sm text-neutral-300 underline hover:text-neutral-100">Change<span className="sr-only"> the type</span></HrefLink>
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3.5">
          <div className="min-w-0 flex-1">
            <h4 className="font-medium">Folder</h4>
            <p className="font-mono text-sm break-all text-neutral-400">{body.path}{count && <span className="font-sans"> · {count}</span>}</p>
          </div>
          <HrefLink href={href('folder')} className="text-sm text-neutral-300 underline hover:text-neutral-100">Change<span className="sr-only"> the folder</span></HrefLink>
        </div>
      </Card>
      {refusal && (
        <div className="mt-5">
          <RefusalMessage refusal={refusal} focus actions={refusal.fix === 'folder' && <HrefLink href={href('folder')} className="text-sm font-medium text-neutral-100 underline">Go back to Folder</HrefLink>} />
        </div>
      )}
    </FlowFrame>
  )
}

/** Added: follows the scan and the matching from the live events, because
 * that's when the next task (the unmatched review) appears. */
function AddedStep({ common, draft, onAnother }: { common: Common; draft: AddDraft; onAnother: () => void }) {
  const added = draft.added!
  const type = draft.type ?? 'movie'
  const live = useEventState()
  const health = useQuery(healthQuery)
  const libraries = useQuery(librariesQuery)
  const lib = libraries.data?.items.find((l) => l.id === added.id)
  const tmdb = health.data?.tmdbConfigured ?? true
  const scan = live.scans[added.id] ?? lib?.scan
  const match = live.matches[added.id] ?? lib?.match
  const lastScan = live.lastScan[added.id]
  const lastMatch = live.lastMatch[added.id]
  const what = type === 'tv' ? 'shows' : 'movies'
  const scanned = !!lastScan?.finished || (!!lib?.lastScannedAt && !scan)
  const matchingDone = !match && scanned && (!tmdb || !!lastMatch || (lib ? lib.pendingCount === 0 : false))
  const unmatched = lib?.unmatchedCount ?? 0

  let progress
  if (lastScan?.failed) {
    progress = (
      <Message tone="err" title={`Couldn’t scan ${added.name}`} actions={<HrefLink href={settingsHref('libraries', added.id)} className="text-sm font-medium text-neutral-100 underline">Open {added.name}</HrefLink>}>
        {added.path} can’t be read right now. Check the drive is connected, then scan again from the library’s page.
      </Message>
    )
  } else if (scan) {
    const pct = scan.total ? Math.round((scan.scanned / scan.total) * 100) : 0
    progress = <ProgressCard title={scan.total ? `Reading files · ${scan.scanned.toLocaleString()} of ${scan.total.toLocaleString()}` : 'Collecting files…'} pct={pct} note={`${tmdb ? 'Matching to TMDB follows. ' : ''}You can leave. It carries on, and ${what} appear as they’re read.`} />
  } else if (match) {
    const pct = match.total ? Math.round((match.done / match.total) * 100) : 0
    progress = <ProgressCard title={`Matching to TMDB · ${match.done.toLocaleString()} of ${match.total.toLocaleString()}`} pct={pct} note={`You can leave. It carries on, and ${what} get their posters and details as they’re matched.`} />
  } else if (matchingDone) {
    progress = (
      <Message tone={unmatched > 0 ? 'warn' : 'ok'} title={unmatched > 0 ? `Scanned · ${unmatched.toLocaleString()} file${unmatched === 1 ? '' : 's'} couldn’t be matched` : `Scanned · ${(lib?.fileCount ?? 0).toLocaleString()} files`}>
        {!tmdb ? 'Files play now, without posters or details.' : unmatched > 0 ? 'They play, but have no poster or details until you say what they are.' : `Every file is matched, and the ${what} are on Home.`}
      </Message>
    )
  } else {
    progress = <ProgressCard title="Starting the scan…" pct={0} note={`You can leave. It carries on, and ${what} appear as they’re read.`} />
  }

  const review = matchingDone && unmatched > 0
  return (
    <FlowFrame
      {...common}
      finished="ok"
      title={`${added.name} is added`}
      lede={scan || !scanned ? `Scanning ${added.path} now.` : `${added.path} is scanned.`}
      skip={{ label: 'Add another library', onClick: onAnother }}
      next={review
        ? { label: `Review ${unmatched.toLocaleString()} unmatched`, href: settingsHref('libraries', added.id, 'unmatched') }
        : { label: 'Back to Libraries', href: settingsHref('libraries') }}
    >
      <div className="space-y-5">
        {progress}
        {!tmdb && <MatchingOff />}
        <Outcome
          changed={[
            `${added.name} is in the top bar and on Home for everyone.`,
            'The folder is watched: new files show up within a minute.',
          ]}
          next={[
            tmdb ? 'When matching ends, files that couldn’t be matched wait for you under Review unmatched.' : 'Once a TMDB key is added, files are matched and any that can’t be wait for you under Review unmatched.',
            <>To let people request {what}, <HrefLink href={settingsHref('integrations', 'add', type === 'tv' ? 'sonarr' : 'radarr')}>add {type === 'tv' ? 'Sonarr' : 'Radarr'}</HrefLink>.</>,
          ]}
        />
      </div>
    </FlowFrame>
  )
}

function ProgressCard({ title, pct, note }: { title: string; pct: number; note: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5" role="status">
      <h3 className="flex items-center gap-2 font-semibold"><Spinner className="text-accent" />{title}</h3>
      <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-line" aria-hidden="true">
        <div className="h-full rounded-full bg-accent transition-[width] motion-reduce:transition-none" style={{ width: `${pct}%` }} />
      </div>
      <p className="mt-2 text-sm text-neutral-400">{note}</p>
    </div>
  )
}
