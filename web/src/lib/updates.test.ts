import { describe, expect, it } from 'vitest'
import { dockerUpdateSteps, updateDownload } from './updates'

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
  it('tells Docker installs how to pull, and Unraid ones to use its Docker page', () => {
    expect(dockerUpdateSteps()).toEqual({ text: expect.stringContaining('compose file'), command: 'docker compose pull && docker compose up -d' })
    expect(dockerUpdateSteps('unraid').command).toBeUndefined()
    expect(dockerUpdateSteps('unraid').text).toContain('Check for Updates')
  })
})
