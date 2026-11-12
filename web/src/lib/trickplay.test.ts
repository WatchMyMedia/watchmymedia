import { describe, expect, it } from 'vitest'
import {
  SheetLoader,
  currentTitle,
  deleteHint,
  filesLine,
  percentDone,
  pollInterval,
  previewLeft,
  sheetUrl,
  thumbnailCopy,
  thumbnailState,
  tileAt,
  tileBackground,
  usableTrickplay,
  type Trickplay,
  type TrickplayStatus,
} from './trickplay'

const status = (over: Partial<TrickplayStatus> = {}): TrickplayStatus => ({
  enabled: true,
  files: 11284,
  done: 7912,
  failed: 14,
  pending: 3358,
  bytes: 16.8 * 1024 ** 3,
  running: true,
  ...over,
})

describe('thumbnailState', () => {
  it('follows the board: off, low disk, paused, done, working', () => {
    expect(thumbnailState(status())).toBe('working')
    expect(thumbnailState(status({ paused: 'playing' }))).toBe('paused')
    expect(thumbnailState(status({ paused: 'lowDisk', freeBytes: 3.2 * 1024 ** 3 }))).toBe('lowDisk')
    expect(thumbnailState(status({ pending: 0, done: 11270, running: false }))).toBe('done')
    expect(thumbnailState(status({ enabled: false, paused: 'playing' }))).toBe('off')
  })

  it('counts files still to do as working between runs', () => {
    expect(thumbnailState(status({ running: false }))).toBe('working')
  })
})

describe('thumbnailCopy', () => {
  it('says why it waits', () => {
    expect(thumbnailCopy('paused', status())).toEqual({ pill: 'Paused while someone is watching', tone: 'off', note: 'It goes on 2 minutes after the last play stops.' })
    const low = thumbnailCopy('lowDisk', status({ paused: 'lowDisk', freeBytes: 3.2 * 1024 ** 3 }))
    expect(low.pill).toBe('Paused: under 5 GB free')
    expect(low.tone).toBe('warn')
    expect(low.note).toBe('The disk that holds the data folder has 3.2 GB free. It goes on when there is 5 GB.')
    expect(thumbnailCopy('working', status()).note).toBe('')
    expect(thumbnailCopy('off', status()).pill).toBe('Off')
  })
})

describe('progress', () => {
  it('reads as on the board', () => {
    expect(filesLine(status())).toBe('7,912 of 11,284 files')
    expect(percentDone(status())).toBe(70.1)
    expect(percentDone(status({ files: 0, done: 0 }))).toBe(0)
  })
})

describe('currentTitle', () => {
  it('names an episode with its show and number, and a movie by its title', () => {
    expect(currentTitle({ fileId: 1, title: 'The Long Dark', showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 7, sheet: 3, sheets: 4 })).toBe(
      'Harbor Lights · S03 E07 · The Long Dark',
    )
    expect(currentTitle({ fileId: 1, title: 'Paper Moons', sheet: 1, sheets: 8 })).toBe('Paper Moons')
    expect(currentTitle({ fileId: 1, title: '', showTitle: 'Harbor Lights', seasonNumber: 1, episodeNumber: 2, sheet: 1, sheets: 1 })).toBe('Harbor Lights · S01 E02')
  })
})

describe('pollInterval', () => {
  it('asks again only while there is work', () => {
    expect(pollInterval(undefined)).toBe(false)
    expect(pollInterval(status())).toBe(5000)
    expect(pollInterval(status({ running: false }))).toBe(5000)
    expect(pollInterval(status({ pending: 0, running: false }))).toBe(false)
    expect(pollInterval(status({ enabled: false, running: false }))).toBe(false)
  })
})

describe('deleteHint', () => {
  it('says whether they come back', () => {
    expect(deleteHint(true)).toContain('made again, newest first')
    expect(deleteHint(false)).toContain('Nothing is made again')
  })
})

// The plan's example manifest: a 2-hour film, 724 tiles on 8 sheets.
const film: Trickplay = { intervalMs: 10000, width: 320, height: 180, columns: 10, rows: 10, count: 724, sheets: 8, url: '/api/files/42/trickplay/9c1e07a2/{sheet}.jpg' }

describe('usableTrickplay', () => {
  it('takes a manifest it can draw from', () => {
    expect(usableTrickplay(film)).toBe(film)
    const full = { ...film, count: 800 }
    expect(usableTrickplay(full)).toBe(full)
  })

  it('drops one it cannot', () => {
    expect(usableTrickplay(undefined)).toBeNull()
    expect(usableTrickplay({ ...film, width: 0 })).toBeNull()
    expect(usableTrickplay({ ...film, intervalMs: 0 })).toBeNull()
    expect(usableTrickplay({ ...film, url: '/api/files/42/trickplay/9c1e07a2/0.jpg' })).toBeNull()
    expect(usableTrickplay({ ...film, count: 801 })).toBeNull()
  })
})

describe('tileAt', () => {
  it('finds the tile at or before the position, and its place in its sheet', () => {
    expect(tileAt(film, 0)).toEqual({ index: 0, sheet: 0, column: 0, row: 0 })
    expect(tileAt(film, 9999)).toEqual({ index: 0, sheet: 0, column: 0, row: 0 })
    expect(tileAt(film, 10000)).toEqual({ index: 1, sheet: 0, column: 1, row: 0 })
    expect(tileAt(film, 1_905_000)).toEqual({ index: 190, sheet: 1, column: 0, row: 9 })
    expect(tileAt(film, 3_456_789)).toEqual({ index: 345, sheet: 3, column: 5, row: 4 })
  })

  it('keeps to the tiles there are', () => {
    expect(tileAt(film, -5000).index).toBe(0)
    expect(tileAt(film, 99_999_999)).toEqual({ index: 723, sheet: 7, column: 3, row: 2 })
  })
})

describe('tileBackground', () => {
  it('scales the sheet to the box and moves it to the tile', () => {
    expect(sheetUrl(film, 3)).toBe('/api/files/42/trickplay/9c1e07a2/3.jpg')
    expect(tileBackground(film, tileAt(film, 3_456_789))).toEqual({
      backgroundImage: 'url("/api/files/42/trickplay/9c1e07a2/3.jpg")',
      backgroundSize: '1000% 1000%',
      backgroundPosition: `${(5 / 9) * 100}% ${(4 / 9) * 100}%`,
    })
    expect(tileBackground(film, tileAt(film, 99_000)).backgroundPosition).toBe('100% 0%')
  })

  it('handles a sheet one tile wide', () => {
    expect(tileBackground({ ...film, columns: 1, rows: 1, count: 1, sheets: 1 }, { index: 0, sheet: 0, column: 0, row: 0 }).backgroundPosition).toBe('0% 0%')
  })
})

describe('previewLeft', () => {
  it('centres the preview on the pointer and keeps it on the track', () => {
    expect(previewLeft(700, 260, 1408)).toBe(570)
    expect(previewLeft(10, 260, 1408)).toBe(0)
    expect(previewLeft(1400, 260, 1408)).toBe(1148)
    expect(previewLeft(100, 260, 200)).toBe(0)
  })
})

describe('SheetLoader', () => {
  function loader(fail: Set<number> = new Set()) {
    const asked: string[] = []
    const pending: (() => void)[] = []
    let loads = 0
    const l = new SheetLoader(
      film,
      (url) => {
        asked.push(url)
        const sheet = Number(/(\d+)\.jpg$/.exec(url)?.[1])
        return new Promise<void>((resolve, reject) => pending.push(() => (fail.has(sheet) ? reject(new Error('404')) : resolve())))
      },
      () => loads++,
    )
    const settle = async () => {
      pending.splice(0).forEach((f) => f())
      await new Promise((r) => setTimeout(r, 0))
    }
    return { l, asked, settle, loads: () => loads }
  }

  it('fetches the sheet under a scrub and those either side, once each', async () => {
    const { l, asked, settle, loads } = loader()
    l.scrubFrom(tileAt(film, 1_905_000))
    expect(asked.map((u) => u.split('/').pop())).toEqual(['1.jpg', '2.jpg', '0.jpg'])
    expect(l.loaded(1)).toBe(false)
    l.want(1)
    expect(asked).toHaveLength(3)
    await settle()
    expect([l.loaded(0), l.loaded(1), l.loaded(2)]).toEqual([true, true, true])
    expect(loads()).toBe(3)
  })

  it('asks for no sheet past either end', () => {
    const { l, asked } = loader()
    l.scrubFrom(tileAt(film, 0))
    l.scrubFrom(tileAt(film, 99_999_999))
    expect(asked.map((u) => u.split('/').pop())).toEqual(['0.jpg', '1.jpg', '7.jpg', '6.jpg'])
  })

  it('tries a failed sheet again on the next scrub, not at every tile', async () => {
    const fail = new Set([1])
    const { l, asked, settle } = loader(fail)
    l.want(1)
    await settle()
    l.want(1)
    expect(asked).toHaveLength(1)
    fail.clear()
    l.scrubFrom(tileAt(film, 1_000_000))
    expect(asked.filter((u) => u.endsWith('/1.jpg'))).toHaveLength(2)
    await settle()
    expect(l.loaded(1)).toBe(true)
  })
})
