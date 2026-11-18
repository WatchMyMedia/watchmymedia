import type { components } from '../api/schema'
import { formatBytes, relativeTime } from './format'

// Settings › Server › Backups (PLAN-ADMIN-SETTINGS.md §5.8): whether there
// is a recent backup, what Back up now made, why it failed, and the restore
// path for how this server is installed.

type BackupInfo = components['schemas']['Info']
type Install = components['schemas']['UpdateStatus']['install']

const DAY = 86_400_000

/** The data folder's backups folder, with the separator the server's path uses. */
export function backupFolder(dataDir: string): string {
  const sep = dataDir.includes('\\') && !dataDir.includes('/') ? '\\' : '/'
  return `${dataDir.replace(/[\\/]+$/, '')}${sep}backups`
}

/** The page's pill: the newest backup's age, warning when it is over two days old or there is none. */
export function backupPill(backups: readonly Pick<BackupInfo, 'modTime'>[], now = Date.now()): { tone: 'ok' | 'warn'; label: string } {
  const newest = backups.reduce<number | undefined>((n, b) => {
    const t = new Date(b.modTime).getTime()
    return n === undefined || t > n ? t : n
  }, undefined)
  if (newest === undefined) return { tone: 'warn', label: 'No backups yet' }
  return { tone: now - newest > 2 * DAY ? 'warn' : 'ok', label: `Last backup ${relativeTime(new Date(newest).toISOString())}` }
}

/** Nightly backups to keep: the usual choices, and the saved number when it is none of them. */
export function keepChoices(saved: number): { value: number; label: string }[] {
  const base = [0, 1, 3, 7, 14, 30, 90, 365]
  const values = base.includes(saved) ? base : [...base, saved].sort((a, b) => a - b)
  return values.map((value) => ({ value, label: value === 0 ? 'Off' : String(value) }))
}

/** The Nightly row's line for a number kept. */
export function keepLine(keep: number, folder: string): string {
  if (keep <= 0) return 'Nothing is backed up unless you do it by hand.'
  return `The newest ${keep === 1 ? 'one is' : `${keep} are`} kept in ${folder}. They run once a day, counted from when the server started, not at a set time.`
}

/** What Back up now made, for its row: the file, its size, where, and what was pruned. */
export function backupMadeLine(made: Pick<BackupInfo, 'name' | 'size'>, before: number, keep: number, folder: string): string {
  const total = before + 1
  const removed = keep > 0 ? Math.max(0, total - keep) : 0
  const pruned = removed === 0 ? '' : removed === 1 ? ` The oldest of ${total} was removed.` : ` The oldest ${removed} of ${total} were removed.`
  return `${made.name} · ${formatBytes(made.size)} · in ${folder}.${pruned}`
}

/**
 * Why Back up now failed, worded for the page: the server says out of space
 * (507) or can't write (500 with those words); anything else is its own words.
 */
export function backupFailure(error: unknown, folder: string, words: (e: unknown) => string): { title: string; body: string } {
  const status = error && typeof error === 'object' && 'status' in error ? (error as { status: number }).status : 0
  const message = error instanceof Error ? error.message : ''
  if (status === 507) {
    return { title: 'Couldn’t back up: the disk is full', body: `${folder} doesn’t have room for a backup, so nothing was saved. Free some space, or keep fewer nightly backups.` }
  }
  if (status === 500 && /can.?t write/i.test(message)) {
    return { title: 'Couldn’t back up: the server can’t write to the data folder', body: `Nothing was saved. Check that the server’s user may write to ${folder}, then back up again.` }
  }
  if (status === 503) return { title: 'Backups aren’t available on this server', body: 'Nothing was saved.' }
  return { title: 'Couldn’t back up', body: words(error) }
}

export type RestoreStep = { title: string; text: string; command?: string }

/**
 * The steps of a restore, done on the server itself, with the commands for
 * how it is installed (UpdateStatus.install, and ServerInfo.host for
 * Unraid). `file` is the newest backup's name, or a stand-in. `configFile`
 * is Settings.configFile: set when the server reads its config file from
 * outside the data folder, as the Linux packages do, which adds a step that
 * puts the backup's copy back there.
 */
export function restoreSteps(install: Install | undefined, host: string | undefined, dataDir: string, file: string, configFile?: string): RestoreStep[] {
  const folder = backupFolder(dataDir)
  const sep = folder.endsWith('\\backups') ? '\\' : '/'
  const archive = `${folder}${sep}${file}`
  let unpack = `tar -xzf "${archive}" -C "${dataDir}"`
  const restored = `${dataDir.replace(/[\\/]+$/, '')}${sep}config.yaml`
  let config: RestoreStep | undefined = configFile
    ? { title: 'Put the config file back', text: `This server reads its config file from ${configFile}, not the data folder. Copy the backup’s, now at ${restored}, over it.` }
    : undefined
  let stop: RestoreStep
  let start: RestoreStep
  let where = `This server’s data folder is ${dataDir}. Replace what’s there with what’s in the backup:`
  switch (install) {
    case 'docker':
      if (host === 'unraid') {
        stop = { title: 'Stop the server', text: 'On Unraid’s Docker page, click WatchMyMedia, then Stop.' }
        start = { title: 'Start the server', text: 'On Unraid’s Docker page, click WatchMyMedia, then Start.' }
      } else {
        stop = { title: 'Stop the server', text: 'Docker, from the folder with your compose file (or docker stop with your container’s name):', command: 'docker compose stop' }
        start = { title: 'Start the server', text: 'Docker:', command: 'docker compose start' }
      }
      where = `This server’s data folder is ${dataDir} inside the container; on the host it is the folder mapped to it. Replace what’s there with what’s in the backup:`
      break
    case 'macos-app':
      stop = { title: 'Stop the server', text: 'Choose Quit from the WatchMyMedia menu bar item.' }
      start = { title: 'Start the server', text: 'Open WatchMyMedia again from Applications.' }
      break
    case 'windows-app':
      stop = { title: 'Stop the server', text: 'Choose Quit from the WatchMyMedia menu by the clock.' }
      start = { title: 'Start the server', text: 'Start WatchMyMedia again from the Start menu.' }
      break
    case 'deb':
    case 'rpm':
      stop = { title: 'Stop the server', text: 'On the server, stop its service:', command: 'sudo systemctl stop watchmymedia' }
      start = { title: 'Start the server', text: 'Start the service again:', command: 'sudo systemctl start watchmymedia' }
      where = `This server’s data folder is ${dataDir}. Unpack the backup there as the watchmymedia user, which the server runs as, so it can read what’s unpacked:`
      unpack = `sudo -u watchmymedia ${unpack}`
      if (configFile) {
        config = {
          title: 'Put the config file back',
          text: `This server reads its config file from ${configFile}, not the data folder. Copy the backup’s over it and remove the copy:`,
          command: `sudo cp "${restored}" "${configFile}" && sudo rm "${restored}"`,
        }
      }
      break
    default:
      stop = { title: 'Stop the server', text: 'Stop the server the way it is run: Ctrl+C in its terminal, or its service manager.' }
      start = { title: 'Start the server', text: 'Start it again the same way.' }
  }
  return [
    stop,
    { title: 'Unpack the backup into the data folder', text: where, command: unpack },
    ...(config ? [config] : []),
    start,
    { title: 'Sign in again', text: 'Everyone signs in again on every device. Then come back here: Plex needs signing in to again, and seek thumbnails are remade by themselves.' },
  ]
}
