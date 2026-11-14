import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  plexLibrariesQuery,
  plexPeopleQuery,
  plexPreviewQuery,
  plexRunsQuery,
  useSavePlexMappings,
  type PlexConnection,
  type PlexPerson,
  type PlexSection,
} from '../../../api/plex'
import { librariesQuery, usersQuery } from '../../../api/queries'
import { FlowFrame } from '../../../components/settings/Flow'
import { Button, Card, fieldInput, HrefLink, ItemRow, LoadingRows, Message, Pill } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { settingsHref } from '../../../lib/settingsRoutes'
import { plexLinks, plexMappingError, plexMappingsChanged, plexRunActive, type PlexMappingDraft } from '../../../lib/plex'
import {
  ago,
  count,
  LINK_STEPS,
  linkSaveMessage,
  linkStep,
  linkSummary,
  nameList,
  plexLinkDraft,
  plexPeopleConflict,
  plexPersonKind,
  plexPersonLeftOut,
  plexPersonReady,
  reconnectBanner,
  type PlexMessage,
} from '../../../lib/plexSettings'
import { FlowWaiting, ListCard, PlexNote, RowTable } from './parts'
import { keptImport, linkKey, plexCrumbs, plexHref, setAsideCheck, useClock, usePlexConnection, useStored } from './state'

/** Link people and libraries: People → Libraries → Review → Saved (§5.1).
 * Only Review saves; the choices before it are kept in this tab. */
export function LinkFlow({ step }: { step?: string }) {
  const { query, connection } = usePlexConnection()
  const crumbs = plexCrumbs('link')
  if (query.isPending || query.isError)
    return <FlowWaiting crumbs={crumbs} steps={LINK_STEPS} title="Who is who?" error={query.isError} retry={() => void query.refetch()} />
  if (!connection) return <FlowWaiting crumbs={crumbs} steps={LINK_STEPS} title="Who is who?" missing />
  return <Link key={connection.id} connection={connection} step={step} />
}

type Kept = { draft?: PlexMappingDraft; people: boolean; libraries: boolean }
type Saved = { accounts: string[]; libraries: string[]; sections: string[]; leftOut: string[]; firstTime: boolean }

type LocalUser = { id: number; name: string }
type LocalLibrary = { id: number; name: string; type: 'movie' | 'tv' }

function Link({ connection, step }: { connection: PlexConnection; step?: string }) {
  const go = useGo()
  const qc = useQueryClient()
  const id = connection.id
  const crumbs = plexCrumbs('link')
  const people = useQuery(plexPeopleQuery(id, connection.generation))
  const sections = useQuery(plexLibrariesQuery(id, connection.generation))
  const users = useQuery(usersQuery)
  const libraries = useQuery(librariesQuery)
  const runs = useQuery(plexRunsQuery(id))
  // Opening the task starts from today's links; a reload or a step's own
  // address picks up the choices made so far.
  const [kept, setKept] = useStored<Kept>(linkKey(id, connection.generation), { people: false, libraries: false }, !step)
  const [saved, setSaved] = useState<Saved | null>(null)
  const [refusal, setRefusal] = useState<PlexMessage | null>(null)
  const save = useSavePlexMappings(id)
  const current = linkStep(step, { people: kept.people, libraries: kept.libraries, saved: !!saved })
  useEffect(() => {
    if (current !== step) go(plexHref('link', current), { replace: true })
  }, [current, step, go])

  const loaded = !!people.data && !!sections.data && !!users.data && !!libraries.data
  const sameGeneration = people.data?.generation === connection.generation && sections.data?.generation === connection.generation
  const initial = useMemo(() => (people.data && sections.data ? plexLinkDraft(people.data.users, sections.data.libraries) : undefined), [people.data, sections.data])
  const draft = kept.draft ?? initial
  const setDraft = (d: PlexMappingDraft) => {
    setKept({ ...kept, draft: d })
    setRefusal(null)
  }
  const refresh = () => {
    setRefusal(null)
    save.reset()
    void qc.invalidateQueries({ queryKey: ['plex', 'connections'] })
    void people.refetch()
    void sections.refetch()
    void users.refetch()
    void libraries.refetch()
  }
  const importing = !!runs.data?.active || (runs.data?.items.some((r) => plexRunActive(r.status)) ?? false)

  const userList: LocalUser[] = users.data?.items ?? []
  const libraryList: LocalLibrary[] = libraries.data?.items ?? []
  const personList: PlexPerson[] = people.data?.users ?? []
  const sectionList: PlexSection[] = sections.data?.libraries ?? []

  if (saved)
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={LINK_STEPS}
        current="saved"
        finished="ok"
        title={saved.accounts.length && saved.libraries.length ? 'People and libraries are linked' : 'Links saved'}
        lede={`${count(saved.accounts.length, 'person', 'people')} and ${count(saved.libraries.length, 'library', 'libraries')}. ${saved.firstTime ? 'Nothing has been imported yet.' : 'Nothing new has been imported.'}`}
        skip={saved.accounts.length && saved.libraries.length ? { label: 'Back to Plex', href: plexHref() } : undefined}
        next={saved.accounts.length && saved.libraries.length ? { label: 'Check what will change →', href: plexHref('import') } : { label: 'Back to Plex', href: plexHref() }}
      >
        <div className="grid gap-4 md:grid-cols-2">
          <ListCard
            title="What changed"
            items={[
              saved.accounts.length ? `${nameList(saved.accounts)} ${saved.accounts.length === 1 ? 'is linked to their Plex person' : 'are linked to their Plex people'}.` : 'Nobody is linked, so nothing is read.',
              saved.libraries.length ? `${nameList(saved.libraries)} ${saved.libraries.length === 1 ? 'is' : 'are'} linked to ${nameList(saved.sections)}.` : 'No library is linked, so nothing is read.',
              saved.leftOut.length > 0 && `${nameList(saved.leftOut)} ${saved.leftOut.length === 1 ? 'is' : 'are'} left out and won’t be read.`,
            ]}
          />
          <ListCard
            title="What happens next"
            items={saved.accounts.length && saved.libraries.length ? [
              'Check what an import would change. It reads Plex and writes nothing.',
              'Then import once, or import and keep up to date.',
            ] : ['Link at least one person and one library before checking what an import would change.']}
          />
        </div>
      </FlowFrame>
    )

  // Every step needs the people and libraries read from Plex and from here.
  let blocker: ReactNode = null
  if (connection.status !== 'connected') blocker = <PlexNote message={reconnectBanner(connection.name)} />
  else if (people.isError || sections.isError || users.isError || libraries.isError)
    blocker = (
      <Message tone="err" title={`Couldn’t read ${connection.name}’s people and libraries`} actions={<Button small onClick={refresh}>Refresh people and libraries</Button>}>
        Check Plex is running, then refresh. Nothing was changed.
      </Message>
    )
  else if (!loaded || !draft) blocker = <LoadingRows rows={4} />
  else if (!sameGeneration)
    blocker = (
      <Message tone="warn" title="The connection changed while this page was open" actions={<Button small onClick={refresh}>Refresh people and libraries</Button>}>
        Refresh people and libraries, then look over your choices.
      </Message>
    )

  const ready = !blocker && !!draft

  if (current === 'people') {
    const conflict = draft ? plexPeopleConflict(draft, personList, userList) : null
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={LINK_STEPS}
        current="people"
        stepHref={(k) => plexHref('link', k)}
        title="Who is who?"
        lede="Choose the WatchMyMedia account for each Plex person you want to bring across. Anyone left out isn’t read. We never match people by name for you."
        next={{
          label: 'Continue to libraries',
          disabled: !ready,
          onClick: () => {
            if (conflict) return setRefusal({ tone: 'err', ...conflict })
            setKept({ ...kept, draft, people: true })
            go(plexHref('link', 'libraries'))
          },
        }}
      >
        {blocker ?? (
          <div className="space-y-4">
            {personList.length ? (
              <RowTable
                label="People on Plex"
                columns={['On Plex', 'Status', 'Account here']}
                rows={personList.map((p) => {
                  const status = plexPersonReady(p.status)
                  return {
                    key: p.plexUserId,
                    cells: [
                      <><b className="font-semibold break-words">{p.title}</b><span className="block text-[13px] text-neutral-400">{plexPersonKind(p.kind)}</span></>,
                      <Pill tone={status.tone}>{status.label}</Pill>,
                      p.status === 'ready' ? (
                        <select
                          aria-label={`Account here for ${p.title}`}
                          className={fieldInput}
                          value={draft!.users[p.plexUserId] ?? 0}
                          onChange={(e) => setDraft({ ...draft!, users: { ...draft!.users, [p.plexUserId]: Number(e.target.value) } })}
                        >
                          <option value={0}>Leave out</option>
                          {userList.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
                        </select>
                      ) : p.status === 'needs_pin' ? (
                        <span className="text-[13.5px] text-neutral-400">{plexPersonLeftOut(p)}</span>
                      ) : (
                        <span className="text-[13.5px] text-neutral-400">
                          Check {p.title}’s access in Plex, then{' '}
                          <button type="button" onClick={refresh} className="text-neutral-200 underline underline-offset-2">refresh</button>. Left out for now.
                        </span>
                      ),
                    ],
                  }
                })}
              />
            ) : (
              <Message tone="info" title="No people were found">Check the Plex account that owns {connection.name}, then refresh.</Message>
            )}
            <p className="text-sm text-neutral-400">
              Need an account that isn’t here? <HrefLink href={settingsHref('users', 'add')} className="text-neutral-200 underline">Add a person in Users</HrefLink>. Your choices on this step are kept while you’re away.
            </p>
            {refusal && <PlexNote message={refusal} focus />}
          </div>
        )}
      </FlowFrame>
    )
  }

  if (current === 'libraries')
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={LINK_STEPS}
        current="libraries"
        stepHref={(k) => plexHref('link', k)}
        title="Which library is which?"
        lede="Choose where each Plex library belongs here. Movies go with movies and TV with TV. Leave a library out and it isn’t read."
        back={{ label: 'People', href: plexHref('link', 'people') }}
        next={{
          label: 'Review',
          disabled: !ready,
          onClick: () => {
            setKept({ ...kept, draft, libraries: true })
            go(plexHref('link', 'review'))
          },
        }}
      >
        {blocker ?? (
          <div className="space-y-4">
            {sectionList.length ? (
              <RowTable
                label="Libraries on Plex"
                columns={['On Plex', 'Type', 'Library here']}
                rows={sectionList.map((s) => {
                  const type = s.type === 'movie' ? 'movie' : 'tv'
                  const choices = libraryList.filter((l) => l.type === type)
                  return {
                    key: s.sectionId,
                    cells: [
                      <b className="font-semibold break-words">{s.title}</b>,
                      <span className="text-neutral-300">{type === 'movie' ? 'Movies' : 'TV'}</span>,
                      choices.length ? (
                        <select
                          aria-label={`Library here for ${s.title}`}
                          className={fieldInput}
                          value={draft!.libraries[s.sectionId] ?? 0}
                          onChange={(e) => setDraft({ ...draft!, libraries: { ...draft!.libraries, [s.sectionId]: Number(e.target.value) } })}
                        >
                          <option value={0}>Leave out</option>
                          {choices.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
                        </select>
                      ) : (
                        <span className="text-[13.5px] text-neutral-400">No {type === 'movie' ? 'movie' : 'TV'} library here yet. Left out.</span>
                      ),
                    ],
                  }
                })}
              />
            ) : (
              <Message tone="info" title="No movie or TV libraries were found">Add them in Plex, then refresh.</Message>
            )}
            <p className="text-sm text-neutral-400">
              Need a library that isn’t here? <HrefLink href={settingsHref('libraries', 'add')} className="text-neutral-200 underline">Add a library in Libraries</HrefLink>.
            </p>
          </div>
        )}
      </FlowFrame>
    )

  // Review: the one place the links are saved.
  const userName = new Map(userList.map((u) => [u.id, u.name]))
  const libraryName = new Map(libraryList.map((l) => [l.id, l.name]))
  const linkedPeople = draft ? personList.filter((p) => (draft.users[p.plexUserId] ?? 0) > 0) : []
  const leftPeople = draft ? personList.filter((p) => !(draft.users[p.plexUserId] ?? 0)) : []
  const linkedSections = draft ? sectionList.filter((s) => (draft.libraries[s.sectionId] ?? 0) > 0) : []
  const leftSections = draft ? sectionList.filter((s) => !(draft.libraries[s.sectionId] ?? 0)) : []
  const changed = !!draft && plexMappingsChanged(connection, initial ?? draft, draft)
  const message = refusal ?? linkSaveMessage(save.error, connection.name)
  const submit = async () => {
    if (!draft) return
    const problem = plexMappingError(draft, personList, sectionList, userList, libraryList)
    if (problem) return setRefusal({ tone: 'err', title: 'Couldn’t save the links', body: problem })
    setRefusal(null)
    try {
      if (changed) {
        await save.mutateAsync(plexLinks(connection.generation, draft))
        setAsideCheck(id)
      }
      setSaved({
        accounts: linkedPeople.map((p) => userName.get(draft.users[p.plexUserId]) ?? p.title),
        libraries: linkedSections.map((s) => libraryName.get(draft.libraries[s.sectionId]) ?? s.title),
        sections: linkedSections.map((s) => s.title),
        leftOut: [...leftPeople.map((p) => p.title), ...leftSections.map((s) => s.title)],
        firstTime: !runs.data?.items.length,
      })
      go(plexHref('link', 'saved'))
    } catch {
      // The refusal shows above the footer.
    }
  }
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={LINK_STEPS}
      current="review"
      stepHref={(k) => plexHref('link', k)}
      title="Check the links"
      lede="Nothing is read or imported when you save. This only says who and what to look at."
      back={{ label: 'Libraries', href: plexHref('link', 'libraries') }}
      next={{
        label: 'Save links',
        onClick: () => void submit(),
        busy: save.isPending,
        busyLabel: 'Saving…',
        disabled: !ready || importing,
        reason: importing ? 'An import is running. Wait for it, or cancel it, first.' : undefined,
      }}
    >
      {blocker ?? (
        <div className="space-y-4">
          <Card>
            <ItemRow
              title="People"
              detail={linkSummary(linkedPeople.map((p) => [p.title, userName.get(draft!.users[p.plexUserId]) ?? p.title]), leftPeople.map((p) => p.title))}
              control={<HrefLink href={plexHref('link', 'people')} className="text-sm text-neutral-200 underline">Change</HrefLink>}
            />
            <ItemRow
              title="Libraries"
              detail={linkSummary(linkedSections.map((s) => [s.title, libraryName.get(draft!.libraries[s.sectionId]) ?? s.title]), leftSections.map((s) => s.title))}
              control={<HrefLink href={plexHref('link', 'libraries')} className="text-sm text-neutral-200 underline">Change</HrefLink>}
            />
          </Card>
          {changed && <SetAside connectionId={id} generation={connection.generation} />}
          {importing && (
            <PlexNote message={{ tone: 'warn', title: 'An import is running', body: 'Wait for it, or cancel it, before changing the links. Nothing has been saved.', fix: { do: 'see-import', label: 'See the import' } }} />
          )}
          {message && <PlexNote message={message} focus on={{ refresh, 'try-again': () => void submit() }} busy={save.isPending} />}
        </div>
      )}
    </FlowFrame>
  )
}

/** "Saving sets aside the last check", when this tab made one on today's links. */
function SetAside({ connectionId, generation }: { connectionId: number; generation: number }) {
  const [previewId] = useState(() => keptImport(connectionId)?.previewId ?? '')
  const preview = useQuery({ ...plexPreviewQuery(connectionId, previewId), enabled: false })
  const now = useClock(30_000)
  if (!previewId || (preview.data && preview.data.generation !== generation)) return null
  const when = ago(preview.data?.finishedAt ?? preview.data?.startedAt, now)
  return (
    <Message tone="warn" title="Saving sets aside the last check">
      You checked what will change {when ?? 'earlier'} with different links. You’ll check again before importing.
    </Message>
  )
}
