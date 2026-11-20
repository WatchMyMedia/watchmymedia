import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../../authContext'
import { deleteSettingsQuery, useUpdateDeleteSettings } from '../../../api/delete'
import { offlineSettingsQuery, useUpdateOfflineSettings } from '../../../api/offline'
import { Button, Card, HrefLink, HubPage, ItemRow, LoadingRows, Message, Pill, SaveNote, Section, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { cantRequest, deleteSavedNote, downloadSavedNote, personLine, seerrState, type DeleteAccess, type DownloadAccess } from '../../../lib/usersSettings'
import { usePeople } from './data'
import { clearAddDraft, noteDeleted, useAddDraft, useDeletedNotice } from './store'

/** Settings › Users: people as rows that open a page each, and what everyone can do. */
export function UsersHub() {
  const go = useGo()
  const auth = useAuth()
  const people = usePeople()
  const deleted = useDeletedNotice()
  const added = !!useAddDraft().created
  // Someone added is done with: Add a person starts again at Name and role.
  useEffect(() => { if (added) clearAddDraft() }, [added])
  const items = people.users.data?.items
  return (
    <HubPage
      crumbs={settingsCrumbs('users')}
      title="Users"
      lede="People who can sign in to this server. There are no invites: you add a person and give them their password."
      actions={<Button variant="primary" small onClick={() => go(settingsHref('users', 'add'))}>Add a person</Button>}
      banner={deleted && (
        <Message tone="ok" title={`${deleted} is deleted`} actions={<Button small variant="quiet" onClick={() => noteDeleted(null)}>Dismiss</Button>}>
          Their watch history and sign-ins are gone. Their devices show the sign-in page.
        </Message>
      )}
    >
      <Section title="People">
        {people.users.isPending ? (
          <LoadingRows rows={3} />
        ) : people.users.isError ? (
          <Message tone="err" title="Couldn’t read the people on this server" actions={<Button small onClick={() => void people.users.refetch()}>Try again</Button>}>
            {errorText(people.users.error)}
          </Message>
        ) : (
          <Card>
            {items?.map((u) => {
              const seerr = people.hasSeerr ? seerrState(u.seerrUserId, people.seerrUsers.data?.items) : undefined
              const you = u.id === auth.user?.id
              return (
                <ItemRow
                  key={u.id}
                  href={settingsHref('users', u.id)}
                  title={u.name}
                  pill={<>
                    {(u.isAdmin || you) && <Pill tone={u.isAdmin ? 'now' : 'off'}>{[u.isAdmin && 'Admin', you && 'you'].filter(Boolean).join(' · ')}</Pill>}
                    {cantRequest(seerr) && <Pill tone="warn">Can’t request</Pill>}
                  </>}
                  detail={personLine({ recordHistory: u.recordHistory, plexLinked: !!people.plexPerson(u.id), seerr, devices: people.devices(u.id) })}
                />
              )
            })}
          </Card>
        )}
        {people.hasSeerr && people.seerrUsers.isError && (
          <Message tone="warn" className="mt-3" title="Couldn’t read Seerr’s users">
            {errorText(people.seerrUsers.error)} Who can request is shown again once Seerr answers.
          </Message>
        )}
      </Section>
      <Section title="What everyone can do">
        <Card>
          <WhoCanDelete />
          <WhoCanDownload people={items ?? []} />
        </Card>
      </Section>
    </HubPage>
  )
}

const selectLook = `${fieldInput} min-w-[9rem] sm:w-auto`

/** Admins only (the default) or everyone may delete episodes and movies, through Radarr or Sonarr. */
function WhoCanDelete() {
  const settings = useQuery(deleteSettingsQuery)
  const update = useUpdateDeleteSettings()
  const save = useInstantSave()
  const [pending, setPending] = useState<DeleteAccess | null>(null)
  const value = pending ?? settings.data?.access
  return (
    <ItemRow
      title={<label htmlFor="who-can-delete">Who can delete movies and episodes</label>}
      detail={save.state.status === 'idle' ? 'Through Radarr or Sonarr. Shows and seasons are always admins only.' : <SaveNote state={save.state} />}
      control={
        <select
          id="who-can-delete"
          value={value ?? ''}
          disabled={!settings.data || save.state.status === 'saving'}
          onChange={(e) => {
            const to = e.target.value as DeleteAccess
            setPending(to)
            void save.run(() => update.mutateAsync(to), deleteSavedNote(to)).finally(() => setPending(null))
          }}
          className={selectLook}
        >
          {!settings.data && <option value="">{settings.isError ? 'Couldn’t read' : 'Loading…'}</option>}
          <option value="admins">Admins</option>
          <option value="everyone">Everyone</option>
        </select>
      }
    />
  )
}

/** No one, admins only (the default) or everyone may download to watch offline. */
function WhoCanDownload({ people }: { people: { name: string; isAdmin: boolean }[] }) {
  const settings = useQuery(offlineSettingsQuery)
  const update = useUpdateOfflineSettings()
  const save = useInstantSave()
  const [pending, setPending] = useState<DownloadAccess | null>(null)
  const value = pending ?? settings.data?.access
  return (
    <ItemRow
      title={<label htmlFor="who-can-download">Who can download to watch offline</label>}
      detail={save.state.status === 'idle' ? (
        <>In the Android app on phones and tablets. Everyone’s downloads are under <HrefLink href="/activity/downloads" className="text-neutral-200 underline">Activity › Downloads</HrefLink>.</>
      ) : <SaveNote state={save.state} />}
      control={
        <select
          id="who-can-download"
          value={value ?? ''}
          disabled={!settings.data || save.state.status === 'saving'}
          onChange={(e) => {
            const to = e.target.value as DownloadAccess
            const from = settings.data?.access
            setPending(to)
            void save.run(() => update.mutateAsync(to), downloadSavedNote(from, to, people)).finally(() => setPending(null))
          }}
          className={selectLook}
        >
          {!settings.data && <option value="">{settings.isError ? 'Couldn’t read' : 'Loading…'}</option>}
          <option value="none">No one</option>
          <option value="admins">Admins</option>
          <option value="everyone">Everyone</option>
        </select>
      }
    />
  )
}
