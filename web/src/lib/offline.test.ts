import { describe, expect, it } from 'vitest'
import {
  canRevoke,
  deviceState,
  downloadMark,
  downloadsSummary,
  downloadTitle,
  lastSync,
  progressLine,
  qualityChip,
  sizeLabel,
  timeLeft,
  transfersSummary,
  type OfflineDownload,
  type OfflineTransfer,
} from './offline'

const GB = 1024 ** 3

const download = (over: Partial<OfflineDownload> = {}): OfflineDownload => ({
  id: 'k3v9aaaaaaaaaaaa',
  fileId: 42,
  state: 'ready',
  mode: 'direct',
  protocol: 'file',
  url: '/api/offline/k3v9aaaaaaaaaaaa/stream',
  sizeBytes: 3.9 * GB,
  sizeEstimated: false,
  quality: 'original',
  createdAt: '2026-11-01T10:00:00Z',
  item: { type: 'episode', title: 'Slack Water', showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 6 },
  deviceState: 'downloaded',
  deviceName: 'Sam’s phone',
  user: { id: 2, name: 'Sam' },
  ...over,
})

const transfer = (over: Partial<OfflineTransfer> = {}): OfflineTransfer => ({
  downloadId: 'k3v9aaaaaaaaaaaa',
  user: { id: 2, name: 'Sam' },
  deviceName: 'Sam’s tablet',
  fileId: 42,
  item: { type: 'movie', title: 'Dead Reckoning Bay' },
  quality: 'original',
  mode: 'direct',
  network: 'local',
  sizeBytes: 8.1 * GB,
  sizeEstimated: false,
  bytesSent: 5 * GB,
  bandwidth: 38_000_000,
  progress: 0.62,
  startedAt: '2026-11-01T10:00:00Z',
  lastSeenAt: '2026-11-01T10:05:00Z',
  ...over,
})

describe('qualityChip', () => {
  it('names the quality, and marks transcodes', () => {
    expect(qualityChip('original', 'direct')).toEqual({ label: 'Original', transcode: false })
    expect(qualityChip('original', 'remux')).toEqual({ label: 'Original', transcode: false })
    expect(qualityChip('720', 'transcode')).toEqual({ label: '720p · Transcode', transcode: true })
    expect(qualityChip('1080', 'audio')).toEqual({ label: '1080p', transcode: false })
  })
})

describe('downloadTitle', () => {
  it('words episodes and movies as the board does', () => {
    expect(downloadTitle(download().item)).toEqual({ title: 'Harbor Lights', subtitle: 'S03 E06 · Slack Water' })
    expect(downloadTitle({ type: 'movie', title: 'Dead Reckoning Bay', year: 2024 })).toEqual({ title: 'Dead Reckoning Bay', subtitle: 'Movie · 2024' })
    expect(downloadTitle({ type: 'file', title: 'clip' }).subtitle).toBe('Not matched')
  })
})

describe('sizes and progress', () => {
  it('says about for estimates, and the part done in the whole’s unit', () => {
    expect(sizeLabel(8.1 * GB, false)).toBe('8.1 GB')
    expect(sizeLabel(1.7 * GB, true)).toBe('about 1.7 GB')
    expect(progressLine(transfer())).toBe('5.0 of 8.1 GB')
    expect(progressLine(transfer({ sizeBytes: 1.7 * GB, sizeEstimated: true, progress: 0.34 }))).toBe('0.6 of about 1.7 GB')
    expect(progressLine(transfer({ progress: 0 }))).toBe('0.0 of 8.1 GB')
  })

  it('works out the time left from the rate', () => {
    // 38% of 8.1 GB (GiB) at 38 Mbps is 11.6 minutes.
    expect(timeLeft(transfer())).toBe('about 12 min left')
    expect(timeLeft(transfer({ bandwidth: 0 }))).toBe('')
    expect(timeLeft(transfer({ progress: 1 }))).toBe('')
    expect(timeLeft(transfer({ progress: 0.999 }))).toBe('less than a minute left')
    expect(timeLeft(transfer({ progress: 0, bandwidth: 1_000_000 }))).toBe('about 19 h left')
  })
})

describe('deviceState', () => {
  it('shows the transfer’s progress while one runs', () => {
    expect(deviceState({ deviceState: 'queued' }, { progress: 0.341 })).toBe('Downloading 34%')
    expect(deviceState({ deviceState: 'queued' })).toBe('Queued')
    expect(deviceState({ deviceState: 'downloading' })).toBe('Downloading')
    expect(deviceState({ deviceState: 'downloaded' }, { progress: 1 })).toBe('Downloaded')
    expect(deviceState({})).toBe('Downloaded')
  })
})

describe('downloadMark', () => {
  it('marks what the device learns at its next sync', () => {
    expect(downloadMark(download())).toBeNull()
    expect(downloadMark(download({ state: 'gone' }))).toEqual({ text: 'Removed from the server', tone: 'warn' })
    expect(downloadMark(download({ state: 'replaced', replacedBy: 43 }))).toEqual({ text: 'A new version is available', tone: 'accent' })
    expect(downloadMark(download({ state: 'revoked', revokedBy: 'access' }))?.text).toBe('Can no longer download · removed at its next sync')
    const revoked = download({ state: 'revoked', revokedBy: 'admin', revokedByName: 'Robin', revokedAt: '2026-10-28T12:00:00Z' })
    expect(downloadMark(revoked, 'Alex')?.text).toMatch(/^Revoked by Robin, .+ · removed at its next sync$/)
    expect(downloadMark({ ...revoked, revokedAt: new Date().toISOString() }, 'Robin')?.text).toBe('Revoked by you, just now · removed at its next sync')
    expect(canRevoke(revoked)).toBe(false)
    expect(canRevoke(download({ state: 'gone' }))).toBe(true)
  })
})

describe('summaries', () => {
  it('counts downloads, devices and size', () => {
    const items = [download(), download({ deviceName: 'Sam’s tablet', sizeBytes: 8.1 * GB }), download({ user: { id: 1, name: 'Robin' }, deviceName: 'Robin’s Pixel' })]
    expect(downloadsSummary(items)).toBe('3 downloads on 3 devices · 16 GB · newest first')
    expect(downloadsSummary([download()])).toBe('1 download on 1 device · 3.9 GB · newest first')
  })

  it('splits transfers by kind and network', () => {
    const s = transfersSummary([transfer(), transfer({ mode: 'transcode', network: 'remote', bandwidth: 12_000_000 })])
    expect(s).toEqual({ downloading: '2 · 1 original, 1 transcode', home: 38_000_000, away: 12_000_000 })
    expect(transfersSummary([]).downloading).toBe('None')
  })

  it('says Now for a download being fetched', () => {
    expect(lastSync({ syncedAt: '2020-01-01T00:00:00Z' }, true)).toBe('Now')
    expect(lastSync({ syncedAt: new Date(Date.now() - 2 * 3600_000).toISOString() }, false)).toBe('2 h ago')
  })
})
