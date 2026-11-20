import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../../../api/client'
import { visibleConfirmationError, withConfirm } from '../../../api/withConfirm'
import { useSetRecordHistory } from '../../../api/activity'
import { useLinkSeerrUser } from '../../../api/integrations'
import { useAuth } from '../../../authContext'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { Button, Card, HrefLink, HubPage, ItemRow, LoadingRows, Message, RemoveSection, SaveNote, Switch, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { suggestSeerrUser } from '../../../lib/integrations'
import { plexKindLabel } from '../../../lib/plex'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { deleteRefusal, possessive, seerrRefusal, type StepError } from '../../../lib/usersSettings'
import { usePeople } from './data'
import { ResetPasswordDialog } from './ResetPasswordDialog'
import { noteDeleted } from './store'

/** One person's page: record history, the Seerr user, the Plex link, Reset password and Delete. */
export function PersonPage({ id }: { id: number }) {
  const auth = useAuth()
  const people = usePeople()
  const person = people.users.data?.items.find((u) => u.id === id)
  const [resetFor, setResetFor] = useState<string | null>(null)
  const [resetting, setResetting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // A delete's banner belongs to the hub it was shown on.
  useEffect(() => { noteDeleted(null) }, [])

  if (people.users.isPending) {
    return <HubPage crumbs={settingsCrumbs('users', { label: '…' })} title="…"><LoadingRows rows={4} /></HubPage>
  }
  if (!person) {
    return (
      <HubPage crumbs={settingsCrumbs('users', { label: 'Not found' })} title="This person isn’t here">
        <Message tone={people.users.isError ? 'err' : 'info'} title={people.users.isError ? 'Couldn’t read the people on this server' : 'No one on this server has this address'}
          actions={<HrefLink href={settingsHref('users')} className="inline-flex min-h-[36px] items-center rounded-md border border-line-2 px-3 text-sm">Back to Users</HrefLink>}>
          {people.users.isError ? errorText(people.users.error) : 'They may have been deleted.'}
        </Message>
      </HubPage>
    )
  }

  const you = person.id === auth.user?.id
  const devices = people.devices(person.id)
  const lede = [person.isAdmin ? 'Admin' : 'Member', you && 'you', devices ? `played on ${devices} ${devices === 1 ? 'device' : 'devices'}` : ''].filter(Boolean).join(' · ')
  return (
    <HubPage
      crumbs={settingsCrumbs('users', { label: person.name })}
      title={person.name}
      lede={lede}
      banner={resetFor && (
        <Message tone="ok" title={`${possessive(resetFor)} password is reset`}>
          All of {possessive(resetFor)} devices are signed out. Tell {resetFor} the new password.
        </Message>
      )}
    >
      <Card>
        <RecordHistoryRow id={person.id} name={person.name} on={person.recordHistory} />
        {people.hasSeerr && <SeerrRow person={person} people={people} />}
        {people.plex && <PlexRow person={person} people={people} />}
        {you ? (
          <ItemRow
            title="Password"
            detail="Your own password is changed under Account & devices."
            control={<HrefLink href={settingsHref('account', 'password')} className="inline-flex min-h-[36px] items-center text-sm text-neutral-200 underline">Change your password</HrefLink>}
          />
        ) : (
          <ItemRow
            title="Password"
            detail={`Set a new one and sign ${person.name} out everywhere.`}
            control={<Button small guarded onClick={() => { setResetFor(null); setResetting(true) }}>Reset password</Button>}
          />
        )}
      </Card>
      {!you && (
        <RemoveSection title="Delete">
          <ItemRow
            title={`Delete ${person.name}`}
            detail="Their watch history and sign-ins are deleted with them."
            control={<Button small variant="danger" guarded onClick={() => setDeleting(true)}>Delete…</Button>}
          />
        </RemoveSection>
      )}
      {resetting && (
        <ResetPasswordDialog
          user={person}
          onClose={() => setResetting(false)}
          onSuccess={() => { setResetting(false); setResetFor(person.name) }}
        />
      )}
      {deleting && <DeletePersonDialog person={person} plexConnected={!!people.plex} onClose={() => setDeleting(false)} />}
    </HubPage>
  )
}

type Person = { id: number; name: string; isAdmin: boolean; recordHistory: boolean; seerrUserId?: number }

function RecordHistoryRow({ id, name, on }: { id: number; name: string; on: boolean }) {
  const record = useSetRecordHistory()
  const save = useInstantSave()
  const [pending, setPending] = useState<boolean | null>(null)
  const checked = pending ?? on
  return (
    <ItemRow
      title="Record watch history"
      detail={<>
        <span className="block">Off: plays aren’t kept from now on, and Plex history is hidden for {name}. Their plays still show in Now playing. How long history is kept is under <HrefLink href={settingsHref('history')} className="text-neutral-200 underline">Watch history</HrefLink>.</span>
        <SaveNote state={save.state} />
      </>}
      control={
        <Switch
          checked={checked}
          disabled={save.state.status === 'saving'}
          label={`Record ${possessive(name)} watch history`}
          onChange={(next) => {
            setPending(next)
            void save.run(() => record.mutateAsync({ id, on: next }), next ? `${possessive(name)} plays are kept from now on.` : `${possessive(name)} plays aren’t kept from now on.`).finally(() => setPending(null))
          }}
        />
      }
    />
  )
}

function SeerrRow({ person, people }: { person: Person; people: ReturnType<typeof usePeople> }) {
  const link = useLinkSeerrUser()
  const save = useInstantSave()
  const [pending, setPending] = useState<number | null>(null)
  const [refusal, setRefusal] = useState<StepError | null>(null)
  const users = people.seerrUsers.data?.items
  const linked = pending ?? person.seerrUserId ?? 0
  const current = users?.find((s) => s.id === linked)
  const linkedIds = (people.users.data?.items ?? []).flatMap((u) => (u.seerrUserId && u.id !== person.id ? [u.seerrUserId] : []))
  const suggestion = !linked && users ? suggestSeerrUser(person.name, users, linkedIds) : undefined
  const choose = (seerrUserId: number) => {
    const chosen = users?.find((s) => s.id === seerrUserId)
    setPending(seerrUserId)
    setRefusal(null)
    void save
      .run(async () => {
        try {
          await link.mutateAsync({ id: person.id, seerrUserId })
        } catch (error) {
          setRefusal(seerrRefusal(error, chosen?.name, person.name))
          throw error
        }
      }, seerrUserId ? `${possessive(person.name)} requests are made as ${chosen?.name ?? 'that user'}.` : `${person.name} can’t request now.`)
      .finally(() => setPending(null))
  }
  const detail = !users
    ? people.seerrUsers.isError ? 'Couldn’t read Seerr’s users, so this can’t be changed now.' : 'Reading Seerr’s users…'
    : linked && !current ? `${person.name} is linked to a user Seerr no longer has, so they can’t request.`
    : current && !current.canRequest ? `Seerr doesn’t let ${current.name} request, so ${person.name} can’t either.`
    : !linked ? `${person.name} can’t request until they have a Seerr user.`
    : `${possessive(person.name)} requests are made as this Seerr user.`
  return (
    <>
      <ItemRow
        title={<label htmlFor={`seerr-${person.id}`}>Seerr user</label>}
        detail={<>
          <span className="block">{detail}</span>
          {suggestion && (
            <span className="mt-1 block">
              Suggested: {suggestion.name} (same name).{' '}
              <button type="button" disabled={save.state.status === 'saving'} onClick={() => choose(suggestion.id)} className="min-h-[32px] font-semibold text-accent underline underline-offset-2 disabled:opacity-50">
                Use {suggestion.name}
              </button>
            </span>
          )}
          {save.state.status !== 'failed' && <SaveNote state={save.state} />}
        </>}
        control={
          <select
            id={`seerr-${person.id}`}
            value={linked}
            disabled={!users || save.state.status === 'saving'}
            onChange={(e) => choose(Number(e.target.value))}
            className={`${fieldInput} min-w-[9rem] sm:w-auto`}
          >
            <option value={0}>None</option>
            {linked > 0 && !current && <option value={linked}>{users ? `Gone from Seerr (#${linked})` : `#${linked}`}</option>}
            {users?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        }
      />
      {save.state.status === 'failed' && (
        <div className="px-5 py-3">
          <Message tone="err" title={refusal?.title ?? 'The Seerr user wasn’t saved'}>{refusal?.text ?? save.state.message}</Message>
        </div>
      )}
    </>
  )
}

function PlexRow({ person, people }: { person: Person; people: ReturnType<typeof usePeople> }) {
  const plexPerson = people.plexPerson(person.id)
  const detail = people.plex?.status !== 'connected'
    ? 'Plex needs to be connected again before its people can be checked. Links already made stay.'
    : people.plexPeople.isError ? 'Couldn’t read the people in Plex. Open Plex to check the connection.'
    : people.plexPeople.isPending ? 'Reading the people in Plex…'
    : plexPerson ? `Linked to the ${plexKindLabel(plexPerson.kind)} ${plexPerson.title}.`
    : `Not linked to anyone in Plex, so no Plex history comes across for ${person.name}.`
  return (
    <ItemRow
      title="Plex"
      detail={detail}
      control={<HrefLink href={settingsHref('plex', 'link')} className="inline-flex min-h-[36px] items-center text-sm text-neutral-200 underline">{plexPerson ? 'Change in Plex' : 'Link in Plex'}</HrefLink>}
    />
  )
}

/**
 * Delete a person: what goes, that it can't be undone, and for an
 * admin with Plex connected, that Plex stops if they were the one who
 * connected it. The server doesn't say who connected Plex, so it says "If".
 */
function DeletePersonDialog({ person, plexConnected, onClose }: { person: Person; plexConnected: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const go = useGo()
  const remove = useMutation({
    mutationFn: async () => withConfirm(async () => unwrap(await api.DELETE('/api/users/{id}', { params: { path: { id: person.id } } })), 'delete this account'),
    onSuccess: () => {
      noteDeleted(person.name)
      void qc.invalidateQueries({ queryKey: ['users'] })
      void qc.invalidateQueries({ queryKey: ['activity'] })
      void qc.invalidateQueries({ queryKey: ['sessions'] })
      go(settingsHref('users'), { replace: true })
    },
  })
  const error = remove.isError && visibleConfirmationError(remove.error) ? deleteRefusal(remove.error, person.name) : undefined
  return (
    <ConfirmDialog
      title={`Delete ${person.name}?`}
      deleted={[`${possessive(person.name)} account, watch history and progress, settings, and sign-ins on every device.`]}
      confirmLabel={`Delete ${person.name}`}
      guarded
      busy={remove.isPending}
      error={error}
      onConfirm={() => remove.mutate()}
      onClose={onClose}
    >
      <p className="mt-4 text-sm text-neutral-400">This can’t be undone. To let {person.name} back in later, add them again as a new person.</p>
      {person.isAdmin && plexConnected && (
        <Message tone="warn" className="mt-4" title={`If ${person.name} connected Plex`}>
          Updates from Plex stop until another admin signs in to Plex again.
        </Message>
      )}
    </ConfirmDialog>
  )
}
