import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthQuery, librariesQuery, serverQuery } from '../../../api/queries'
import { useEventState } from '../../../api/events'
import { settingsQuery, updateStatusQuery, useCheckForUpdates, useUpdateSettings, type UpdateStatus } from '../../../api/settings'
import type { components } from '../../../api/schema'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { CommandLine } from '../../../components/CommandLine'
import { ConfirmDialog, LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Card, Field, HubPage, ItemRow, LoadingRows, Message, NextStep, Pill, SaveBar, SaveNote, Section, Switch, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { formatBytes, formatDate, relativeTime } from '../../../lib/format'
import { generalLede, generalNextStep, removeKeyWords, serverNameError, tmdbDoneLine } from '../../../lib/generalSettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { dockerUpdateSteps, packageUpdateSteps, updateDownload } from '../../../lib/updates'
import { clearNotice, peekNotice } from './notice'

type Settings = components['schemas']['Settings']

/** Settings › Server › General: Add a TMDB key is the Next step when there is none (board gen-hub). */
export function GeneralHub() {
  const settings = useQuery(settingsQuery)
  const health = useQuery(healthQuery)
  const libraries = useQuery(librariesQuery)
  const { connected } = useEventState()
  const go = useGo()
  const [notice, setNotice] = useState(peekNotice)
  useEffect(() => clearNotice(), [])
  const s = settings.data
  const lede = generalLede(health.data?.version, libraries.data?.items.length, connected)
  const crumbs = settingsCrumbs('general')
  const running = health.data ? (health.data.status === 'ok' ? { tone: 'ok' as const, label: 'Running' } : { tone: 'warn' as const, label: health.data.status }) : undefined
  if (!s) {
    return (
      <HubPage crumbs={crumbs} title="General" lede={lede} pill={running}>
        {settings.isError ? <Message tone="err" title="Couldn’t load the server’s settings">{errorText(settings.error)}</Message> : <LoadingRows rows={4} />}
      </HubPage>
    )
  }
  return (
    <HubPage crumbs={crumbs} title="General" lede={lede} pill={running} banner={notice ? <Message tone="ok">{notice}</Message> : undefined}>
      {generalNextStep(s) === 'tmdb' && (
        <NextStep title="Add a TMDB key" action={<Button variant="primary" onClick={() => go(settingsHref('general', 'tmdb'))}>Add a TMDB key</Button>}>
          Without one, files play but have no posters, details or trailers, and nothing can be matched or requested. The key is free.
        </NextStep>
      )}
      <ServerNameSection s={s} />
      <UpdatesSection />
      <TmdbSection s={s} onRemoved={setNotice} />
      <TrailersSection s={s} />
      <CastingSection cast={s.cast} />
    </HubPage>
  )
}

/** The server's name: a typed field, so a draft with the save bar. */
function ServerNameSection({ s }: { s: Settings }) {
  const update = useUpdateSettings()
  const [draft, setDraft] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const value = draft ?? s.serverName
  const dirty = draft !== null && draft.trim() !== s.serverName
  const mistake = serverNameError(value)
  const save = () => {
    if (mistake) return
    update.mutate({ serverName: value.trim() }, { onSuccess: () => { setDraft(null); setSaved(true) } })
  }
  return (
    <Section title="Server name">
      <LeaveCheck when={dirty} what="The server name you typed isn’t saved." />
      <SaveBar dirty={dirty} saving={update.isPending} onDiscard={() => { setDraft(null); update.reset() }} onSave={save}
        error={update.isError && visibleConfirmationError(update.error) ? errorText(update.error) : undefined} />
      <Card padded>
        <div className="max-w-md">
          <Field label="Name" hint="What the apps call this server when they find it on your network. Empty uses the computer’s name. Up to 63 characters." error={mistake}>
            {(p) => <input {...p} value={value} maxLength={63} placeholder={s.serverNameDefault} onChange={(e) => { setSaved(false); setDraft(e.target.value) }} className={fieldInput} />}
          </Field>
        </div>
        {saved && !dirty && <p role="status" className="settings-fade mt-2 text-sm text-emerald-300">✓ Saved. The apps on your network see the new name within a minute.</p>}
      </Card>
    </Section>
  )
}

function TmdbSection({ s, onRemoved }: { s: Settings; onRemoved: (line: string) => void }) {
  const update = useUpdateSettings()
  const go = useGo()
  const [asking, setAsking] = useState(false)
  const words = removeKeyWords(s.tmdbKeyFromConfig)
  const fromConfigOnly = !s.tmdbKeyHint && s.tmdbKeyFromConfig
  // Stays mounted after the key is removed, so the dialog's result reaches the page.
  return (
    <>
    {s.tmdbConfigured && <Section title="Metadata">
      <Card>
        <ItemRow
          title={fromConfigOnly ? 'TMDB key from config.yaml' : `TMDB key saved${s.tmdbKeyHint ? ` · ${s.tmdbKeyHint}` : ''}`}
          pill={<Pill tone="ok">Matching on</Pill>}
          detail="Titles, posters, cast and trailers come from TMDB."
          control={<>
            <Button small onClick={() => go(settingsHref('general', 'tmdb'))}>Replace key</Button>
            <Button small variant="danger" onClick={() => { update.reset(); setAsking(true) }}>Remove key…</Button>
          </>}
        />
      </Card>
    </Section>}
      {asking && (
        <ConfirmDialog
          title="Remove the TMDB key?"
          stops={words.stops}
          stays={words.stays}
          confirmLabel="Remove key"
          busy={update.isPending}
          error={update.isError && visibleConfirmationError(update.error) ? errorText(update.error) : undefined}
          onClose={() => setAsking(false)}
          onConfirm={() => update.mutate({ tmdbApiKey: '' }, { onSuccess: () => { setAsking(false); onRemoved(tmdbDoneLine('removed', s.tmdbKeyFromConfig)) } })}
        >
          {words.config && <Message tone="warn" className="mt-4" title={words.config.title}>{words.config.body}</Message>}
        </ConfirmDialog>
      )}
    </>
  )
}

function TrailersSection({ s }: { s: Settings }) {
  const update = useUpdateSettings()
  const save = useInstantSave()
  const on = s.trailersYouTube !== false
  return (
    <Section title="Trailers">
      <Card>
        <ItemRow
          title="Trailers from YouTube"
          detail={save.state.status === 'idle'
            ? 'Movie pages offer the trailer TMDB lists, played in YouTube’s player. Google sees the viewer’s address while one plays. Trailer files in your movie folders play either way.'
            : <SaveNote state={save.state} />}
          control={<Switch label="Trailers from YouTube" checked={on} disabled={save.state.status === 'saving'}
            onChange={(next) => void save.run(() => update.mutateAsync({ trailersYouTube: next }), next ? 'Trailers are looked up every 6 hours and play from YouTube.' : 'Movie pages no longer offer YouTube trailers. Trailer files still play.')} />}
        />
      </Card>
    </Section>
  )
}

/** Check for updates: the server asks the release list only when the button is pressed. Hidden when updates.enabled is off. */
function UpdatesSection() {
  const status = useQuery(updateStatusQuery)
  const server = useQuery(serverQuery)
  const check = useCheckForUpdates()
  const s = status.data
  if (!s?.enabled) return null
  const button = <Button small busy={check.isPending} busyLabel="Checking…" onClick={() => check.mutate()}>{s.checked ? 'Check again' : 'Check for updates'}</Button>
  return (
    <Section title="Updates">
      <Card>
        <UpdateRow s={s} host={server.data?.host} control={button} />
      </Card>
      {s.error && (
        <Message tone="err" className="mt-3" title="Couldn’t check for updates">
          {s.source || 'The release site'} didn’t answer: {s.error.replace(/\.$/, '')}.{s.checked && ` The last good check was ${relativeTime(s.checked)}: ${s.updateAvailable ? `${s.latest} was out` : 'up to date'}.`}
        </Message>
      )}
      {check.isError && <Message tone="err" focus className="mt-3" title="Couldn’t check for updates">{errorText(check.error)}</Message>}
    </Section>
  )
}

function UpdateRow({ s, host, control }: { s: UpdateStatus; host?: string; control: React.ReactNode }) {
  if (!s.checked) {
    return <ItemRow title={`Version ${s.current}`} detail={`Not checked yet. Checking asks ${s.source || 'the release site'} for the list of releases and sends nothing but this server’s version.`} control={control} />
  }
  const checked = `Checked ${relativeTime(s.checked)}.`
  if (!s.latest) return <ItemRow title={`Version ${s.current}`} detail={`${checked} The release list has no releases for this server yet.`} control={control} />
  if (s.development) return <ItemRow title="Development build" detail={`${checked} A development build isn’t offered updates. The latest release is ${s.latest}.`} control={control} />
  if (!s.updateAvailable) return <ItemRow title={`Version ${s.current}`} pill={<Pill tone="ok">Up to date</Pill>} detail={checked} control={control} />
  return (
    <ItemRow title={`${s.latest} is out`} pill={<Pill tone="now">Update</Pill>} detail={`${checked}${s.date ? ` Released ${formatDate(s.date)}.` : ''}`} control={control}>
      <HowToUpdate s={s} host={host} />
      {s.notes && <ReleaseNotes notes={s.notes} />}
    </ItemRow>
  )
}

function HowToUpdate({ s, host }: { s: UpdateStatus; host?: string }) {
  if (s.install === 'docker') {
    const steps = dockerUpdateSteps(host)
    return (
      <div className="mt-1 text-sm text-neutral-400">
        {steps.command ? (
          <details>
            <summary className="cursor-pointer text-neutral-300 underline hover:text-neutral-100">This server runs in Docker: pull the new image and recreate the container. Show the commands</summary>
            <p className="mt-2">{steps.text}</p>
            <CommandLine>{steps.command}</CommandLine>
          </details>
        ) : <p>{steps.text}</p>}
      </div>
    )
  }
  const pkg = packageUpdateSteps(s.install)
  if (pkg) {
    return (
      <div className="mt-1 text-sm text-neutral-400 [overflow-wrap:anywhere]">
        <p>{pkg.text}</p>
        <CommandLine>{pkg.command}</CommandLine>
        <p className="mt-2">{pkg.after}</p>
      </div>
    )
  }
  if (!s.download) return <p className="mt-1 text-sm text-neutral-400">The release list has no download for this platform.</p>
  const { label, hint } = updateDownload(s.install)
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
      <a href={s.download.url} rel="noreferrer" className="btn-primary inline-flex min-h-[36px] items-center rounded-md px-3.5 font-semibold">
        {label}{s.download.size ? ` (${formatBytes(s.download.size)})` : ''}
      </a>
      {hint && <span className="text-neutral-400">{hint}</span>}
    </div>
  )
}

/** The changelog's Markdown list, one item per "- " line; other lines are paragraphs. */
function ReleaseNotes({ notes }: { notes: string }) {
  const lines = notes.split('\n').map((l) => l.trim()).filter(Boolean)
  const items = lines.filter((l) => /^[-*] /.test(l))
  return (
    <details className="mt-2 text-sm text-neutral-300">
      <summary className="cursor-pointer text-neutral-300 hover:text-neutral-100">What’s new</summary>
      <div className="mt-2 rounded-md border border-line bg-ground px-4 py-3">
        {lines.filter((l) => !/^[-*] /.test(l)).map((l) => <p key={l}>{l}</p>)}
        {items.length > 0 && <ul className="list-disc space-y-1 pl-5">{items.map((l) => <li key={l}>{l.slice(2)}</li>)}</ul>}
      </div>
    </details>
  )
}

/** How casting is set up. It comes from config.yaml or the environment (cast.*), so this only shows it. */
function CastingSection({ cast }: { cast: Settings['cast'] }) {
  const secure = typeof window !== 'undefined' && window.isSecureContext
  const httpOnly = cast.urls.length > 0 && cast.urls.every((u) => u.startsWith('http://'))
  return (
    <Section title="Casting">
      <Card>
        <ItemRow
          title={cast.enabled ? (cast.appId ? `On · receiver ${cast.appId}` : 'On · no receiver for browsers') : 'Off'}
          detail={<>Set in config.yaml (<code>cast.*</code>) or the environment. Change it there and restart the server.</>}
        >
          <details className="mt-2 text-sm">
            <summary className="cursor-pointer text-neutral-300 hover:text-neutral-100">Addresses</summary>
            <dl className="mt-2 grid gap-x-6 gap-y-1.5 sm:grid-cols-[10rem_minmax(0,1fr)]">
              <dt className="text-neutral-400">Address for TVs</dt>
              <dd className="break-all">{cast.url || 'None set'}</dd>
              <dt className="text-neutral-400">TVs are offered</dt>
              <dd className="break-all">{cast.urls.length ? cast.urls.join(', ') : <span className="text-warn">No address a TV can reach</span>}</dd>
              <dt className="text-neutral-400">Receiver pages</dt>
              <dd className="break-all">{cast.receiverOrigins.join(', ') || '—'}</dd>
            </dl>
          </details>
          {cast.enabled && httpOnly && <p className="mt-2 text-[13px] text-neutral-400">TVs get plain http:// addresses only. If a Chromecast can’t play from them, set cast.url to an HTTPS address it can reach, such as your reverse proxy’s domain.</p>}
          {cast.enabled && !secure && <p className="mt-2 text-[13px] text-neutral-400">Casting from a browser needs this page over HTTPS (Tailscale Serve or your domain); the phone apps cast from any address.</p>}
        </ItemRow>
      </Card>
    </Section>
  )
}
