import type { components } from '../api/schema'

// Settings › Server › General: the TMDB key as
// the Next step and a one-step task, the Remove key dialog, and the rest of
// the page's wording.

type Settings = components['schemas']['Settings']
type TestResult = components['schemas']['TestResult']

/** General's Next step: Add a TMDB key while there is none; else nothing to do. */
export function generalNextStep(s: Pick<Settings, 'tmdbConfigured'> | undefined): 'tmdb' | null {
  return s && !s.tmdbConfigured ? 'tmdb' : null
}

export type KeyMessage = { tone: 'ok' | 'err'; title: string; body: string }

/** TMDB's answer to Test, which saves nothing. */
export function tmdbTestMessage(result: Pick<TestResult, 'ok' | 'message'>): KeyMessage {
  if (result.ok) return { tone: 'ok', title: 'TMDB accepted this key', body: 'Nothing is saved yet.' }
  if (/invalid api key|unauthori[sz]ed|401/i.test(result.message)) {
    return { tone: 'err', title: 'TMDB didn’t accept this key', body: 'Copy the API Key again. If you pasted the long Read Access Token, use the shorter key above it.' }
  }
  return { tone: 'err', title: 'Couldn’t reach TMDB', body: 'Check the server’s internet connection and test again.' }
}

/** Why Save key was refused: TMDB said no (422), or couldn't be reached (503). */
export function tmdbSaveMessage(error: unknown, words: (e: unknown) => string): KeyMessage {
  const status = error && typeof error === 'object' && 'status' in error ? (error as { status: number }).status : 0
  if (status === 422) return { tone: 'err', title: 'TMDB didn’t accept this key', body: 'Copy the API Key again. If you pasted the long Read Access Token, use the shorter key above it.' }
  if (status === 503) return { tone: 'err', title: 'Couldn’t reach TMDB, so the key wasn’t saved', body: 'Check the server’s internet connection and save again.' }
  return { tone: 'err', title: 'The key wasn’t saved', body: words(error) }
}

/**
 * The Remove the TMDB key? dialog: what stops and what stays,
 * and, when config.yaml or the environment has a key too, that it comes
 * back at the next restart.
 */
export function removeKeyWords(fromConfig: boolean | undefined): { stops: string[]; stays: string[]; config?: { title: string; body: string } } {
  return {
    stops: ['Matching new files, trailers, TMDB lists and looking things up to request.'],
    stays: ['Posters and details already fetched.'],
    config: fromConfig
      ? { title: 'config.yaml also has a TMDB key', body: 'Removing the key here uses that one again from the next restart. To stop using TMDB, remove it from config.yaml too.' }
      : undefined,
  }
}

/** The line on General after the key is saved or removed. */
export function tmdbDoneLine(saved: 'saved' | 'removed', fromConfig?: boolean): string {
  if (saved === 'saved') return 'The key is saved. Every library is being matched again; Libraries shows progress.'
  return fromConfig
    ? 'The key is removed and matching is off. The key in config.yaml is used again from the next restart.'
    : 'The key is removed. Matching, trailers and TMDB lists are off until a key is added.'
}

/** The page's state line: version, libraries, and whether live updates are connected. */
export function generalLede(version: string | undefined, libraries: number | undefined, connected: boolean): string {
  return [version && `Version ${version}`, libraries !== undefined && `${libraries} ${libraries === 1 ? 'library' : 'libraries'}`, connected ? 'events connected' : 'events reconnecting']
    .filter(Boolean)
    .join(' · ')
}

/** A server name is up to 63 characters; empty uses the computer's name. */
export function serverNameError(name: string): string | undefined {
  return name.trim().length > 63 ? 'Up to 63 characters.' : undefined
}
