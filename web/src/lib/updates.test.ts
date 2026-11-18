import { describe, expect, it } from 'vitest'
import { dockerUpdateSteps, packageUpdateSteps, updateDownload } from './updates'

describe('the update download for each install', () => {
  it('names the app the file installs', () => {
    expect(updateDownload('macos-app').label).toBe('Download for Mac')
    expect(updateDownload('windows-app').label).toBe('Download for Windows')
    expect(updateDownload('binary').label).toBe('Download')
  })
  it('points each app at its own updater', () => {
    expect(updateDownload('macos-app').hint).toContain('menu bar')
    expect(updateDownload('windows-app').hint).toContain('menu by the clock')
    expect(updateDownload('binary').hint).toContain('Replace the server binary')
    expect(updateDownload('docker').hint).toBeUndefined()
  })
  it('gives package installs the apt or dnf command, and where the repository entry is', () => {
    const deb = packageUpdateSteps('deb')
    expect(deb?.command).toBe('sudo apt update && sudo apt install --only-upgrade watchmymedia')
    expect(deb?.text).toContain('apt updates it')
    expect(deb?.after).toContain('/etc/apt/sources.list.d/watchmymedia.sources')
    const rpm = packageUpdateSteps('rpm')
    expect(rpm?.command).toBe('sudo dnf upgrade --refresh watchmymedia')
    expect(rpm?.text).toContain('dnf updates it')
    expect(rpm?.after).toContain('/etc/yum.repos.d/watchmymedia.repo')
    for (const steps of [deb, rpm]) expect(steps?.after).toContain('restarts the server')
    for (const other of ['docker', 'binary', 'macos-app', 'windows-app'] as const) expect(packageUpdateSteps(other)).toBeUndefined()
  })
  it('tells Docker installs how to pull, and Unraid ones to use its Docker page', () => {
    expect(dockerUpdateSteps()).toEqual({ text: expect.stringContaining('compose file'), command: 'docker compose pull && docker compose up -d' })
    expect(dockerUpdateSteps('unraid').command).toBeUndefined()
    expect(dockerUpdateSteps('unraid').text).toContain('Check for Updates')
  })
})
