import { useId, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../../../api/settings'
import { useAuth } from '../../../authContext'
import { Button, Card, Field, ItemRow, LoadingRows, Message, Pill, fieldInput, type PillTone } from '../../../components/settings/Frame'
import { ConfirmDialog, LeaveCheck } from '../../../components/settings/Dialogs'
import { FlowFrame } from '../../../components/settings/Flow'
import { errorText, useGo } from '../../../components/settings/hooks'
import { adminAwayWording } from '../../../lib/adminAway'
import { sameValue } from '../../../lib/drafts'
import { languageName } from '../../../lib/format'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import {
  accountLine, andList, choiceSaveError, downloadsLine, fallbackCodes, fallbackRefusal, fallbackSaveError, fallbackTags, openSubtitlesError,
  serviceLede, serviceState, type Notice, type ServiceState,
} from '../../../lib/youSettings'
import { LanguageList } from './LanguageList'
import { selectInput, setFlash, useLeaveAfterSave, useRowSave } from './hooks'
import { RowNote } from './RowNote'

const PILL: Record<ServiceState, { tone: PillTone; label: string }> = {
  unavailable: { tone: 'off', label: 'Not in this build' },
  'signed-in': { tone: 'ok', label: 'Signed in' },
  failed: { tone: 'warn', label: 'Sign-in failed' },
  'signed-out': { tone: 'off', label: 'Not signed in' },
}

const signInHref = settingsHref('subtitles', 'opensubtitles')
const fallbackHref = settingsHref('subtitles', 'fallback-languages')

/**
 * The server subtitle service, below a person's own choices on Audio &
 * subtitles, for admins only (PLAN-ADMIN-SETTINGS.md §5.10). Its state in
 * words first; the login and the fallback languages are one-step tasks;
 * Who can download saves at once; Sign out asks first. Nothing here asks
 * for the password away from home. Members see nothing.
 */
export function SubtitleService() {
  const auth = useAuth()
  if (!auth.user?.isAdmin) return null
  return (
    <section aria-labelledby="subtitle-service" className="mt-10 border-t border-line pt-8">
      {auth.adminAllowed ? <ServiceBlock /> : <KeptOut limit={auth.adminLimit ?? 'network'} />}
    </section>
  )
}

function ServiceHead({ lede, pill }: { lede?: ReactNode; pill?: { tone: PillTone; label: string } }) {
  return (
    <div className="mb-5 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="min-w-0 max-w-2xl flex-1 basis-72">
        <div className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">Server-wide</div>
        <h2 id="subtitle-service" className="mt-1 text-xl font-semibold tracking-tight">Server subtitle service</h2>
        {lede && <p className="mt-1.5 text-neutral-400">{lede}</p>}
      </div>
      {pill && <Pill tone={pill.tone}>{pill.label}</Pill>}
    </div>
  )
}

function KeptOut({ limit }: { limit: 'network' | 'https' }) {
  return (
    <>
      <ServiceHead />
      <Card padded>
        <p>{adminAwayWording(limit).long}</p>
        <p className="mt-2.5 text-sm text-neutral-400">Online subtitles keep working for everyone; only changing the service waits until you’re home.</p>
      </Card>
    </>
  )
}

function ServiceBlock() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const signOut = useUpdateSettings()
  const { rows, run } = useRowSave<'everyone' | 'admins'>()
  const [signingOut, setSigningOut] = useState(false)
  const [signedOut, setSignedOut] = useState(false)
  const go = useGo()
  const s = settings.data?.subtitles
  if (!s) {
    return (
      <>
        <ServiceHead lede="For everyone on this server. Only admins see this." />
        {settings.isError ? (
          <Message tone="err" title="Couldn’t load the subtitle service" actions={<Button small onClick={() => void settings.refetch()}>Try again</Button>}>
            The server didn’t answer. Nothing has changed.
          </Message>
        ) : <LoadingRows rows={3} />}
      </>
    )
  }
  const state = serviceState(s)
  if (state === 'unavailable') {
    return (
      <>
        <ServiceHead lede={serviceLede(s)} pill={PILL[state]} />
        <Card padded>
          <p>This build of WatchMyMedia has no OpenSubtitles key, so it can’t look for subtitles online. Subtitles inside your files still work.</p>
          <p className="mt-2 text-sm text-neutral-400">The Docker image, the downloads and the Mac app have one.</p>
        </Card>
      </>
    )
  }
  const downloads = rows.downloads?.status === 'saving' ? rows.downloads.value! : s.downloads
  const setDownloads = (value: 'everyone' | 'admins') => {
    const before = s.downloads === 'admins' ? 'Admins only' : 'Everyone'
    void run('downloads', value, () => update.mutateAsync({ subtitles: { downloads: value } }), downloadsLine(value, true), (e) => choiceSaveError(e, before))
  }
  return (
    <>
      <ServiceHead lede={serviceLede(s)} pill={PILL[state]} />
      <div className="space-y-4">
        {state === 'failed' && (
          <Message tone="warn" title={`OpenSubtitles stopped accepting the login for ${s.username}`} actions={<Button small variant="primary" onClick={() => go(signInHref)}>Sign in again</Button>}>
            Online downloads are down to 5 a day until it’s fixed. The password may have changed at opensubtitles.com.
          </Message>
        )}
        {signedOut && <Message tone="ok" title="Signed out of OpenSubtitles">Online downloads are down to 5 a day, for everyone on this server.</Message>}
        <Card>
          <ItemRow
            title="OpenSubtitles account"
            detail={accountLine(s)}
            control={s.username ? (
              <>
                <Button small onClick={() => go(signInHref)}>Change login</Button>
                <Button small onClick={() => setSigningOut(true)}>Sign out…</Button>
              </>
            ) : <Button small variant="primary" onClick={() => go(signInHref)}>Sign in</Button>}
          />
          <ItemRow
            title="Fallback languages"
            detail={`${s.languages.length ? andList(s.languages.map((c) => languageName(c))) : 'None'}. Searched for people who haven’t chosen subtitle languages.`}
            control={<Button small onClick={() => go(fallbackHref)}>Change</Button>}
          />
          <DownloadsRow value={downloads} state={rows.downloads} onChange={setDownloads} />
        </Card>
        <p className="text-sm text-neutral-400">
          Downloads are kept on this server and included in its backups, so picking one again costs nothing. Subtitles provided by{' '}
          <a href="https://opensubtitles.com" target="_blank" rel="noreferrer" className="text-neutral-200 underline">OpenSubtitles.com</a>.
        </p>
      </div>
      {signingOut && (
        <ConfirmDialog
          title="Sign out of OpenSubtitles?"
          stops={['Online downloads drop to 5 a day, for everyone on this server.']}
          stays={['Every subtitle already downloaded, and searching.']}
          confirmLabel="Sign out"
          busy={signOut.isPending}
          error={signOut.isError ? errorText(signOut.error) : undefined}
          onClose={() => { setSigningOut(false); signOut.reset() }}
          onConfirm={() => signOut.mutate({ subtitles: { username: '' } }, { onSuccess: () => { setSigningOut(false); setSignedOut(true) } })}
        />
      )}
    </>
  )
}

function DownloadsRow({ value, state, onChange }: { value: 'everyone' | 'admins'; state?: ReturnType<typeof useRowSave<'everyone' | 'admins'>>['rows'][string]; onChange: (v: 'everyone' | 'admins') => void }) {
  const id = useId()
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
      <div className="min-w-0 flex-1 basis-56">
        <label htmlFor={id} className="font-medium">Who can download</label>
        <div id={`${id}-note`} className="mt-0.5 text-sm text-neutral-400"><RowNote state={state} idle={downloadsLine(value)} /></div>
      </div>
      <select id={id} aria-describedby={`${id}-note`} value={value} disabled={state?.status === 'saving'} onChange={(e) => onChange(e.target.value as 'everyone' | 'admins')} className={selectInput}>
        <option value="everyone">Everyone</option>
        <option value="admins">Admins only</option>
      </select>
    </div>
  )
}

/** Why a service task can't be done here, in place of its form; null when it can. */
function useServiceGate(): ReactNode | null {
  const auth = useAuth()
  const settings = useQuery({ ...settingsQuery, enabled: !!auth.user?.isAdmin && auth.adminAllowed })
  if (!auth.user?.isAdmin) return <p>Only admins can change the server’s settings.</p>
  if (!auth.adminAllowed) return <p>{adminAwayWording(auth.adminLimit ?? 'network').long}</p>
  if (settings.data && !settings.data.subtitles.available) return <p>This build of WatchMyMedia has no OpenSubtitles key, so it can’t look for subtitles online. Subtitles inside your files still work.</p>
  return null
}

const backToTab = { label: 'Back to Audio & subtitles', href: settingsHref('subtitles') }

/** Sign in to OpenSubtitles, or change the login: one step, checked before it is saved. */
export function OpenSubtitlesFlow() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const { leaving, leave } = useLeaveAfterSave()
  const gate = useServiceGate()
  const s = settings.data?.subtitles
  const changing = !!s?.username
  const [username, setUsername] = useState<string | null>(null)
  const [password, setPassword] = useState('')
  const [problem, setProblem] = useState<Notice | null>(null)
  const name = username ?? s?.username ?? ''
  const typed = username !== null || password !== ''
  const crumbs = settingsCrumbs('subtitles', { label: changing ? 'Change login' : 'Sign in to OpenSubtitles' })
  const submit = () => {
    if (!name.trim() || !password) return
    setProblem(null)
    update.mutate({ subtitles: { username: name.trim(), password } }, {
      onSuccess: (after) => {
        const a = after.subtitles.account
        setFlash('subtitles', { title: `Signed in as ${after.subtitles.username || name.trim()}`, body: a ? `${a.allowedDownloads} downloads a day, for everyone on this server.` : 'Downloads count against this account, for everyone on this server.' })
        leave(settingsHref('subtitles'))
      },
      onError: (e) => setProblem(openSubtitlesError(e, errorText(e))),
    })
  }
  return (
    <FlowFrame
      tab="subtitles"
      crumbs={crumbs}
      title={changing ? 'Change the OpenSubtitles login' : 'Sign in to OpenSubtitles'}
      lede="Downloads count against this account: 20 a day on a free account, up to 1000 on VIP. Without one, the server gets 5 a day."
      help={gate ? undefined : (
        <>
          <h4>Getting an account</h4>
          <p>Sign up for free at <a href="https://www.opensubtitles.com/users/sign_up" target="_blank" rel="noreferrer">opensubtitles.com</a> and confirm your email. No API key is needed; WatchMyMedia brings its own.</p>
          <h4>More downloads</h4>
          <p>A VIP subscription on the same site raises the account’s limit to 1000 a day.</p>
        </>
      )}
      back={gate ? backToTab : { label: 'Cancel', href: settingsHref('subtitles') }}
      next={gate ? undefined : {
        label: changing ? 'Save login' : 'Sign in',
        onClick: submit,
        busy: update.isPending,
        busyLabel: 'Checking with OpenSubtitles…',
        disabled: !s || !name.trim() || !password,
      }}
    >
      {gate ?? (s ? (
        <div className="max-w-md">
          <LeaveCheck when={typed && !leaving} what="The login you typed isn’t saved. The server keeps the one it had." />
          <Field label="Username">
            {(p) => <input {...p} value={name} onChange={(e) => setUsername(e.target.value)} autoComplete="off" spellCheck={false} autoCapitalize="none" className={fieldInput} />}
          </Field>
          <Field label="Password">
            {(p) => <input {...p} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" className={fieldInput} />}
          </Field>
          {problem && <Message tone="err" title={problem.title} focus className="mt-5">{problem.body}</Message>}
        </div>
      ) : <LoadingRows rows={2} />)}
    </FlowFrame>
  )
}

/** The fallback languages as an ordered list of names: one step, saved once. */
export function FallbackLanguagesFlow() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const { leaving, leave } = useLeaveAfterSave()
  const gate = useServiceGate()
  const saved = settings.data ? fallbackTags(settings.data.subtitles.languages) : null
  const [draft, setDraft] = useState<string[] | null>(null)
  const [problem, setProblem] = useState<Notice | null>(null)
  const list = draft ?? saved ?? []
  const dirty = !!saved && !!draft && !sameValue(saved, draft)
  const submit = () => {
    if (!dirty) return
    setProblem(null)
    update.mutate({ subtitles: { languages: fallbackCodes(list) } }, {
      onSuccess: (after) => {
        const names = after.subtitles.languages.map((c) => languageName(c))
        setFlash('subtitles', { title: 'Fallback languages saved', body: names.length ? `${andList(names)}, in that order, for people who haven’t chosen subtitle languages.` : 'None now. People who haven’t chosen subtitle languages get the server’s default.' })
        leave(settingsHref('subtitles'))
      },
      onError: (e) => setProblem(fallbackSaveError(e, errorText(e))),
    })
  }
  return (
    <FlowFrame
      tab="subtitles"
      crumbs={settingsCrumbs('subtitles', { label: 'Fallback languages' })}
      title="Fallback languages"
      lede="Searched, in this order, for people who haven’t chosen any subtitle languages of their own."
      back={gate ? backToTab : { label: 'Cancel', href: settingsHref('subtitles') }}
      next={gate ? undefined : { label: 'Save languages', onClick: submit, busy: update.isPending, disabled: !dirty }}
    >
      {gate ?? (saved ? (
        <div className="max-w-2xl">
          <LeaveCheck when={dirty && !leaving} what="Your changes to the fallback languages aren’t saved." />
          <LanguageList kind="subtitle" addLabel="Add a fallback language" languages={list} saved={saved} disabled={update.isPending}
            onChange={(next) => { setDraft(next); setProblem(null) }} refuse={fallbackRefusal}
            empty="No fallback language. People who haven’t chosen subtitle languages get the server’s default." />
          {problem && <Message tone="err" title={problem.title} focus className="mt-5">{problem.body}</Message>}
        </div>
      ) : <LoadingRows rows={2} />)}
    </FlowFrame>
  )
}
