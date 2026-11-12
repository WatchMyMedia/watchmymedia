import { liveTVSetupPlan, type LiveSetupStep } from './liveTVSettingsRules'

export type LibraryType = 'movie' | 'tv'
export type FolderDraft = { name: string; path: string }
export type FolderDrafts = Record<LibraryType, FolderDraft>

/** Movies first, as the cards are laid out. */
export const libraryTypes: LibraryType[] = ['movie', 'tv']

export function libraryLabel(type: LibraryType) {
  return type === 'tv' ? 'TV Shows' : 'Movies'
}

export function emptyDrafts(): FolderDrafts {
  return { movie: { name: libraryLabel('movie'), path: '' }, tv: { name: libraryLabel('tv'), path: '' } }
}

/** The library a draft becomes; a cleared name falls back to the card's. */
export function draftBody(type: LibraryType, draft: FolderDraft) {
  return { type, name: draft.name.trim() || libraryLabel(type), path: draft.path.trim() }
}

/** Cards with a folder typed or picked whose type has no library yet. */
export function draftsToAdd(saved: LibraryType[], drafts: FolderDrafts): LibraryType[] {
  return libraryTypes.filter((type) => !saved.includes(type) && drafts[type].path.trim() !== '')
}

/**
 * What step one's Next does. A folder left in a card is added, never
 * dropped (PLAN-ONBOARDING.md §3.1), so the setup path is the one after
 * adding: a typed folder on a tuner-only server brings Metadata back.
 */
export function sourcesNext(saved: LibraryType[], drafts: FolderDrafts, tunerSaved: boolean) {
  const toAdd = draftsToAdd(saved, drafts)
  const plan = liveTVSetupPlan(saved.length + toAdd.length, tunerSaved)
  const label = toAdd.length === 0
    ? `Next: ${plan.hasLibraries ? 'metadata' : 'transcoding'}`
    : `Add ${toAdd.length === 1 ? libraryLabel(toAdd[0]) : 'folders'} and continue`
  return { toAdd, plan, label, enabled: plan.canContinue }
}

/** Said under Next when adding a folder failed, so the way on is clear. */
export function addFailedHint(added: LibraryType[], failed: LibraryType) {
  const fix = `Fix the ${libraryLabel(failed)} folder, or clear it to carry on without one.`
  return added.length ? `${added.map(libraryLabel).join(' and ')} ${added.length === 1 ? 'was' : 'were'} added. ${fix}` : fix
}

const steps: LiveSetupStep[] = ['sources', 'tmdb', 'subtitles', 'transcode', 'done']

/** `/setup?step=…`: an unknown step is step one. */
export function validateSetupSearch(search: Record<string, unknown>): { step?: LiveSetupStep } {
  const step = steps.find((s) => s === search.step)
  return { step: step === 'sources' ? undefined : step }
}

/** The step to show: one this server's path has, and never past step one before it can carry on. */
export function setupStep(requested: LiveSetupStep | undefined, plan: ReturnType<typeof liveTVSetupPlan>): LiveSetupStep {
  if (!requested || !plan.canContinue) return 'sources'
  return plan.steps.some(([s]) => s === requested) ? requested : 'sources'
}

const KEY = 'wmm.setupDrafts'

/** Typed folders, kept for this browser tab so a reload doesn't lose them. */
export function readDrafts(): FolderDrafts {
  const drafts = emptyDrafts()
  try {
    const saved = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Partial<Record<LibraryType, Partial<FolderDraft>>> | null
    for (const type of libraryTypes) {
      const d = saved?.[type]
      if (typeof d?.name === 'string') drafts[type].name = d.name
      if (typeof d?.path === 'string') drafts[type].path = d.path
    }
  } catch {
    // Private windows and blocked storage: start with empty cards.
  }
  return drafts
}

export function writeDrafts(drafts: FolderDrafts | null) {
  try {
    if (drafts) sessionStorage.setItem(KEY, JSON.stringify(drafts))
    else sessionStorage.removeItem(KEY)
  } catch {
    // Without storage the drafts last until the page reloads.
  }
}

/** Samples are entry names, not proof of a layout: warnings never block saving. */
export function folderLayoutWarning(type: LibraryType, sample: string[]): string | undefined {
  const seasons = sample.filter((name) => /^(?:season[ ._-]*\d+|specials)$/i.test(name.normalize('NFC'))).length
  if (seasons === 0) return
  return type === 'tv'
    ? 'This looks like one show. Pick the folder that holds all your shows.'
    : 'This looks like a TV show folder. Choose your movies folder, or add it under TV Shows.'
}

export function folderCheckCount(count: number, truncated = false): string {
  return `Readable · ${truncated ? 'at least ' : ''}${count.toLocaleString()} video file${count === 1 ? '' : 's'}`
}

/** A current retry temporarily supersedes only its own library's saved error. */
export function savedScanError(library: { lastScanError?: string; lastScanErrorAt?: string; scan?: unknown }, scanning = false, last?: { finished?: { partial?: boolean; failed: number }; at: number }): string | undefined {
  if (scanning || library.scan) return
  const errorAt = library.lastScanErrorAt ? Date.parse(library.lastScanErrorAt) : 0
  if (last?.finished && !last.finished.partial && last.finished.failed === 0 && last.at >= errorAt) return
  return library.lastScanError
}
