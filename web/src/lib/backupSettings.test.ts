import { describe, expect, it } from 'vitest'
import { backupFailure, backupFolder, backupMadeLine, backupPill, keepChoices, keepLine, restoreSteps } from './backupSettings'

const words = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.')
const refusal = (status: number, message: string) => Object.assign(new Error(message), { status })

describe('the Backups page', () => {
  const now = Date.parse('2026-11-14T16:00:00Z')

  it('says whether there is a recent backup', () => {
    expect(backupPill([], now)).toEqual({ tone: 'warn', label: 'No backups yet' })
    expect(backupPill([{ modTime: '2026-11-14T09:00:00Z' }, { modTime: '2026-11-10T09:00:00Z' }], now).tone).toBe('ok')
    expect(backupPill([{ modTime: '2026-11-10T09:00:00Z' }], now).tone).toBe('warn')
  })

  it('offers the usual numbers to keep, and the saved one', () => {
    expect(keepChoices(7).map((c) => c.label)).toEqual(['Off', '1', '3', '7', '14', '30', '90', '365'])
    expect(keepChoices(10).map((c) => c.value)).toContain(10)
    expect(keepLine(0, '/config/backups')).toBe('Nothing is backed up unless you do it by hand.')
    expect(keepLine(7, '/config/backups')).toContain('The newest 7 are kept in /config/backups.')
  })

  it('uses the separator of the server’s path', () => {
    expect(backupFolder('/config')).toBe('/config/backups')
    expect(backupFolder('/config/')).toBe('/config/backups')
    expect(backupFolder('C:\\ProgramData\\WatchMyMedia')).toBe('C:\\ProgramData\\WatchMyMedia\\backups')
  })
})

describe('Back up now', () => {
  it('reports what it made and what was pruned', () => {
    const made = { name: 'backup-20261114-141200-000.tar.gz', size: 48 * 1024 * 1024 }
    expect(backupMadeLine(made, 7, 7, 'data/backups')).toBe('backup-20261114-141200-000.tar.gz · 48 MB · in data/backups. The oldest of 8 was removed.')
    expect(backupMadeLine(made, 2, 7, 'data/backups')).toBe('backup-20261114-141200-000.tar.gz · 48 MB · in data/backups.')
    expect(backupMadeLine(made, 9, 7, 'data/backups')).toContain('The oldest 3 of 10 were removed.')
    expect(backupMadeLine(made, 9, 0, 'data/backups')).not.toContain('removed')
  })

  it('words the server’s reasons for failing', () => {
    expect(backupFailure(refusal(507, 'not enough space in the data folder for a backup'), '/config/backups', words).title).toBe('Couldn’t back up: the disk is full')
    expect(backupFailure(refusal(500, 'the server can\'t write to the data folder'), '/config/backups', words).title).toBe('Couldn’t back up: the server can’t write to the data folder')
    expect(backupFailure(refusal(500, 'database is locked'), '/config/backups', words)).toEqual({ title: 'Couldn’t back up', body: 'database is locked' })
    expect(backupFailure(refusal(503, 'backups not available'), '/config/backups', words).title).toBe('Backups aren’t available on this server')
  })
})

describe('Restore from a backup', () => {
  it('gives Docker’s commands, and says the folder is the mapped one', () => {
    const steps = restoreSteps('docker', undefined, '/config', 'b.tar.gz')
    expect(steps.map((s) => s.title)).toEqual(['Stop the server', 'Unpack the backup into the data folder', 'Start the server', 'Sign in again'])
    expect(steps[0].command).toBe('docker compose stop')
    expect(steps[1].command).toBe('tar -xzf "/config/backups/b.tar.gz" -C "/config"')
    expect(steps[1].text).toContain('inside the container')
  })

  it('uses Unraid’s Docker page and the apps’ menus', () => {
    expect(restoreSteps('docker', 'unraid', '/config', 'b.tar.gz')[0]).toEqual({ title: 'Stop the server', text: 'On Unraid’s Docker page, click WatchMyMedia, then Stop.' })
    expect(restoreSteps('macos-app', undefined, '/Users/x/Library/Application Support/WatchMyMedia', 'b.tar.gz')[0].text).toContain('menu bar')
    const win = restoreSteps('windows-app', undefined, 'C:\\ProgramData\\WatchMyMedia', 'b.tar.gz')
    expect(win[1].command).toBe('tar -xzf "C:\\ProgramData\\WatchMyMedia\\backups\\b.tar.gz" -C "C:\\ProgramData\\WatchMyMedia"')
    expect(restoreSteps(undefined, undefined, '/data', 'b.tar.gz')[0].command).toBeUndefined()
  })

  it('stops and starts a package install’s service, and unpacks as its user', () => {
    for (const kind of ['deb', 'rpm'] as const) {
      const steps = restoreSteps(kind, undefined, '/var/lib/watchmymedia', 'b.tar.gz', '/etc/watchmymedia/config.yaml')
      expect(steps.map((s) => s.title)).toEqual(['Stop the server', 'Unpack the backup into the data folder', 'Put the config file back', 'Start the server', 'Sign in again'])
      expect(steps[0].command).toBe('sudo systemctl stop watchmymedia')
      expect(steps[1].command).toBe('sudo -u watchmymedia tar -xzf "/var/lib/watchmymedia/backups/b.tar.gz" -C "/var/lib/watchmymedia"')
      expect(steps[1].text).toContain('as the watchmymedia user')
      expect(steps[2].text).toContain('/etc/watchmymedia/config.yaml')
      expect(steps[2].command).toBe('sudo cp "/var/lib/watchmymedia/config.yaml" "/etc/watchmymedia/config.yaml" && sudo rm "/var/lib/watchmymedia/config.yaml"')
      expect(steps[3].command).toBe('sudo systemctl start watchmymedia')
    }
    // A package install whose config file was moved into the data folder has nothing to put back.
    expect(restoreSteps('deb', undefined, '/var/lib/watchmymedia', 'b.tar.gz').map((s) => s.title)).not.toContain('Put the config file back')
  })

  it('says where the config file goes back on any server that reads it from elsewhere', () => {
    const steps = restoreSteps('binary', undefined, '/srv/wmm', 'b.tar.gz', '/etc/wmm.yaml')
    expect(steps.map((s) => s.title)).toEqual(['Stop the server', 'Unpack the backup into the data folder', 'Put the config file back', 'Start the server', 'Sign in again'])
    expect(steps[2]).toEqual({ title: 'Put the config file back', text: 'This server reads its config file from /etc/wmm.yaml, not the data folder. Copy the backup’s, now at /srv/wmm/config.yaml, over it.' })
    expect(restoreSteps('windows-app', undefined, 'C:\\ProgramData\\WatchMyMedia', 'b.tar.gz', 'D:\\wmm.yaml')[2].text).toContain('now at C:\\ProgramData\\WatchMyMedia\\config.yaml,')
  })
})
