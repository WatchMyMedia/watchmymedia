import type { components } from '../../../src/api/schema'

// Why Home has nothing to show (Android's HomeEmptyState), from the
// libraries rather than a guess that a scan is running. A failed scan's
// reason is the server's text and can name a folder, so it is never shown:
// only which library couldn't be read.

type Library = components['schemas']['Library']

export type HomeEmptyKind = 'noLibraries' | 'scanning' | 'scanFailed' | 'emptyLibraries' | 'unavailable'

export type HomeEmpty = { kind: HomeEmptyKind; title: string; body: string; /** Admins are shown the server's address, to set it up from a computer. */ setup: boolean }

const scanning = (l: Library) => !!l.scan || !!l.match

/** Which empty Home this is; `unavailable` when the libraries couldn't be read either. */
export function homeEmptyKind(libraries: readonly Library[] | undefined): { kind: HomeEmptyKind; failed: Library[]; othersScanning: boolean } {
  if (!libraries) return { kind: 'unavailable', failed: [], othersScanning: false }
  // A retry supersedes that library's old failure, but never hides another library's.
  const failed = libraries.filter((l) => !scanning(l) && !!l.lastScanError)
  const running = libraries.some(scanning)
  if (failed.length) return { kind: 'scanFailed', failed, othersScanning: running }
  if (running) return { kind: 'scanning', failed, othersScanning: false }
  if (!libraries.length) return { kind: 'noLibraries', failed, othersScanning: false }
  return { kind: 'emptyLibraries', failed, othersScanning: false }
}

/** What an empty Home says, for an admin or for everyone else. */
export function homeEmpty(libraries: readonly Library[] | undefined, isAdmin: boolean): HomeEmpty {
  const { kind, failed, othersScanning } = homeEmptyKind(libraries)
  const ask = (what: string) => isAdmin ? `${what} on the server’s web page.` : `Ask the person who runs the server to ${what.charAt(0).toLowerCase()}${what.slice(1)}.`
  switch (kind) {
    case 'noLibraries':
      return { kind, title: 'This server has no movies or TV yet', body: isAdmin ? 'Add a library on the server’s web page, then come back here.' : 'Ask the person who runs it to add a library.', setup: true }
    case 'scanning':
      return { kind, title: 'Finding your files…', body: 'They appear here as they’re found.', setup: false }
    case 'scanFailed': {
      const names = failed.map((l) => l.name)
      const which = names.length === 1 ? `Couldn’t read ${names[0]}.` : `Couldn’t read ${names.slice(0, -1).join(', ')} or ${names[names.length - 1]}.`
      return {
        kind, title: names.length === 1 ? 'Couldn’t read the library' : 'Couldn’t read some libraries',
        body: [which, othersScanning ? 'Other libraries are still being scanned.' : '', ask('Check the library')].filter(Boolean).join(' '),
        setup: true,
      }
    }
    case 'emptyLibraries':
      return { kind, title: 'No movies or shows found', body: ask('Check the library’s folders and scan status'), setup: true }
    case 'unavailable':
      return { kind, title: 'Nothing here yet', body: 'Couldn’t check the library’s scan status. Try again.', setup: false }
  }
}
