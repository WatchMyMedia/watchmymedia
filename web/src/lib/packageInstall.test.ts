import { describe, expect, it } from 'vitest'
import { isPackageInstall, packageFolderAccess, shellQuote } from './packageInstall'

describe('a server installed from the .deb or .rpm package', () => {
  it('is those two kinds only', () => {
    expect(isPackageInstall('deb')).toBe(true)
    expect(isPackageInstall('rpm')).toBe(true)
    for (const other of ['docker', 'binary', 'macos-app', 'windows-app', '', undefined]) expect(isPackageInstall(other)).toBe(false)
  })

  it('names the watchmymedia user and gives the command for the folder that was checked', () => {
    for (const kind of ['deb', 'rpm']) {
      const help = packageFolderAccess(kind, '/home/robin/Videos')
      expect(help?.text).toContain('runs as the watchmymedia user')
      expect(help?.command).toBe(`sudo setfacl -R -m u:watchmymedia:rX '/home/robin/Videos'`)
      expect(help?.after).toContain('home folder')
    }
  })

  it('keeps a folder with spaces or quotes one word of the command', () => {
    expect(packageFolderAccess('deb', '/srv/TV Shows')?.command).toBe(`sudo setfacl -R -m u:watchmymedia:rX '/srv/TV Shows'`)
    expect(shellQuote(`/srv/Robin's films`)).toBe(`'/srv/Robin'\\''s films'`)
    expect(shellQuote('/srv/$HOME "x"')).toBe(`'/srv/$HOME "x"'`)
  })

  it('leaves every other install its own words', () => {
    for (const other of ['docker', 'binary', 'macos-app', 'windows-app', undefined]) expect(packageFolderAccess(other, '/movies')).toBeUndefined()
  })
})
