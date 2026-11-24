import { AddressError } from '../session/address'

export type Words = { title: string; body: string }

/** The HTTP status an error carries: the web's ApiError, or the player's own. */
export function statusOf(error: unknown): number | undefined {
  const s = (error as { status?: unknown } | null)?.status
  return typeof s === 'number' ? s : undefined
}

/**
 * What to say about a failure, in our own words by its kind and status.
 * Server text never reaches the screen: `ApiError.message` carries the
 * server's `detail`, which can name paths, addresses or worse. Screens show
 * these words and, at most, their own context ("Couldn't load Movies").
 */
export function problemWords(error: unknown): Words {
  if (error instanceof AddressError) return { title: 'That isn’t a server address', body: error.message }
  if (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError')) {
    return { title: 'The server took too long', body: 'Check that it’s on and that this TV is connected, then try again.' }
  }
  const status = statusOf(error)
  if (status === undefined) {
    // fetch rejects with a TypeError when nothing answers: off, asleep, or another network.
    return { title: 'Can’t reach the server', body: 'Check that it’s on and that this TV is on the same network, then try again.' }
  }
  if (status === 401) return { title: 'Signed out', body: 'This TV was signed out. Sign in again to keep watching.' }
  if (status === 403) return { title: 'Not allowed', body: 'Your account can’t do this. The server’s admin can change that.' }
  if (status === 404) return { title: 'Not found', body: 'It may have been removed from the server.' }
  if (status === 409) return { title: 'Already done', body: 'Someone else got there first. What you see is up to date.' }
  if (status === 429) return { title: 'Too many tries', body: 'Wait a minute, then try again.' }
  if (status === 503) return { title: 'The server is busy', body: 'Try again in a moment.' }
  if (status >= 500) return { title: 'The server had a problem', body: 'Try again in a moment. If it keeps happening, restart the server.' }
  return { title: 'Something went wrong', body: 'Try again.' }
}

/** The password form's own words: a wrong password is a 401 here, not a lost session. */
export function signInWords(error: unknown): string {
  const status = statusOf(error)
  if (status === 401) return 'That name and password don’t match. Check them and try again.'
  if (status === 429) return 'Too many tries. Wait a minute, then try again.'
  if (status === 403) return 'This server wants a browser check before a password sign-in. Use a code instead.'
  return problemWords(error).body
}
