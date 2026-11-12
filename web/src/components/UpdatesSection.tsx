import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../api/queries'
import { updateStatusQuery, useCheckForUpdates, type UpdateStatus } from '../api/settings'
import { formatBytes, formatDate, relativeTime } from '../lib/format'
import { dockerUpdateSteps, updateDownload } from '../lib/updates'
import { Panel, primaryButton, secondaryButton, StatusPill } from './SettingsPanel'

/**
 * Settings › General: Check for updates. The server fetches the release list
 * only when the button is pressed (PLAN-RELEASES.md §5); opening the page
 * shows what the last check found. Hidden when updates.enabled is off.
 */
export function UpdatesSection() {
  const status = useQuery(updateStatusQuery)
  const server = useQuery(serverQuery)
  const check = useCheckForUpdates()
  const s = status.data
  if (!s?.enabled) return null
  return (
    <Panel
      title="Updates"
      description={`Checking asks ${s.source || 'the release site'} for the list of releases and sends nothing but this server’s version. It runs only when you press the button.`}
      actions={
        <>
          {pill(s)}
          <button type="button" onClick={() => check.mutate()} disabled={check.isPending} className={secondaryButton}>
            {check.isPending ? 'Checking…' : 'Check for updates'}
          </button>
        </>
      }
    >
      <div className="space-y-3 px-5 pb-5 text-sm">
        {check.isError && <p className="text-red-400">{check.error.message}</p>}
        {s.error && (
          <p className="text-red-400">
            {sentence(s.error)}
            {s.checked && <span className="text-neutral-400"> The last check that worked was {relativeTime(s.checked)}.</span>}
          </p>
        )}
        {s.checked && <Answer s={s} host={server.data?.host} />}
      </div>
    </Panel>
  )
}

function pill(s: UpdateStatus) {
  if (!s.checked || s.development) return null
  return s.updateAvailable ? <StatusPill tone="warn">{s.latest} available</StatusPill> : <StatusPill tone="ok">Up to date</StatusPill>
}

function Answer({ s, host }: { s: UpdateStatus; host?: string }) {
  const checked = relativeTime(s.checked)
  if (!s.latest) return <p className="text-neutral-300">The release list has no releases for this server yet (checked {checked}).</p>
  if (s.development) {
    return (
      <p className="text-neutral-300">
        This is a development build, so it is not offered updates. The latest release is {s.latest} (checked {checked}).
      </p>
    )
  }
  if (!s.updateAvailable) return <p className="text-neutral-300">This is the latest release (checked {checked}).</p>
  return (
    <>
      <p className="text-neutral-100">
        WatchMyMedia {s.latest} is available{s.date && `, released ${formatDate(s.date)}`}.
      </p>
      {s.notes && <ReleaseNotes notes={s.notes} />}
      <HowToUpdate s={s} host={host} />
    </>
  )
}

/** The changelog's Markdown list, one item per "- " line; other lines are paragraphs. */
function ReleaseNotes({ notes }: { notes: string }) {
  const lines = notes.split('\n').map((l) => l.trim()).filter(Boolean)
  const items = lines.filter((l) => /^[-*] /.test(l))
  return (
    <div className="rounded-md border border-line bg-ground px-4 py-3 text-neutral-300">
      {lines.filter((l) => !/^[-*] /.test(l)).map((l) => <p key={l}>{l}</p>)}
      {items.length > 0 && (
        <ul className="list-disc space-y-1 pl-5">
          {items.map((l) => <li key={l}>{l.slice(2)}</li>)}
        </ul>
      )}
    </div>
  )
}

function HowToUpdate({ s, host }: { s: UpdateStatus; host?: string }) {
  if (s.install === 'docker') {
    const steps = dockerUpdateSteps(host)
    return (
      <div className="space-y-2">
        <p className="text-neutral-300">{steps.text}</p>
        {steps.command && <code className="block select-all overflow-x-auto whitespace-nowrap rounded-md border border-line bg-ground px-3 py-2 font-mono text-[13px] text-neutral-100">{steps.command}</code>}
      </div>
    )
  }
  if (!s.download) return <p className="text-neutral-400">The release list has no download for this platform.</p>
  const size = s.download.size ? ` (${formatBytes(s.download.size)})` : ''
  const { label, hint } = updateDownload(s.install)
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
      <a href={s.download.url} className={`${primaryButton} inline-flex items-center`} rel="noreferrer">
        {label}
        {size}
      </a>
      {hint && <span className="text-neutral-400">{hint}</span>}
    </div>
  )
}

/** Server errors arrive as fragments ("could not reach the release list at …"). */
function sentence(text: string): string {
  const t = text.charAt(0).toUpperCase() + text.slice(1)
  return /[.!?]$/.test(t) ? t : `${t}.`
}
