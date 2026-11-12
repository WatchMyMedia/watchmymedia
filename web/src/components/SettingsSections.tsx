import { visibleConfirmationError } from '../api/withConfirm'
import { useId, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { checkTurnstile, settingsQuery, testTMDB, trickplayQuery, useCreateBackup, useDownloadBackup, useDeleteTrickplay, useUpdateSettings, type ActivitySettings } from '../api/settings'
import { mediaListSourcesQuery, mediaListsQuery } from '../api/queries'
import { sentence } from '../lib/mediaLists'
import { formatBytes, plural, relativeTime } from '../lib/format'
import { currentTitle, deleteHint, filesLine, percentDone, pollInterval, thumbnailCopy, thumbnailState } from '../lib/trickplay'
import { CheckRow, Fact, Panel, Row, StatusPill, primaryButton, secondaryButton, textInput } from './SettingsPanel'
import { Turnstile } from './Turnstile'
import { HardwareSettings } from './HardwareSettings'

const link = 'text-neutral-200 underline'

/** "How to get …" steps under a key's panel title, open until the key is in. */
function HowTo({ summary, open, children }: { summary: string; open: boolean; children: ReactNode }) {
  return (
    <details open={open} className="group px-5 pb-4 text-sm text-neutral-400">
      <summary className="cursor-pointer select-none text-neutral-300 hover:text-neutral-100">{summary}</summary>
      <ol className="mt-2 list-decimal space-y-1 pl-5">{children}</ol>
    </details>
  )
}

/**
 * The TMDB key, and in Settings (`trailers`) the Trailers from YouTube
 * checkbox under it (PLAN-TRAILERS.md §6.1); setup asks only for the key.
 */
export function TMDBSection({ onSaved, trailers = false }: { onSaved?: () => void; trailers?: boolean }) {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const [key, setKey] = useState('')
  const [test, setTest] = useState<{ ok: boolean; message: string } | null>(null)
  const s = settings.data
  return (
    <Panel
      title="Metadata (TMDB)"
      description={
        <>
          Titles, artwork and episode details come from The Movie Database (TMDB). Without a key the server still finds your files, but can&rsquo;t tell what they
          are. Keys are free for personal use.
        </>
      }
      actions={
        s && (s.tmdbConfigured ? <StatusPill tone="ok">{s.tmdbKeyHint ? `Key ${s.tmdbKeyHint}` : 'Key saved'}</StatusPill> : <StatusPill tone="warn">No key yet; matching is off</StatusPill>)
      }
    >
      {s && (
        <HowTo summary="How to get a TMDB key" open={!s.tmdbConfigured}>
          <li>
            Create a free account at{' '}
            <a href="https://www.themoviedb.org/signup" target="_blank" rel="noreferrer" className={link}>
              themoviedb.org/signup
            </a>{' '}
            and confirm your email.
          </li>
          <li>
            Open{' '}
            <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noreferrer" className={link}>
              Settings › API
            </a>{' '}
            and request a key: pick Developer, accept the terms and describe it as a personal media server.
          </li>
          <li>Copy the API Key (or the longer API Read Access Token), paste it below, then Test and Save.</li>
        </HowTo>
      )}
      <form
        className="flex flex-wrap items-center gap-2 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          update.mutate({ tmdbApiKey: key }, { onSuccess: () => { setKey(''); setTest(null); onSaved?.() } })
        }}
      >
        <input
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder={s?.tmdbConfigured ? 'Paste a new key to replace it' : 'API key or read access token'}
          aria-label="TMDB API key"
          autoComplete="off"
          className={`${textInput} min-w-0 flex-1 basis-64 font-mono`}
        />
        <button type="button" disabled={!key} onClick={() => void testTMDB(key).then(setTest).catch((e: Error) => setTest({ ok: false, message: e.message }))} className={secondaryButton}>
          Test
        </button>
        <button type="submit" disabled={!key || update.isPending} className={primaryButton}>
          Save
        </button>
        {s?.tmdbConfigured && (
          <button type="button" onClick={() => update.mutate({ tmdbApiKey: '' })} className="min-h-[40px] px-2 text-sm text-neutral-400 hover:text-red-400">
            Remove key
          </button>
        )}
      </form>
      {test && <p className={`px-5 pb-4 text-sm ${test.ok ? 'text-emerald-400' : 'text-red-400'}`}>{test.message}</p>}
      {trailers && s && (
        <CheckRow
          label="Trailers from YouTube"
          hint={
            <>
              Movie pages offer the trailer TMDB lists, played in YouTube&rsquo;s player. Google sees the viewer&rsquo;s address while one plays. Trailer files in your
              movie folders play either way.
            </>
          }
          checked={s.trailersYouTube !== false}
          onChange={(on) => update.mutate({ trailersYouTube: on })}
        />
      )}
      {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
    </Panel>
  )
}

/**
 * The OpenSubtitles account downloads count against. There is no API key to
 * enter: the terms allow one key per application, and release builds carry
 * WatchMyMedia's.
 */
export function OpenSubtitlesSection({ onSaved }: { onSaved?: () => void }) {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const s = settings.data?.subtitles
  if (!s) return null
  if (!s.available) {
    return (
      <Panel title="OpenSubtitles" actions={<StatusPill tone="off">Not in this build</StatusPill>}>
        <p className="px-5 pb-5 text-sm text-neutral-400">
          This build of WatchMyMedia has no OpenSubtitles API key, so it can&rsquo;t search for subtitles online. Release builds (the Docker image, the downloads and the Mac
          app) have one; subtitles inside your files still work.
        </p>
      </Panel>
    )
  }
  const a = s.account
  return (
    <Panel
      title="OpenSubtitles account"
      description={
        <>
          Players find subtitles on OpenSubtitles.com. Downloads count against this account: 20 a day on a free account, up to 1000 on VIP. Without one, the server gets
          5 a day. No API key is needed; WatchMyMedia brings its own.
        </>
      }
      actions={
        s.username ? (
          a ? (
            <StatusPill tone="ok">Signed in as {s.username}</StatusPill>
          ) : (
            <StatusPill tone="warn">Sign-in failed</StatusPill>
          )
        ) : (
          <StatusPill tone="off">Not signed in</StatusPill>
        )
      }
    >
      <HowTo summary="How to get an OpenSubtitles account" open={!s.username}>
        <li>
          Sign up for free at{' '}
          <a href="https://www.opensubtitles.com/users/sign_up" target="_blank" rel="noreferrer" className={link}>
            opensubtitles.com
          </a>{' '}
          and confirm your email.
        </li>
        <li>Enter that username and password below. The server checks them with OpenSubtitles before saving.</li>
        <li>For more than 20 downloads a day, a VIP subscription on the same site raises the account&rsquo;s limit to 1000.</li>
      </HowTo>
      {a && (
        <dl>
          <Fact label="Account">
            {a.level}
            {a.vip ? ' · VIP' : ''}
          </Fact>
          <Fact label="Downloads today">
            {a.remainingDownloads >= 0 ? `${a.remainingDownloads} of ${a.allowedDownloads} left` : `${a.allowedDownloads} a day`}
            {a.resetAt ? ` · resets ${new Date(a.resetAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : ''}
          </Fact>
        </dl>
      )}
      {s.error && <p className="px-5 pb-3 text-sm text-red-400">{s.error}</p>}
      <form
        className="flex flex-wrap items-center gap-2 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          update.mutate(
            { subtitles: { username, password } },
            {
              onSuccess: () => {
                setUsername('')
                setPassword('')
                onSaved?.()
              },
            },
          )
        }}
      >
        <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={s.username || 'Username'} aria-label="OpenSubtitles username" autoComplete="off" className={`${textInput} min-w-0 flex-1 basis-40`} />
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="Password" aria-label="OpenSubtitles password" autoComplete="new-password" className={`${textInput} min-w-0 flex-1 basis-40`} />
        <button type="submit" disabled={!username || !password || update.isPending} className={primaryButton}>
          {s.username ? 'Change' : 'Sign in'}
        </button>
        {s.username && (
          <button type="button" onClick={() => update.mutate({ subtitles: { username: '' } })} className="min-h-[40px] px-2 text-sm text-neutral-400 hover:text-red-400">
            Sign out
          </button>
        )}
      </form>
      {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
    </Panel>
  )
}

/**
 * The admin's own MDBList key, for lists that follow IMDb's, Rotten
 * Tomatoes' and Letterboxd's charts (PLAN-ROWS.md §3,
 * design/mockups/web/SettingsLists). Write-only, like the TMDB key: the
 * server checks it with MDBList before keeping it. A stored key MDBList
 * has since refused shows as the source not being ready.
 */
export function MDBListSection() {
  const settings = useQuery(settingsQuery)
  const sources = useQuery(mediaListSourcesQuery)
  const lists = useQuery(mediaListsQuery)
  const update = useUpdateSettings()
  const [key, setKey] = useState('')
  const s = settings.data
  const on = !!s?.listsMdblistConfigured
  const mdblist = sources.data?.sources.find((x) => x.kind === 'mdblist')
  const refused = on && !!mdblist && !mdblist.ready
  const using = (lists.data?.items ?? []).filter((l) => l.source.kind === 'mdblist')
  const household = using.filter((l) => l.owner === 'everyone').length
  return (
    <Panel
      title="MDBList"
      description="MDBList publishes lists that follow IMDb’s, Rotten Tomatoes’ and Letterboxd’s charts, and lists people make there. The server reads them with your own MDBList account’s key, one for the household. Accounts are free, with a daily limit. Rows made from it say From MDBList."
      actions={
        s &&
        (refused ? (
          <StatusPill tone="warn">Key refused</StatusPill>
        ) : on ? (
          <StatusPill tone="ok">{s.listsMdblistKeyHint ? `Key ${s.listsMdblistKeyHint}` : 'Key saved'}</StatusPill>
        ) : (
          <StatusPill tone="off">No key</StatusPill>
        ))
      }
    >
      {s && (
        <HowTo summary="How to get an MDBList key" open={!on}>
          <li>
            Make a free account at{' '}
            <a href="https://mdblist.com" target="_blank" rel="noreferrer" className={link}>
              mdblist.com
            </a>{' '}
            and sign in.
          </li>
          <li>Open your preferences there and copy your API key.</li>
          <li>Paste it below, then Save. The server checks it with MDBList first.</li>
        </HowTo>
      )}
      <form
        className="flex flex-wrap items-center gap-2 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          update.mutate({ listsMdblistKey: key.trim() }, { onSuccess: () => setKey('') })
        }}
      >
        <input
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder={on ? 'Paste a new key to replace it' : 'API key'}
          aria-label="MDBList API key"
          autoComplete="off"
          maxLength={200}
          className={`${textInput} min-w-0 flex-1 basis-64 font-mono`}
        />
        <button type="submit" disabled={!key.trim() || update.isPending} className={primaryButton}>
          {update.isPending ? 'Checking…' : 'Save'}
        </button>
        {on && (
          <button type="button" disabled={update.isPending} onClick={() => update.mutate({ listsMdblistKey: '' })} className="min-h-[40px] px-2 text-sm text-neutral-400 hover:text-red-400">
            Remove key
          </button>
        )}
      </form>
      {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
      {refused && (
        <p role="status" className="border-t border-line px-5 py-3 text-sm leading-normal text-orange-300">
          {sentence(mdblist?.reason) || 'MDBList refused the key.'} Lists from MDBList keep the titles they had until it accepts a key again: paste a new one above.
        </p>
      )}
      <p className="border-t border-line px-5 py-3 text-[13px] text-neutral-400">
        {on
          ? `${using.length} ${using.length === 1 ? 'list' : 'lists'} you can see ${using.length === 1 ? 'uses' : 'use'} MDBList, ${household} of them the household’s. Each is fetched once a day.`
          : 'Without a key, Make a list doesn’t offer MDBList, and says an admin can add a key here.'}
      </p>
    </Panel>
  )
}

/** The name TV and phone apps show for this server, in their server list and on the network. */
export function ServerNameSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const s = settings.data
  const [name, setName] = useState<string | null>(null)
  const value = name ?? s?.serverName ?? ''
  return (
    <Panel title="Server name" description="What the WatchMyMedia apps on your TVs and phones call this server when they find it on your network. Leave it empty to use the computer’s name.">
      <form
        className="flex flex-wrap items-center gap-2 px-5 pb-5"
        onSubmit={(e) => {
          e.preventDefault()
          update.mutate({ serverName: value.trim() }, { onSuccess: () => setName(null) })
        }}
      >
        <input
          value={value}
          onChange={(e) => setName(e.target.value)}
          placeholder={s?.serverNameDefault ?? ''}
          maxLength={63}
          aria-label="Server name"
          className={`${textInput} min-w-0 flex-1 sm:max-w-sm`}
        />
        <button type="submit" disabled={name === null || update.isPending} className={primaryButton}>
          Save
        </button>
        {update.isSuccess && name === null && <span className="text-sm text-emerald-400">Saved.</span>}
      </form>
      {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
    </Panel>
  )
}

export function TranscodeSection() {
  return <div className="space-y-6"><HardwareSettings /><SeekThumbnailsPanel /></div>
}

/** Seek thumbnails: the switch, how far the job has got, what it takes on disk, and Delete thumbnails (the SettingsThumbnails board). */
function SeekThumbnailsPanel() {
  const status = useQuery({ ...trickplayQuery, refetchInterval: (q) => pollInterval(q.state.data) })
  const update = useUpdateSettings()
  const remove = useDeleteTrickplay()
  const switchId = useId()
  const s = status.data
  const state = s ? thumbnailState(s) : undefined
  const copy = s && state ? thumbnailCopy(state, s) : undefined
  const error = update.error ?? remove.error ?? status.error
  return (
    <Panel
      title="Seek thumbnails"
      description="A small picture of the video shows above the scrubber while someone seeks. The server makes them in the background, newest files first."
    >
      <Row label={<span id={switchId}>Make seek thumbnails</span>} hint="For every file, at low priority. Paused while anything is playing.">
        <button
          type="button"
          role="switch"
          aria-checked={s?.enabled ?? false}
          aria-labelledby={switchId}
          disabled={!s || update.isPending}
          onClick={() => s && update.mutate({ trickplayEnabled: !s.enabled })}
          className={`relative my-1.5 h-8 w-[52px] shrink-0 rounded-full disabled:opacity-60 ${s?.enabled ? 'bg-accent' : 'bg-line-2 ring-1 ring-inset ring-neutral-700'}`}
        >
          <span aria-hidden="true" className={`absolute top-1 h-6 w-6 rounded-full ${s?.enabled ? 'left-6 bg-ground' : 'left-1 bg-neutral-400'}`} />
        </button>
      </Row>
      {s && state && copy && (
        <>
          <div className="flex flex-col gap-2.5 border-t border-line px-5 py-4">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <StatusPill tone={copy.tone}>{copy.pill}</StatusPill>
              <span className="text-sm tabular-nums text-neutral-200">{filesLine(s)}</span>
            </div>
            {state !== 'done' && (
              <div
                className="relative h-1.5 overflow-hidden rounded-full bg-white/10"
                role="progressbar"
                aria-label="Files with seek thumbnails"
                aria-valuemin={0}
                aria-valuemax={s.files}
                aria-valuenow={s.done}
              >
                <span className={`absolute inset-y-0 left-0 rounded-full ${state === 'working' ? 'bg-accent' : 'bg-neutral-600'}`} style={{ width: `${percentDone(s)}%` }} />
              </div>
            )}
            {state === 'working' && s.current && (
              <p className="text-sm text-neutral-400">
                Now: <span className="text-neutral-200">{currentTitle(s.current)}</span> · sheet {s.current.sheet} of {s.current.sheets}
              </p>
            )}
            {copy.note && <p className="text-sm text-neutral-400">{copy.note}</p>}
          </div>
          <dl>
            <Fact label="On disk">
              {formatBytes(s.bytes)} <span className="text-neutral-400">· about 2 MB an hour of video</span>
            </Fact>
            {s.failed > 0 && (
              <Fact label="Couldn’t be made">
                {plural(s.failed, 'file')} <span className="text-neutral-400">· tried again when the file changes</span>
              </Fact>
            )}
            <Fact label="Kept in">
              <code className="text-[13px]">trickplay</code> in the data folder <span className="text-neutral-400">· not in backups, as they can be made again</span>
            </Fact>
          </dl>
          <Row label="Delete thumbnails" hint={deleteHint(s.enabled)}>
            <button
              type="button"
              disabled={remove.isPending || s.bytes === 0}
              onClick={() => remove.mutate()}
              className="min-h-[44px] rounded-md border border-line-2 bg-surface px-[18px] text-sm font-medium text-red-400 hover:bg-surface-2 disabled:opacity-50"
            >
              Delete thumbnails
            </button>
          </Row>
        </>
      )}
      {error && <p className="border-t border-line px-5 py-3 text-sm text-red-400">{error.message}</p>}
    </Panel>
  )
}

const SCAN_INTERVALS: readonly (readonly [number, string])[] = [
  [5, 'Every 5 minutes'],
  [15, 'Every 15 minutes'],
  [30, 'Every 30 minutes'],
  [60, 'Every hour'],
  [360, 'Every 6 hours'],
  [1440, 'Once a day'],
]

/** How often every library is read in full, to find what the watcher misses. */
export function ScanIntervalSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const value = settings.data?.scanIntervalMinutes
  // An interval set in config.yaml may be none of these.
  const options = value && !SCAN_INTERVALS.some(([m]) => m === value) ? [...SCAN_INTERVALS, [value, `Every ${value} minutes`] as const] : SCAN_INTERVALS
  return (
    <Panel>
      <Row
        label="Scan every library"
        hint="Where the server can watch a folder, new files show up within seconds. This scan finds the rest: network shares written to by other computers, and folders beyond the system’s limit on watches. Each scan reads every folder, which can wake sleeping drives."
        htmlFor="scan-interval"
      >
        <select
          id="scan-interval"
          value={value ?? 15}
          disabled={!settings.data}
          onChange={(e) => update.mutate({ scanIntervalMinutes: Number(e.target.value) })}
          className={`${textInput} px-2`}
        >
          {options.map(([m, l]) => (
            <option key={m} value={m}>
              {l}
            </option>
          ))}
        </select>
      </Row>
      {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
    </Panel>
  )
}

export function BackupSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const create = useCreateBackup()
 const download = useDownloadBackup()
  const s = settings.data
  const total = s?.backups.reduce((n, b) => n + b.size, 0) ?? 0
  return (
    <div className="space-y-6">
      <Panel>
        <NumberSetting
          label="Nightly backups to keep"
          hint={
            <>
              0 turns nightly backups off. Written to <code className="break-all text-neutral-300">{s?.dataDir}/backups</code>.
            </>
          }
          value={s?.backupKeep ?? 7}
          max={365}
          onSave={(v) => update.mutate({ backupKeep: v })}
        />
        {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
      </Panel>
      <Panel
        title="On this server"
        description={s && (s.backups.length > 0 ? `${s.backups.length} backups · ${formatBytes(total)}` : 'No backups yet.')}
        actions={
          <>
            <button type="button" disabled={download.isPending} onClick={() => download.mutate()} className={secondaryButton}>
              {download.isPending ? 'Preparing…' : 'Download backup'}
            </button>
            <button type="button" disabled={create.isPending} onClick={() => create.mutate()} className={primaryButton}>
              {create.isPending ? 'Backing up…' : 'Back up now'}
            </button>
          </>
        }
      >
        {download.isError && visibleConfirmationError(download.error) && <p role="alert" className="px-5 pb-4 text-sm text-warn">{download.error.message}</p>}
      {create.isError && visibleConfirmationError(create.error) && <p className="px-5 pb-3 text-sm text-red-400">{create.error.message}</p>}
        {s && s.backups.length > 0 && (
          <ul>
            {s.backups.map((b) => (
              <li key={b.name} className="flex flex-wrap justify-between gap-x-4 gap-y-1 border-t border-line px-5 py-2.5">
                <span className="break-all font-mono text-xs leading-5 text-neutral-200">{b.name}</span>
                <span className="text-sm text-neutral-400">
                  {formatBytes(b.size)} · {relativeTime(b.modTime)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <Panel title="Restoring">
        <ol className="list-decimal space-y-0.5 pb-4 pl-10 pr-5 text-sm text-neutral-300">
          <li>Stop the server.</li>
          <li>Unpack the archive into the data directory.</li>
          <li>Start the server again. Artwork is fetched again as it is needed.</li>
        </ol>
      </Panel>
    </div>
  )
}

export function ActivitySection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const a = settings.data?.activity
  if (!a) return null
  const save = (patch: Partial<ActivitySettings>) => update.mutate({ activity: { ...a, ...patch } })
  return (
    <div className="space-y-6">
      <Panel title="What is kept">
        <NumberSetting
          label="Keep plays longer than"
          unit="seconds"
          hint="Shorter plays are dropped unless they reach the credits; 0 keeps every play."
          value={a.minPlaySeconds}
          max={3600}
          onSave={(v) => save({ minPlaySeconds: v })}
        />
        <NumberSetting label="Keep history for" unit="days" hint="0 keeps it forever." value={a.retentionDays} max={36500} onSave={(v) => save({ retentionDays: v })} />
        <CheckRow
          label="Group resumed plays"
          hint="A play that picks up where an unfinished one stopped joins its history row."
          checked={a.groupPlays}
          onChange={(on) => save({ groupPlays: on })}
        />
        <CheckRow label="Trim statistics with history" hint="Off, daily totals stay when old plays are deleted." checked={a.trimStats} onChange={(on) => save({ trimStats: on })} />
      </Panel>
      <Panel title="Who sees what">
        <CheckRow
          label="Let everyone see what is playing now"
          hint="Titles and names only, without addresses. History and statistics stay private."
          checked={a.household}
          onChange={(on) => save({ household: on })}
        />
      </Panel>
      {update.isError && visibleConfirmationError(update.error) && <p className="text-sm text-red-400">{update.error.message}</p>}
    </div>
  )
}

/** A number saved when the field loses focus or Enter is pressed. */
function NumberSetting({ label, unit, hint, value, max, onSave }: { label: string; unit?: string; hint: ReactNode; value: number; max: number; onSave: (v: number) => void }) {
  const id = useId()
  const [draft, setDraft] = useState<string | null>(null)
  const commit = () => {
    if (draft === null) return
    const v = Math.min(max, Math.max(0, Math.round(Number(draft) || 0)))
    setDraft(null)
    if (v !== value) onSave(v)
  }
  return (
    <Row label={label} hint={hint} htmlFor={id}>
      <input
        id={id}
        type="number"
        min={0}
        max={max}
        value={draft ?? value}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && commit()}
        className={`${textInput} w-24 text-right`}
      />
      {unit && <span className="w-14 text-sm text-neutral-400">{unit}</span>}
    </Row>
  )
}

/**
 * Cloudflare Turnstile on the sign-in and setup pages. The server checks the
 * secret key with Cloudflare before saving it; the site key can only be
 * checked by trying the widget, which needs a reload once it is on.
 */
export function SignInProtectionSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const ts = settings.data?.turnstile
  const [site, setSite] = useState<string | null>(null)
  const [secret, setSecret] = useState('')
  const [trying, setTrying] = useState(false)
  const [check, setCheck] = useState<{ ok: boolean; message: string } | null>(null)
  const siteValue = site ?? ts?.siteKey ?? ''
  const status = !ts ? null : ts.disabled ? (
    <StatusPill tone="warn">Off in config</StatusPill>
  ) : ts.enabled ? (
    <StatusPill tone="ok">On</StatusPill>
  ) : (
    <StatusPill tone="off">Off</StatusPill>
  )
  return (
    <div className="space-y-6">
      <Panel
        title="Turnstile keys"
        actions={status}
        description={
          <>
            It&rsquo;s free: in the{' '}
            <a href="https://dash.cloudflare.com/?to=/:account/turnstile" target="_blank" rel="noreferrer" className="text-neutral-200 underline">
              Cloudflare dashboard
            </a>{' '}
            add a Turnstile widget for the host names you open this server at (for example <code className="text-neutral-200">{window.location.hostname}</code>), then paste its site key and secret key here.
          </>
        }
      >
        {ts?.disabled && (
          <p className="px-5 pb-3 text-sm text-warn">
            <code>auth.turnstile.disabled</code> is set in config or the environment, so it stays off whatever keys are saved.
          </p>
        )}
        {ts?.fromConfig ? (
          <p className="px-5 pb-5 text-sm text-neutral-400">
            {ts.enabled ? 'On, with keys' : 'The keys come'} from <code>auth.turnstile</code> in config.yaml or <code>WATCHMYMEDIA_AUTH_TURNSTILE_*</code> in the environment; change them there.
          </p>
        ) : (
          <form
            className="flex flex-wrap items-end gap-3 px-5 pb-5"
            onSubmit={(e) => {
              e.preventDefault()
              update.mutate(
                { turnstile: { siteKey: siteValue.trim(), secretKey: secret.trim() || undefined } },
                {
                  onSuccess: () => {
                    setSite(null)
                    setSecret('')
                    setCheck(null)
                  },
                },
              )
            }}
          >
            <label className="min-w-0 flex-1 basis-60">
              <span className="mb-1.5 block text-sm text-neutral-400">Site key</span>
              <input value={siteValue} onChange={(e) => setSite(e.target.value)} autoComplete="off" required className={`${textInput} w-full font-mono`} />
            </label>
            <label className="min-w-0 flex-1 basis-60">
              <span className="mb-1.5 block text-sm text-neutral-400">Secret key</span>
              <input
                type="password"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                placeholder={ts?.secretKeyHint ? `Leave empty to keep ${ts.secretKeyHint}` : ''}
                autoComplete="off"
                required={!ts?.secretKeyHint}
                className={`${textInput} w-full font-mono`}
              />
            </label>
            <button type="submit" disabled={update.isPending || !siteValue.trim()} className={primaryButton}>
              {update.isPending ? 'Checking…' : ts?.siteKey ? 'Save' : 'Turn on'}
            </button>
          </form>
        )}
        {update.isError && visibleConfirmationError(update.error) && <p className="px-5 pb-4 text-sm text-red-400">{update.error.message}</p>}
      </Panel>

      {ts?.enabled && (
        <Panel tone="warn">
          <div className="flex flex-wrap items-center gap-4 px-5 py-4">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-warn">
              <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
              <path d="M12 9v4" />
              <path d="M12 17h.01" />
            </svg>
            <div className="min-w-0 flex-1 basis-80">
              <div className="font-medium">Try the widget before you sign out</div>
              <p className="mt-0.5 text-sm text-neutral-300">
                If its site key doesn&rsquo;t allow this host name, nobody can sign in: turn it off here, or set <code>WATCHMYMEDIA_AUTH_TURNSTILE_DISABLED=true</code> and restart the server.
              </p>
            </div>
            {!trying && (
              <button type="button" onClick={() => setTrying(true)} className="min-h-[40px] rounded-md border border-warn px-4 text-sm font-medium text-orange-300 hover:bg-orange-950/40">
                Try it
              </button>
            )}
          </div>
          {trying && (
            <div className="px-5 pb-4">
              <Turnstile
                siteKey={ts.siteKey ?? ''}
                action="settings"
                onToken={(token) => {
                  if (token) void checkTurnstile(token).then(setCheck).catch((e: Error) => setCheck({ ok: false, message: e.message }))
                }}
                onError={(code) =>
                  setCheck({
                    ok: false,
                    message: `The widget failed (Cloudflare error ${code}). Check the site key, and that its widget in Cloudflare lists ${window.location.hostname} as a host name.`,
                  })
                }
              />
            </div>
          )}
          {check && <p className={`px-5 pb-4 text-sm ${check.ok ? 'text-emerald-400' : 'text-red-400'}`}>{check.message}</p>}
        </Panel>
      )}

      {ts?.siteKey && !ts.fromConfig && (
        <Panel>
          <Row label="Turn off sign-in protection" hint="Removes the keys. TV and phone apps can sign in with a password again.">
            <button
              type="button"
              disabled={update.isPending}
              onClick={() => update.mutate({ turnstile: { siteKey: '' } }, { onSuccess: () => { setSite(null); setTrying(false); setCheck(null) } })}
              className="min-h-[40px] rounded-md border border-red-900 px-4 text-sm font-medium text-red-300 hover:bg-red-950/40 disabled:opacity-50"
            >
              Turn off
            </button>
          </Row>
        </Panel>
      )}
    </div>
  )
}
