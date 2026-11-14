import { describe, expect, it } from 'vitest'
import {
  addArrStep,
  arrOutcome,
  connectProblem,
  connectionChecklist,
  connectionPill,
  defaultName,
  firstTargetChoice,
  folderRows,
  foldersProblem,
  foldersToSave,
  hubBanners,
  hubLede,
  linkProblem,
  linksToSave,
  mappingProblem,
  peopleDraft,
  removeWording,
  rootsLeftOut,
  rowLibrary,
  seerrStep,
  targetProblem,
  targetToSave,
  taskFits,
  testedWording,
  todoCard,
  todoKeys,
  webhookProblem,
} from './integrationsSettings'

const radarr = {
  id: 1,
  kind: 'radarr' as const,
  name: 'Movies',
  url: 'http://radarr.home.example:7878',
  apiKeyHint: 'a41c',
  pathMap: [] as { from: string; to: string }[],
  targets: [] as { libraryId: number; rootFolder: string; qualityProfileId: number }[],
  status: { ok: true, items: 12, version: '5.14.0.9383', webhook: { outside: 0 } as { outside: number; lastTestAt?: string; lastEventAt?: string } },
}
const sonarr = { ...radarr, id: 2, kind: 'sonarr' as const, name: 'TV', url: 'http://sonarr.home.example:8989', status: { ...radarr.status, version: '4.0.9.2244' } }
const seerr = { ...radarr, id: 3, kind: 'seerr' as const, name: 'Seerr', url: 'http://seerr.home.example:5055', status: { ...radarr.status, version: '3.1.0' } }
const libraries = [
  { id: 10, name: 'Movies', path: '/media/movies' },
  { id: 11, name: 'Anime', path: '/media/anime' },
]
const detail = (rootFolders: { path: string; localPath: string; items: number; libraryId?: number }[], suggestions: { from: string; to: string }[] = []) => ({
  ...radarr,
  details: { qualityProfiles: [{ id: 4, name: 'HD-1080p' }], rootFolders, suggestions },
})

describe('addArrStep', () => {
  it('opens Connect until the connection is saved, whatever the address asks for', () => {
    expect(addArrStep(undefined, false).key).toBe('connect')
    expect(addArrStep('webhook', false).key).toBe('connect')
    expect(addArrStep('connected', false).key).toBe('connect')
  })
  it('opens any step once connected, since the middle ones can be skipped', () => {
    expect(addArrStep('webhook', true).key).toBe('webhook')
    expect(addArrStep('connect', true).key).toBe('connect')
    expect(addArrStep('nonsense', true, ['folders']).key).toBe('connected')
  })
  it('opens the first step still to do when the address names none', () => {
    expect(addArrStep(undefined, true, ['requests', 'webhook']).key).toBe('requests')
    expect(addArrStep(undefined, true, []).key).toBe('connected')
  })
})

describe('seerrStep', () => {
  it('opens Connect until Seerr is saved, then Link people', () => {
    expect(seerrStep('people', false).key).toBe('connect')
    expect(seerrStep(undefined, true).key).toBe('people')
    expect(seerrStep('connected', true).key).toBe('connected')
  })
})

describe('taskFits', () => {
  it('gives Seerr people and no folders, and Radarr the reverse', () => {
    expect(taskFits('people', 'seerr')).toBe(true)
    expect(taskFits('folders', 'seerr')).toBe(false)
    expect(taskFits('people', 'radarr')).toBe(false)
    expect(taskFits('webhook', 'sonarr')).toBe(true)
    expect(taskFits('connection', 'seerr')).toBe(true)
    expect(taskFits('other', 'radarr')).toBe(false)
  })
})

describe('defaultName', () => {
  it('names the first Radarr Movies and a second one Movies 2', () => {
    expect(defaultName('radarr', [])).toBe('Movies')
    expect(defaultName('radarr', ['Movies'])).toBe('Movies 2')
    expect(defaultName('sonarr', ['TV', 'TV 2'])).toBe('TV 3')
  })
})

describe('connectionChecklist', () => {
  it('lists all three as to do on a bare connection whose root is outside every library', () => {
    const items = connectionChecklist(radarr, { detail: detail([{ path: '/data/movies', localPath: '/data/movies', items: 3 }]) })
    expect(todoKeys(items)).toEqual(['folders', 'requests', 'webhook'])
  })
  it('counts folders done when every root is in a library, without mappings', () => {
    const items = connectionChecklist(radarr, { detail: detail([{ path: '/media/movies', localPath: '/media/movies', items: 3, libraryId: 10 }]) })
    expect(items[0].state).toBe('done')
  })
  it('counts folders done once mapped, even with a root left out', () => {
    const c = { ...radarr, pathMap: [{ from: '/data/movies', to: '/media/movies' }] }
    expect(connectionChecklist(c, { detail: detail([{ path: '/data/movies-4k', localPath: '/data/movies-4k', items: 1 }]) })[0].state).toBe('done')
  })
  it('says folders need a look when events came for folders outside the libraries', () => {
    const c = { ...radarr, pathMap: [{ from: '/data', to: '/media' }], status: { ...radarr.status, webhook: { outside: 2 } } }
    expect(connectionChecklist(c)[0]).toMatchObject({ state: 'todo', todo: 'Folders need a look' })
  })
  it("doesn't know about folders until the app's folders are read", () => {
    expect(connectionChecklist(radarr)[0].state).toBe('unknown')
  })
  it('ticks requests with a target and the webhook once a test event came', () => {
    const c = { ...radarr, targets: [{ libraryId: 10, rootFolder: '/data/movies', qualityProfileId: 4 }], status: { ...radarr.status, webhook: { outside: 0, lastTestAt: '2026-11-14T10:00:00Z' } } }
    const items = connectionChecklist(c)
    expect(items.map((i) => i.state)).toEqual(['unknown', 'done', 'done'])
  })
  it('counts the people linked to Seerr', () => {
    expect(connectionChecklist(seerr, { people: { linked: 2, total: 3 } })[0]).toMatchObject({ state: 'todo', label: 'People · 2 of 3 linked', todo: '1 person not linked' })
    expect(connectionChecklist(seerr, { people: { linked: 3, total: 3 } })[0].state).toBe('done')
  })
})

describe('connectionPill', () => {
  it('says connected, how many to do, or not answering', () => {
    expect(connectionPill(radarr, [])).toEqual({ tone: 'ok', label: 'Radarr 5.14.0 · Connected' })
    expect(connectionPill(sonarr, connectionChecklist(sonarr))).toEqual({ tone: 'warn', label: 'Sonarr 4.0.9 · 2 to do' })
    expect(connectionPill({ ...radarr, status: { ...radarr.status, ok: false, error: 'connection refused' } }, []).tone).toBe('err')
  })
})

describe('hubBanners', () => {
  it('names a connection that stopped answering and what fails until it is back', () => {
    const down = { ...radarr, status: { ...radarr.status, ok: false, error: 'could not reach the app' } }
    expect(hubBanners([down, sonarr])).toEqual([
      {
        id: 1,
        title: 'Movies stopped answering',
        body: 'Requests for movies will fail until Radarr is back. Check Radarr is running. If its address or key changed, change the connection.',
        said: 'could not reach the app',
      },
    ])
  })
  it("doesn't banner a connection that hasn't been checked yet", () => {
    expect(hubBanners([{ ...radarr, status: { ...radarr.status, ok: false } }])).toEqual([])
  })
})

describe('hubLede', () => {
  it('says who takes requests and whether Seerr is connected', () => {
    const withTarget = (c: typeof radarr | typeof sonarr) => ({ ...c, targets: [{ libraryId: 1, rootFolder: '/x', qualityProfileId: 1 }] })
    expect(hubLede([withTarget(radarr), withTarget(sonarr)])).toBe('Radarr and Sonarr take requests. Seerr isn’t connected.')
    expect(hubLede([radarr])).toBe('Radarr is connected, without a request target yet. Seerr isn’t connected.')
    expect(hubLede([radarr, seerr])).toBe('Requests go through Seerr. Radarr keeps the library up to date.')
    expect(hubLede([])).toMatch(/^Connect the apps/)
  })
})

describe('folderRows', () => {
  it('offers the saved mappings, then suggestions, then roots outside every library', () => {
    const rows = folderRows([], detail(
      [
        { path: '/data/movies', localPath: '/data/movies', items: 3 },
        { path: '/data/movies-4k', localPath: '/data/movies-4k', items: 1 },
      ],
      [{ from: '/data/movies', to: '/media/movies' }],
    ).details)
    expect(rows).toEqual([
      { from: '/data/movies', to: '/media/movies', suggested: true },
      { from: '/data/movies-4k', to: '', root: true },
    ])
  })
  it('gives one empty row to start from when there is nothing', () => {
    expect(folderRows([], detail([]).details)).toEqual([{ from: '', to: '' }])
    expect(folderRows([{ from: '/a', to: '/b' }])).toEqual([{ from: '/a', to: '/b' }])
  })
})

describe('rowLibrary and rootsLeftOut', () => {
  it('finds the library a folder is in, or one inside it', () => {
    expect(rowLibrary({ from: '/data/movies', to: '/media/movies/' }, libraries)?.name).toBe('Movies')
    expect(rowLibrary({ from: '/data', to: '/media' }, libraries)?.name).toBe('Movies')
    expect(rowLibrary({ from: '/data/x', to: '/srv/x' }, libraries)).toBeUndefined()
    expect(rowLibrary({ from: '/data/movies-4k', to: '/media/movies-4k' }, libraries)).toBeUndefined()
  })
  it('lists the roots that stay outside once the rows are saved', () => {
    const roots = [
      { path: '/data/movies', localPath: '/data/movies', items: 3 },
      { path: '/data/movies-4k', localPath: '/data/movies-4k', items: 1 },
    ]
    const rows = [{ from: '/data/movies', to: '/media/movies' }, { from: '/data/movies-4k', to: '', root: true }]
    expect(rootsLeftOut(rows, roots, libraries)).toEqual(['/data/movies-4k'])
  })
})

describe('foldersToSave and mappingProblem', () => {
  it('leaves a root row left empty alone, and trims the rest', () => {
    expect(foldersToSave([{ from: ' /data/movies ', to: '/media/movies' }, { from: '/data/movies-4k', to: '', root: true }, { from: '', to: '' }])).toEqual([{ from: '/data/movies', to: '/media/movies' }])
    expect(mappingProblem([{ from: '/data/movies-4k', to: '', root: true }])).toBeNull()
  })
  it('refuses a mapping with one side typed', () => {
    expect(mappingProblem([{ from: '/data/movies-4k', to: '' }])).toEqual({ title: 'A mapping is missing one side', body: 'Fill in both folders for /data/movies-4k, or remove the row.' })
  })
})

describe('targets', () => {
  const roots = [{ path: '/data/movies', localPath: '/media/movies', items: 3, libraryId: 10 }, { path: '/data/other', localPath: '/data/other', items: 0 }]
  it('replaces the first target, keeping its library', () => {
    expect(targetToSave([{ libraryId: 7, rootFolder: '/old', qualityProfileId: 1 }], { rootFolder: '/data/movies', qualityProfileId: 4 }, roots, [10])).toEqual([{ libraryId: 7, rootFolder: '/data/movies', qualityProfileId: 4 }])
  })
  it("uses the folder's library, else the first library of the kind", () => {
    expect(targetToSave([], { rootFolder: '/data/movies', qualityProfileId: 4 }, roots, [12])[0].libraryId).toBe(10)
    expect(targetToSave([], { rootFolder: '/data/other', qualityProfileId: 4 }, roots, [12])[0].libraryId).toBe(12)
  })
  it('turns requests off when no folder is chosen', () => {
    expect(targetToSave([{ libraryId: 7, rootFolder: '/old', qualityProfileId: 1 }], { rootFolder: '', qualityProfileId: 0 }, roots, [])).toEqual([])
  })
  it('starts on the saved target, else the one root inside a library', () => {
    expect(firstTargetChoice([], roots, [{ id: 4 }])).toEqual({ rootFolder: '/data/movies', qualityProfileId: 4 })
    expect(firstTargetChoice([], roots, [{ id: 4 }, { id: 5 }]).qualityProfileId).toBe(0)
  })
  it('asks for both a folder and a quality', () => {
    expect(targetProblem('radarr', { rootFolder: '/data/movies', qualityProfileId: 0 }, true)).toEqual({
      title: 'Choose both a folder and a quality',
      body: 'A target needs both. Or choose Skip for now and requests for movies stay off.',
    })
    expect(targetProblem('sonarr', { rootFolder: '', qualityProfileId: 0 }, false)).toBeNull()
    expect(targetProblem('sonarr', { rootFolder: '/tv', qualityProfileId: 3 }, true)).toBeNull()
  })
})

describe('peopleDraft and linksToSave', () => {
  const users = [
    { id: 1, name: 'robin', names: ['robin'], canRequest: true },
    { id: 2, name: 'alex', names: ['alex', 'alex@example.com'], canRequest: true },
  ]
  const people = [{ id: 10, name: 'Robin' }, { id: 11, name: 'Alex', seerrUserId: 2 }, { id: 12, name: 'Casey' }]
  it('keeps links and suggests the same name for the rest', () => {
    expect(peopleDraft(people, users)).toEqual({ 10: { seerrUserId: 1, suggested: true }, 11: { seerrUserId: 2, suggested: false }, 12: { seerrUserId: 0, suggested: false } })
  })
  it('suggests a Seerr user to the first person only', () => {
    const d = peopleDraft([{ id: 1, name: 'robin' }, { id: 2, name: 'Robin' }], users)
    expect([d[1].seerrUserId, d[2].seerrUserId]).toEqual([1, 0])
  })
  it('saves only what changed, suggestions included', () => {
    expect(linksToSave(people, peopleDraft(people, users))).toEqual([{ id: 10, seerrUserId: 1 }])
  })
})

describe('connectProblem', () => {
  it('words an old version per app, and Overseerr and Jellyseerr by their versions', () => {
    expect(connectProblem('radarr', 'Radarr 4.7.5.7809 is too old: WatchMyMedia needs Radarr 5 or later')).toEqual({
      title: 'Radarr 4.7.5.7809 is too old',
      body: 'This server needs Radarr 5 or later. Update Radarr, then test again.',
    })
    expect(connectProblem('sonarr', 'the app said: Sonarr 4.0.2 is too old: …').body).toBe('This server needs Sonarr 4.0.6 or later. Update Sonarr, then test again.')
    expect(connectProblem('seerr', 'Seerr 1.33.2 is too old, or this is Overseerr or Jellyseerr: …').title).toBe('That’s Overseerr, which isn’t supported')
    expect(connectProblem('seerr', 'Seerr 2.5.0 is too old, or this is Overseerr or Jellyseerr: …').title).toBe('That’s Jellyseerr, which isn’t supported')
  })
  it('names the other app at the address', () => {
    expect(connectProblem('radarr', 'that address is Sonarr, not radarr')).toEqual({ title: 'That address is Sonarr, not Radarr', body: 'Check the port, or exit and choose Add Sonarr.', field: 'url' })
  })
  it('words a refused key', () => {
    expect(connectProblem('radarr', 'the app said: the API key was refused')).toEqual({ title: 'Radarr refused the API key', body: 'Copy the key again from Radarr › Settings › General › Security.', field: 'apiKey' })
    expect(connectProblem('seerr', 'Seerr said: the API key was refused').body).toBe('Copy the key again from Seerr › Settings › General.')
  })
  it('words an address it could not reach', () => {
    const p = connectProblem('radarr', 'could not reach the app: dial tcp 192.0.2.9:7878: connect: connection refused')
    expect(p.title).toBe('Couldn’t reach Radarr at that address')
    expect(p.body).toMatch(/not localhost\.$/)
    expect(connectProblem('seerr', 'Get "http://x/api/v1/status": context deadline exceeded').body).toBe('Check Seerr is running and that this server can open the address.')
  })
  it('words a second Seerr, a bad address and a key in the address', () => {
    expect(connectProblem('seerr', 'Seerr is connected already; change that connection instead').title).toBe('Seerr is already connected')
    expect(connectProblem('radarr', 'the address must be http:// or https:// with a host').field).toBe('url')
    expect(connectProblem('radarr', 'put the API key in its own field, not in the address').body).toBe('Put the API key in its own field, not in the address.')
    expect(connectProblem('seerr', "that address doesn't answer as Seerr").title).toBe('That address isn’t Seerr')
  })
  it('falls back to the server’s words as a sentence', () => {
    expect(connectProblem('radarr', 'something odd')).toEqual({ title: 'Couldn’t connect Radarr', body: 'Something odd.' })
  })
  it('says what answered when Test works', () => {
    expect(testedWording('Radarr 5.14.0.9383')).toEqual({ title: 'Radarr 5.14.0 answered', body: 'The address and key work. Nothing is saved yet.' })
  })
})

describe('foldersProblem', () => {
  it('words each refusal', () => {
    expect(foldersProblem('each path mapping needs both folders').title).toBe('A mapping is missing one side')
    expect(foldersProblem('"media/movies" is not a full path on this server').title).toBe('media/movies isn’t a full path on this server')
    expect(foldersProblem('disk on fire').body).toBe('Disk on fire.')
  })
})

describe('webhookProblem', () => {
  it('names the address the app could not open', () => {
    expect(webhookProblem('radarr', 'http://192.0.2.5:8096', 502, 'the app said: Unable to send test message')).toMatchObject({
      title: 'Radarr couldn’t open http://192.0.2.5:8096',
      field: 'address',
      detail: 'Unable to send test message',
    })
  })
  it('says when the app itself is out of reach', () => {
    expect(webhookProblem('sonarr', 'http://x', 502, 'could not reach the app: dial tcp: i/o timeout').title).toBe('Couldn’t reach Sonarr')
  })
  it('says when the address is not one', () => {
    expect(webhookProblem('sonarr', 'nas', 422, 'the address must be http:// or https:// with a host').title).toBe('That isn’t an address')
  })
})

describe('linkProblem', () => {
  it('names the Seerr user that is gone', () => {
    expect(linkProblem('Seerr has no user 2', 'Alex', [{ id: 2, name: 'alex', names: [], canRequest: true }])).toEqual({ title: 'Seerr has no user “alex” any more', body: 'The list was refreshed. Choose again for Alex.' })
  })
})

describe('removeWording', () => {
  it('says what stays and stops for Radarr', () => {
    const w = removeWording(radarr)
    expect(w.title).toBe('Remove Movies (Radarr)?')
    expect(w.stays).toEqual(['Radarr and everything in it, your library, and requests already made.'])
    expect(w.stops).toEqual(['Movie requests, missing-movie searches and instant scans.', 'Radarr’s webhook will be refused, so remove it in Radarr › Settings › Connect.'])
  })
  it('says requests go back to Radarr and Sonarr for Seerr', () => {
    const w = removeWording(seerr)
    expect(w.title).toBe('Remove Seerr?')
    expect(w.stops[0]).toMatch(/approval comes back to this server/)
  })
})

describe('arrOutcome', () => {
  it('lists what changed and what happens next', () => {
    const c = {
      ...radarr,
      pathMap: [{ from: '/data/movies', to: '/media/movies' }],
      targets: [{ libraryId: 10, rootFolder: '/data/movies', qualityProfileId: 4 }],
      status: { ...radarr.status, webhook: { outside: 0, lastTestAt: '2026-11-14T10:00:00Z' } },
    }
    expect(arrOutcome(c, { rootsLeft: ['/data/movies-4k'], profileName: 'HD-1080p', approval: 'admins' })).toEqual({
      changed: ['Connected at radarr.home.example:7878.', '1 folder mapped. /data/movies-4k is left unmapped.', 'Movie requests go to /data/movies at HD-1080p.', 'The webhook is on.'],
      next: [
        'People can request movies from Search. Admins’ requests go straight to Radarr. Everyone else’s wait in the Inbox.',
        'Imports and renames show up at once.',
        'The connection is checked every 15 minutes, and the Integrations page says if it stops answering.',
      ],
    })
  })
  it('says what was skipped', () => {
    const o = arrOutcome(sonarr, {})
    expect(o.changed).toContain('There’s no request target yet, so people can’t request shows.')
    expect(o.changed).toContain('The webhook isn’t connected yet.')
  })
})

describe('todoCard', () => {
  it('words the first thing to do on a connection page', () => {
    expect(todoCard(sonarr, 'webhook')).toEqual({ title: 'Connect the webhook', body: 'Until then, new episodes show up at the next scan, up to 15 minutes later.', action: 'Connect webhook' })
    expect(todoCard(radarr, 'requests')?.body).toBe('Until then, people can’t request movies.')
    expect(todoCard({ ...radarr, status: { ...radarr.status, webhook: { outside: 3 } } }, 'folders')?.title).toBe('Check the folders')
    expect(todoCard(radarr, 'nothing')).toBeNull()
  })
})
