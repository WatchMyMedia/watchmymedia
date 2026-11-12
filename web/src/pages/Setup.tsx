import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { librariesQuery, liveTVCapabilitiesQuery, liveTVSettingsQuery } from '../api/queries'
import { settingsQuery, useCreateLibrary } from '../api/settings'
import { LibraryForm } from '../components/LibraryForm'
import { BackupSection, OpenSubtitlesSection, TMDBSection } from '../components/SettingsSections'
import { HardwareSettings } from '../components/HardwareSettings'
import { Panel, secondaryButton } from '../components/SettingsPanel'
import { liveTVSetupPlan, type LiveSetupStep } from '../lib/liveTVSettingsRules'
import { addFailedHint, draftBody, libraryLabel, libraryTypes, readDrafts, setupStep, sourcesNext, writeDrafts, type FolderDraft, type FolderDrafts, type LibraryType } from '../lib/setupRules'
import { LiveTunerSetup } from './settings/LiveTV'

/**
 * First run accepts libraries, a tuner, or both. File-only setup steps stay with libraries.
 * The step is in the URL (`?step=`), and folders typed on step one are kept until they are libraries.
 */
export function Setup({ step: requested }: { step?: LiveSetupStep }) {
  const libraries = useQuery(librariesQuery)
  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const liveSettings = useQuery(liveTVSettingsQuery)
  const settings = useQuery(settingsQuery)
  // One mutation per card, so a failure or a save in progress shows on its own card.
  const createMovie = useCreateLibrary()
  const createTV = useCreateLibrary()
  const create = { movie: createMovie, tv: createTV }
  const navigate = useNavigate({ from: '/setup' })
  const [drafts, setDrafts] = useState<FolderDrafts>(readDrafts)
  const [adding, setAdding] = useState<LibraryType | null>(null)
  const [failedHint, setFailedHint] = useState<string | null>(null)
  useEffect(() => writeDrafts(drafts), [drafts])
  const libs = libraries.data?.items ?? []
  const sources = liveSettings.data?.sources ?? []
  const tunerSaved = capabilities.data?.setupComplete === true || sources.length > 0
  const plan = liveTVSetupPlan(libs.length, tunerSaved)
  const step = setupStep(requested, plan)
  const has = (type: LibraryType) => libs.find((l) => l.type === type)
  const next = sourcesNext(libraryTypes.filter((t) => has(t)), drafts, tunerSaved)
  // Step one shows the path the server takes once its typed folders are added.
  const shownSteps = step === 'sources' ? next.plan.steps : plan.steps
  const setStep = (s: LiveSetupStep) => void navigate({ search: { step: s === 'sources' ? undefined : s } })

  const editDraft = (type: LibraryType, draft: FolderDraft) => {
    setDrafts((d) => ({ ...d, [type]: draft }))
    create[type].reset()
    setFailedHint(null)
  }

  // Next adds every folder left in a card, one at a time so an error lands
  // on its own card, and moves on only when all of them are libraries.
  const addAndContinue = async () => {
    const added: LibraryType[] = []
    setFailedHint(null)
    for (const type of next.toAdd) {
      setAdding(type)
      try {
        await create[type].mutateAsync(draftBody(type, drafts[type]))
        added.push(type)
      } catch {
        setAdding(null)
        setFailedHint(addFailedHint(added, type))
        void libraries.refetch()
        return
      }
    }
    await libraries.refetch()
    setAdding(null)
    setStep(next.plan.nextStep)
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <header>
        <h1 className="text-3xl font-bold">Set up your server</h1>
        <ol className="mt-3 flex flex-wrap gap-2 text-sm">
          {shownSteps.map(([s, label], i) => (
            <li key={s} aria-current={step === s ? 'step' : undefined} className={`rounded-full border px-3 py-1 ${step === s ? 'border-accent text-neutral-100' : 'border-line-2 text-neutral-400'}`}>
              {i + 1}. {label}
            </li>
          ))}
        </ol>
      </header>

      {step === 'sources' && (
        <section className="space-y-6">
          <p className="text-neutral-400">
            Add movie or TV folders, an HDHomeRun antenna tuner, or both. A tuner alone is enough. In Docker, library folders are the ones you mounted, usually <code>/movies</code> and <code>/tv</code>.
          </p>
          {libraries.isPending && <p role="status" className="text-sm text-neutral-400">Loading libraries…</p>}
          {libraries.isError && <div role="alert" className="space-y-2 text-sm text-warn"><p>{libraries.error.message}</p><button type="button" onClick={() => void libraries.refetch()} className={secondaryButton}>Retry libraries</button></div>}
          {libraries.isSuccess && (['movie', 'tv'] as const).map((type) => {
            const existing = has(type)
            const busy = adding !== null || create[type].isPending
            return (
              <div key={type} className="rounded-lg border border-line bg-surface p-4">
                <h2 className="mb-3 text-lg font-semibold">{libraryLabel(type)}</h2>
                {existing ? (
                  <p className="text-sm text-emerald-400">
                    ✓ {existing.name} — <code>{existing.path}</code>
                  </p>
                ) : (
                  <LibraryForm
                    type={type}
                    value={drafts[type]}
                    onChange={(v) => editDraft(type, v)}
                    note={drafts[type].path.trim() && !busy ? 'Not added yet. Next adds it, or press Add library.' : null}
                    submitLabel="Add library"
                    busy={busy}
                    error={create[type].isError ? create[type].error.message : null}
                    onSubmit={(v) => create[type].mutate(draftBody(type, v))}
                  />
                )}
              </div>
            )
          })}
          <Panel title="Live TV" description="Watch your antenna channels through an HDHomeRun tuner. This server connects to the tuner; viewers only need to reach this server.">
            <div className="space-y-3 border-t border-line p-5">
              {liveSettings.isPending ? <p role="status" className="text-sm text-neutral-400">Loading tuner settings…</p>
                : liveSettings.isError ? <div role="alert" className="space-y-2 text-sm text-warn"><p>{liveSettings.error.message}</p><button type="button" onClick={() => void liveSettings.refetch()} className={secondaryButton}>Retry tuner settings</button></div>
                  : sources.length > 0 ? <>
                    {sources.map((source) => <p key={source.id} className="break-words text-sm text-emerald-400">✓ {source.name} — {source.device.model}{source.enabled ? '' : ' (disabled)'}</p>)}
                    <p className="text-sm text-neutral-400">Tuner saved. Choose which channels to show and who can watch in Settings › Live TV.</p>
                  </> : <LiveTunerSetup />}
            </div>
          </Panel>
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              disabled={!libraries.isSuccess || !next.enabled || adding !== null}
              onClick={() => (next.toAdd.length ? void addAndContinue() : setStep(next.plan.nextStep))}
              className="min-h-[44px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50"
            >
              {adding ? `Adding ${libraryLabel(adding)}…` : next.label}
            </button>
            {failedHint ? <p role="alert" className="text-sm text-neutral-300">{failedHint}</p>
              : !next.enabled && <p className="text-sm text-neutral-400">Add a folder or save a tuner to carry on.</p>}
          </div>
        </section>
      )}

      {step === 'tmdb' && (
        <section className="space-y-6">
          <TMDBSection onSaved={() => setStep('subtitles')} />
          <div className="flex gap-2">
            <button type="button" onClick={() => setStep('sources')} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
              Back
            </button>
            <button type="button" onClick={() => setStep('subtitles')} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
              Skip for now
            </button>
          </div>
        </section>
      )}

      {step === 'subtitles' && (
        <section className="space-y-6">
          <OpenSubtitlesSection onSaved={() => setStep('transcode')} />
          <div className="flex gap-2">
            <button type="button" onClick={() => setStep('tmdb')} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
              Back
            </button>
            <button type="button" onClick={() => setStep('transcode')} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
              {settings.data?.subtitles.available === false ? 'Next' : 'Skip for now'}
            </button>
          </div>
        </section>
      )}

      {step === 'transcode' && (
        <section className="space-y-6">
          <HardwareSettings setup />
          <div className="flex gap-2">
            <button type="button" onClick={() => setStep(plan.backFromTranscode)} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
              Back
            </button>
            <button type="button" onClick={() => setStep('done')} className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold">
              Next
            </button>
          </div>
        </section>
      )}

      {step === 'done' && (
        <section className="space-y-6">
          <h2 className="text-2xl font-semibold">Your server is ready</h2>
          <p className="text-neutral-300">
            {plan.hasLibraries
              ? settings.data?.tmdbConfigured ? 'Your folders are saved. Titles and posters appear as your files are scanned. Check progress with the ⟳ button.' : 'Your folders are saved. You can watch files now and add posters and title details later.'
              : 'Your server opens on Live TV. Add movie or TV folders any time in Settings › Libraries; setup won’t ask again.'}
            {' '}Backups run nightly and can be changed in Settings.
          </p>
          {plan.hasLibraries && settings.data && !settings.data.tmdbConfigured && <div className="space-y-3 rounded-lg border border-warn/40 bg-warn/10 p-4">
            <h3 className="font-semibold">Posters and title details are off</h3>
            <p className="text-sm text-neutral-300">TMDB supplies posters, descriptions and episode details. Add your free TMDB key now, or add it later in Settings › General.</p>
            <button type="button" onClick={() => setStep('tmdb')} className={secondaryButton}>Add a key</button>
          </div>}
          {plan.hasLibraries && <p className="text-sm text-neutral-400">Your scan has already started. Go to Home does not start it again.</p>}
          <BackupSection />
          <button
            type="button"
            onClick={() => {
              writeDrafts(null)
              void navigate({ to: plan.destination })
            }}
            className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold"
          >
            {plan.hasLibraries ? 'Go to Home' : 'Open Live TV'}
          </button>
        </section>
      )}
    </div>
  )
}
