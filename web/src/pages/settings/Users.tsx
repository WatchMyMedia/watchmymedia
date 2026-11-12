import { withConfirm, visibleConfirmationError } from '../../api/withConfirm'
import { useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { api, unwrap } from '../../api/client'
import { usersQuery } from '../../api/queries'
import { useSetRecordHistory } from '../../api/activity'
import { integrationsQuery, seerrUsersQuery, useLinkSeerrUser, type SeerrUser } from '../../api/integrations'
import { deleteSettingsQuery, useUpdateDeleteSettings } from '../../api/delete'
import { offlineSettingsQuery, useUpdateOfflineSettings } from '../../api/offline'
import { useAuth } from '../../authContext'
import { PageHeader, Panel, primaryButton, secondaryButton, textInput } from '../../components/SettingsPanel'
import { suggestSeerrUser } from '../../lib/integrations'
import { WHO_CAN_DOWNLOAD } from '../../lib/offline'
import { PlexUserLinks } from '../../components/PlexUserLinks'
import { ServerOnly } from './SettingsLayout'

/** Settings › Users: who can sign in, who is an admin, who can delete and download, whose plays are kept, and with Seerr connected, who each person requests as. */
export function UsersSettings() {
  return (
    <>
      <PageHeader title="Users">
        People who can sign in to this server. Admins can see everyone&rsquo;s history, devices and addresses under Activity.
      </PageHeader>
      <ServerOnly>
        <div className="space-y-6">
          {/* Side by side where they fit (design/mockups/web/UsersDownloading). */}
          <div className="flex flex-wrap items-stretch gap-6">
            <div className="flex min-w-0 flex-[1_1_22rem] flex-col [&>section]:flex-1">
              <WhoCanDelete />
            </div>
            <div className="flex min-w-0 flex-[1_1_22rem] flex-col [&>section]:flex-1">
              <WhoCanDownload />
            </div>
          </div>
          <UserList />
          <PlexUserLinks />
          <AddUser />
        </div>
      </ServerOnly>
    </>
  )
}

/** Admins only (the default) or everyone with an account may delete episodes and movies (PLAN-EPISODE-PAGE.md §4, design/mockups/web/UsersDeleting). */
function WhoCanDelete() {
  const settings = useQuery(deleteSettingsQuery)
  const update = useUpdateDeleteSettings()
  const access = settings.data?.access
  return (
    <Panel title="Who can delete">
      <fieldset className="border-t border-line px-5 py-2">
        <legend className="sr-only">Who can delete</legend>
        {(
          [
            ['admins', 'Admins only'],
            ['everyone', 'Everyone with an account'],
          ] as const
        ).map(([v, label]) => (
          <label key={v} className="flex min-h-[44px] cursor-pointer items-center gap-3 text-[15px]">
            <input
              type="radio"
              name="who-deletes"
              value={v}
              checked={access === v}
              disabled={!settings.data || update.isPending}
              onChange={() => update.mutate(v)}
              className="h-[18px] w-[18px] accent-accent"
            />
            {label}
          </label>
        ))}
        {(settings.isError || update.isError) && <p className="pb-2 text-sm text-orange-300">{(settings.error ?? update.error)?.message}</p>}
      </fieldset>
      <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
        Deleting removes the file through Radarr or Sonarr. Files they don&rsquo;t manage can&rsquo;t be deleted from WatchMyMedia. Shows and whole seasons can
        only be deleted by admins.
      </p>
    </Panel>
  )
}

/**
 * No one, admins only (the default) or everyone with an account may download
 * to watch offline in the Android app (PLAN-DOWNLOADS.md §4.3,
 * design/mockups/web/UsersDownloading).
 */
function WhoCanDownload() {
  const settings = useQuery(offlineSettingsQuery)
  const update = useUpdateOfflineSettings()
  const access = settings.data?.access
  return (
    <Panel title="Who can download">
      <fieldset className="border-t border-line px-5 py-2">
        <legend className="sr-only">Who can download</legend>
        {WHO_CAN_DOWNLOAD.map(([v, label]) => (
          <label key={v} className="flex min-h-[44px] cursor-pointer items-center gap-3 text-[15px]">
            <input
              type="radio"
              name="who-downloads"
              value={v}
              checked={access === v}
              disabled={!settings.data || update.isPending}
              onChange={() => update.mutate(v)}
              className="h-[18px] w-[18px] accent-accent"
            />
            {label}
          </label>
        ))}
        {(settings.isError || update.isError) && <p className="pb-2 text-sm text-orange-300">{(settings.error ?? update.error)?.message}</p>}
      </fieldset>
      <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
        Downloads let people watch without a connection in the Android app on phones and tablets. They don&rsquo;t expire. Someone who can no longer download
        loses their downloads the next time their device connects.
      </p>
      <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
        This decides what the apps offer, not who can copy a file: anyone who can play a file can save it. Everyone&rsquo;s downloads are under{' '}
        <Link to="/activity/downloads" className="text-neutral-200 underline">
          Activity › Downloads
        </Link>
        .
      </p>
    </Panel>
  )
}

function UserList() {
  const qc = useQueryClient()
  const auth = useAuth()
  const users = useQuery(usersQuery)
  const [resetUser, setResetUser] = useState<{ id: number; name: string } | null>(null)
  const [resetName, setResetName] = useState('')
  const remove = useMutation({
    mutationFn: async (id: number) => withConfirm(async () => unwrap(await api.DELETE('/api/users/{id}', { params: { path: { id } } })), 'delete this account'),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['users'] }),
  })
  const record = useSetRecordHistory()
  const integrations = useQuery(integrationsQuery)
  const hasSeerr = integrations.data?.items.some((c) => c.kind === 'seerr') ?? false
  const seerrUsers = useQuery({ ...seerrUsersQuery, enabled: hasSeerr })
  const link = useLinkSeerrUser()
  const linkedIds = (users.data?.items ?? []).flatMap((u) => (u.seerrUserId ? [u.seerrUserId] : []))
  return (
    <>
      <Panel>
        {/* relative: the sr-only labels are absolute and would otherwise widen the page on a phone. */}
        <div className="relative overflow-x-auto">
          <table className={`w-full text-sm ${hasSeerr ? 'sm:min-w-[720px]' : 'sm:min-w-[480px]'}`}>
            <thead className="hidden sm:table-header-group">
              <tr className="text-left text-xs text-neutral-400">
                <th scope="col" className="px-5 py-3 font-medium">
                  Person
                </th>
                <th scope="col" className="px-3 py-3 font-medium">
                  Role
                </th>
                <th scope="col" className="px-3 py-3 font-medium">
                  Record history
                </th>
                {hasSeerr && (
                  <th scope="col" className="px-3 py-3 font-medium">
                    Seerr user
                  </th>
                )}
                <th scope="col" className="px-5 py-3">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="block sm:table-row-group">
              {users.data?.items.map((u) => (
                <tr key={u.id} className="grid grid-cols-2 border-t border-line sm:table-row">
                  <td className="col-span-2 px-5 py-2.5 sm:table-cell">
                    <span className="flex flex-wrap items-center gap-3">
                      <span
                        aria-hidden="true"
                        className={`grid h-8 w-8 shrink-0 place-items-center rounded-full text-[13px] font-semibold ${u.isAdmin ? 'bg-brand text-white ring-1 ring-brand-line' : 'bg-line-2 text-neutral-200'}`}
                      >
                        {u.name.charAt(0).toUpperCase()}
                      </span>
                      <span className="min-w-0 break-words font-medium">{u.name}</span>
                      {u.id === auth.user?.id && <span className="text-xs text-neutral-400">you</span>}
                      <span className="text-xs text-neutral-400 sm:hidden">{u.isAdmin ? 'Admin' : 'Member'}</span>
                    </span>
                  </td>
                  <td className="hidden px-3 py-2.5 sm:table-cell">
                    {u.isAdmin ? (
                      <span className="rounded-full bg-accent px-2 py-0.5 text-xs font-semibold text-black">Admin</span>
                    ) : (
                      <span className="text-neutral-400">Member</span>
                    )}
                  </td>
                  <td className="col-span-2 px-5 py-1 sm:table-cell sm:px-3 sm:py-2.5">
                    <label className="inline-flex min-h-[36px] cursor-pointer items-center gap-2">
                      <input
                        type="checkbox"
                        checked={u.recordHistory}
                        disabled={record.isPending}
                        onChange={(e) => record.mutate({ id: u.id, on: e.target.checked })}
                        aria-label={`Record ${u.name}’s history`}
                        className="h-[18px] w-[18px] accent-accent"
                      />
                      <span aria-hidden="true" className="text-neutral-400">
                        <span className="sm:hidden">Record history: </span>
                        {u.recordHistory ? 'On' : 'Off'}
                      </span>
                    </label>
                  </td>
                  {hasSeerr && (
                    <td className="col-span-2 px-5 py-1 sm:table-cell sm:px-3 sm:py-2.5">
                      <span className="mr-2 text-neutral-400 sm:hidden">Seerr user</span>
                      <SeerrLink
                        name={u.name}
                        linked={u.seerrUserId ?? 0}
                        users={seerrUsers.data?.items}
                        linkedIds={linkedIds}
                        busy={link.isPending}
                        onLink={(seerrUserId) => link.mutate({ id: u.id, seerrUserId })}
                      />
                    </td>
                  )}
                  <td className="col-span-2 px-5 pb-2.5 sm:table-cell sm:py-2.5">
                    <div className="flex flex-wrap items-center justify-end gap-x-3">
                      {u.id === auth.user?.id ? (
                        <Link to="/settings/account" className="inline-flex min-h-[44px] items-center text-neutral-300 hover:text-white">
                          Change password
                        </Link>
                      ) : (
                        <>
                          <button
                            type="button"
                            aria-label={`Reset password for ${u.name}`}
                            onClick={() => {
                              setResetName('')
                              setResetUser(u)
                            }}
                            className="min-h-[44px] text-neutral-300 hover:text-white"
                          >
                            Reset password
                          </button>
                          <button
                            type="button"
                            disabled={remove.isPending}
                            onClick={() => {
                              if (window.confirm(`Delete ${u.name}? Their watch history and sign-ins are deleted with them.`)) remove.mutate(u.id)
                            }}
                            className="min-h-[44px] px-2 text-neutral-400 hover:text-red-400"
                          >
                            Delete
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {resetName && (
          <p role="status" className="border-t border-line px-5 py-3 text-sm text-emerald-300">
            Password reset for {resetName}. All their browsers and devices were signed out.
          </p>
        )}
        {remove.isError && visibleConfirmationError(remove.error) && <p className="border-t border-line px-5 py-3 text-sm text-red-400">{remove.error.message}</p>}
        {link.isError && <p className="border-t border-line px-5 py-3 text-sm text-orange-300">{link.error.message}</p>}
        {hasSeerr && (
          <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
            Requests go to Seerr, as each person&rsquo;s Seerr user. People who are not linked can&rsquo;t request while Seerr is the backend. Matching names
            are suggested.
            {seerrUsers.isError && <span className="text-orange-300"> Couldn&rsquo;t read Seerr&rsquo;s users: {seerrUsers.error.message}</span>}
          </p>
        )}
        <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
          With history off, a person&rsquo;s plays still show in Now playing but are not kept. How long history is kept is under{' '}
          <Link to="/settings/history" className="text-neutral-200 underline">
            Watch history
          </Link>
          .
        </p>
      </Panel>
      {resetUser && (
        <ResetPasswordDialog
          user={resetUser}
          onClose={() => setResetUser(null)}
          onSuccess={() => {
            setResetName(resetUser.name)
            setResetUser(null)
            void qc.invalidateQueries({ queryKey: ['sessions'] })
          }}
        />
      )}
    </>
  )
}

/** Native modal contains focus and returns it to the row action when closed. */
function ResetPasswordDialog({ user, onClose, onSuccess }: { user: { id: number; name: string }; onClose: () => void; onSuccess: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const passwordInput = useRef<HTMLInputElement>(null)
  const id = useId()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [mismatch, setMismatch] = useState(false)
  const reset = useMutation({
    mutationFn: async () => withConfirm(async () => unwrap(await api.PUT('/api/users/{id}/password', { params: { path: { id: user.id } }, body: { password } })), 'reset this account’s password'),
    onSuccess,
  })
  useEffect(() => {
    const modal = dialog.current!
    const trigger = document.activeElement
    modal.showModal()
    passwordInput.current?.focus()
    return () => {
      modal.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus()
    }
  }, [])
  return (
    <dialog
      ref={dialog}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-description`}
      onCancel={(e) => {
        e.preventDefault()
        if (!reset.isPending) onClose()
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && !reset.isPending) onClose()
      }}
      className="mx-auto my-6 max-h-[calc(100%-3rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-xl border border-line-2 bg-surface p-0 text-neutral-100 shadow-xl backdrop:bg-black/80 sm:my-10 sm:max-h-[calc(100%-5rem)]"
    >
      <div className="p-6">
        <div className="flex items-start justify-between gap-3">
          <h2 id={`${id}-title`} className="min-w-0 break-words text-xl font-bold">
            Reset password for {user.name}
          </h2>
          <button
            type="button"
            onClick={onClose}
            disabled={reset.isPending}
            aria-label="Close"
            className="-mr-2 -mt-2 min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 disabled:opacity-50"
          >
            ✕
          </button>
        </div>
        <p id={`${id}-description`} className="mt-2 text-sm text-neutral-400">
          This signs out all their browsers and devices. Their watch history and settings stay.
        </p>
        <form
          className="mt-5 space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (reset.isPending) return
            if (password !== confirmation) {
              setMismatch(true)
              return
            }
            setMismatch(false)
            reset.mutate()
          }}
        >
          <label className="block">
            <span className="mb-1.5 block text-sm text-neutral-300">New password</span>
            <input
              ref={passwordInput}
              type="password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
                setMismatch(false)
              }}
              required
              minLength={8}
              maxLength={256}
              autoComplete="new-password"
              disabled={reset.isPending}
              placeholder="8 or more characters"
              className={`${textInput} w-full`}
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-sm text-neutral-300">Confirm password</span>
            <input
              type="password"
              value={confirmation}
              onChange={(e) => {
                setConfirmation(e.target.value)
                setMismatch(false)
              }}
              required
              minLength={8}
              maxLength={256}
              autoComplete="new-password"
              disabled={reset.isPending}
              aria-invalid={mismatch || undefined}
              aria-describedby={mismatch ? `${id}-mismatch` : undefined}
              className={`${textInput} w-full`}
            />
          </label>
          {mismatch && (
            <p id={`${id}-mismatch`} role="alert" className="text-sm text-red-400">
              Passwords do not match.
            </p>
          )}
          {reset.isError && visibleConfirmationError(reset.error) && (
            <p role="alert" className="text-sm text-red-400">
              {reset.error.message}
            </p>
          )}
          <p className="text-sm text-neutral-400">Share the new password with {user.name} so they can sign in again.</p>
          <div className="flex flex-wrap justify-end gap-3 pt-2">
            <button type="button" onClick={onClose} disabled={reset.isPending} className={secondaryButton}>
              Cancel
            </button>
            <button type="submit" disabled={reset.isPending} className={primaryButton}>
              {reset.isPending ? 'Resetting…' : 'Reset password'}
            </button>
          </div>
        </form>
      </div>
    </dialog>
  )
}

/** The Seerr user a person requests as, with a suggestion while they aren't linked. */
function SeerrLink({
  name,
  linked,
  users,
  linkedIds,
  busy,
  onLink,
}: {
  name: string
  linked: number
  users: SeerrUser[] | undefined
  linkedIds: number[]
  busy: boolean
  onLink: (seerrUserId: number) => void
}) {
  const current = users?.find((s) => s.id === linked)
  const suggestion = !linked && users ? suggestSeerrUser(name, users, linkedIds) : undefined
  const cantRequest = users !== undefined && (!current || !current.canRequest) && !suggestion
  return (
    <span className="flex flex-wrap items-center gap-2">
      <label>
        <span className="sr-only">Seerr user for {name}</span>
        <select value={linked} disabled={busy || !users} onChange={(e) => onLink(Number(e.target.value))} className={`${textInput} min-w-44`}>
          <option value={0}>Not linked</option>
          {linked > 0 && !current && <option value={linked}>{users ? `Gone from Seerr (#${linked})` : `#${linked}`}</option>}
          {users?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      {suggestion && (
        <button
          type="button"
          disabled={busy}
          onClick={() => onLink(suggestion.id)}
          className="min-h-[36px] rounded-full border border-accent/60 bg-accent/10 px-3.5 text-[13px] font-semibold whitespace-nowrap text-accent hover:bg-accent/15"
        >
          Suggested: {suggestion.name}
        </button>
      )}
      {cantRequest && (
        <span className="inline-flex items-center gap-1.5 text-[13px] text-warn">
          <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-warn" />
          Can&rsquo;t request
        </span>
      )}
    </span>
  )
}

function AddUser() {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [isAdmin, setIsAdmin] = useState(false)
  const create = useMutation({
    mutationFn: async () => withConfirm(async () => unwrap(await api.POST('/api/users', { body: { name, password, isAdmin } })), 'add an admin'),
    onSuccess: () => {
      setName('')
      setPassword('')
      setIsAdmin(false)
      void qc.invalidateQueries({ queryKey: ['users'] })
    },
  })
  return (
    <Panel title="Add a person">
      <form
        className="flex flex-wrap items-end gap-3 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          create.mutate()
        }}
      >
        <label className="min-w-0 flex-1 basis-44">
          <span className="mb-1.5 block text-sm text-neutral-400">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required className={`${textInput} w-full`} />
        </label>
        <label className="min-w-0 flex-1 basis-56">
          <span className="mb-1.5 block text-sm text-neutral-400">Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="8 or more characters"
            required
            minLength={8}
            autoComplete="new-password"
            className={`${textInput} w-full`}
          />
        </label>
        <label className="flex min-h-[40px] cursor-pointer items-center gap-2 text-sm text-neutral-200">
          <input type="checkbox" checked={isAdmin} onChange={(e) => setIsAdmin(e.target.checked)} className="h-[18px] w-[18px] accent-accent" />
          Admin
        </label>
        <button type="submit" disabled={create.isPending} className={primaryButton}>
          Add person
        </button>
      </form>
      {create.isError && visibleConfirmationError(create.error) && <p className="px-5 pb-4 text-sm text-red-400">{create.error.message}</p>}
    </Panel>
  )
}
