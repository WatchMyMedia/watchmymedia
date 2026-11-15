import { visibleConfirmationError } from '../api/withConfirm'
import { useId, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, testTMDB, useCreateBackup, useDownloadBackup, useUpdateSettings } from '../api/settings'
import { formatBytes, relativeTime } from '../lib/format'
import { CheckRow, Fact, Panel, Row, StatusPill, primaryButton, secondaryButton, textInput } from './SettingsPanel'

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
