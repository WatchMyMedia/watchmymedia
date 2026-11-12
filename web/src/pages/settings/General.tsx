import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthQuery } from '../../api/queries'
import { useEventState } from '../../api/events'
import { settingsQuery } from '../../api/settings'
import { CastingSection } from '../../components/CastingSection'
import { PageHeader } from '../../components/SettingsPanel'
import { ServerNameSection, TMDBSection } from '../../components/SettingsSections'
import { UpdatesSection } from '../../components/UpdatesSection'
import { ServerOnly } from './SettingsLayout'

/** Settings › General: how the server is doing, updates, what apps call it, and its metadata key. */
export function GeneralSettings() {
  return (
    <>
      <PageHeader title="General">How this server is doing and whether a newer release is out, what apps call it, where its titles and artwork come from, and how casting is set up.</PageHeader>
      <ServerOnly>
        <div className="space-y-6">
          <ServerStatus />
          <UpdatesSection />
          <ServerNameSection />
          <TMDBSection trailers />
          <Casting />
        </div>
      </ServerOnly>
    </>
  )
}

function Casting() {
  const settings = useQuery(settingsQuery)
  return settings.data ? <CastingSection cast={settings.data.cast} /> : null
}

function ServerStatus() {
  const health = useQuery(healthQuery)
  const { connected } = useEventState()
  const h = health.data
  if (health.isError) return <p className="text-sm text-red-400">{health.error.message}</p>
  if (!h) return null
  return (
    <dl aria-label="Server status" className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-4">
      <Stat label="Server">
        <span aria-hidden="true" className="h-2 w-2 rounded-full bg-emerald-400" />
        {h.status === 'ok' ? 'Running' : h.status}
      </Stat>
      <Stat label="Version">
        <code className="text-[15px] font-medium">{h.version}</code>
      </Stat>
      <Stat label="Live updates">{connected ? 'Connected' : <span className="text-warn">Reconnecting</span>}</Stat>
      <Stat label="Metadata">{h.tmdbConfigured ? 'TMDB ready' : <span className="text-warn">No TMDB key</span>}</Stat>
    </dl>
  )
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="bg-surface px-5 py-4">
      <dt className="text-xs text-neutral-400">{label}</dt>
      <dd className="mt-1 flex items-center gap-2 font-semibold">{children}</dd>
    </div>
  )
}
