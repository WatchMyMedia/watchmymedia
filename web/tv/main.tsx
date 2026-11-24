import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { Input } from './app/input'
import { PointerInput } from './app/pointer'
import { platform } from './platform/current'
import { AppSession } from './session/session'
import type { Player } from './player'
import './ui/tokens.css'
import './ui/kit.css'
import './screens/start/start.css'

function appStorage(): Storage | undefined {
  try { return window.localStorage } catch { return undefined }
}

/** The browser preview's fictional server (`?fakes`, or VITE_TIZEN_FAKES=1). Never in the TV build. */
function fakesMode(): string | undefined {
  if (!import.meta.env.DEV) return undefined
  const asked = new URLSearchParams(location.search).get('fakes')
  return asked ?? (import.meta.env.VITE_TIZEN_FAKES ? '' : undefined)
}

function closed(title: string, message: string) {
  document.documentElement.classList.remove('playing-surface')
  // Text only; never a native error or an address with a token.
  const root = document.getElementById('root')!
  root.replaceChildren()
  const section = document.createElement('section')
  section.className = 'tv-boot'
  const inner = document.createElement('div')
  inner.className = 'tv-boot__inner'
  const heading = document.createElement('h1')
  heading.className = 'tv-h1'
  heading.textContent = title
  const copy = document.createElement('p')
  copy.textContent = message
  inner.append(heading, copy)
  section.append(inner)
  root.append(section)
}

/**
 * The app's one player, made once: the TV's own (the platform's: AVPlay on
 * a Samsung TV); in the development preview only, the browser's video
 * (imported so the TV build leaves it out), or on the fakes a simulated
 * stream; none where neither is there, as when the TV build opens in a
 * desktop browser, which can sign in and browse but not play.
 */
async function makePlayer(fakes: boolean): Promise<Player | undefined> {
  const tvPlayer = platform.createPlayer()
  if (tvPlayer) return tvPlayer
  if (!import.meta.env.DEV) return undefined
  // The fictional server has no media a <video> could open: a simulated stream plays instead.
  if (fakes) {
    document.querySelector('#av-player')?.remove()
    const { previewPlayer } = await import('./fakes/player')
    return previewPlayer(document.querySelector<HTMLVideoElement>('#browser-player') ?? undefined)
  }
  const { BrowserPlayer } = await import('./player/browser-player')
  document.querySelector('#av-player')?.remove()
  return new BrowserPlayer(document.querySelector<HTMLVideoElement>('#browser-player')!)
}

async function start() {
  await platform.start()
  const fakes = fakesMode()
  let initialAddress = ''
  if (fakes !== undefined) {
    const { installFakes } = await import('./fakes')
    initialAddress = installFakes(fakes, appStorage())
  } else if (import.meta.env.DEV) {
    // The fixture preview: this page's own address, which Vite proxies to the fixture server.
    initialAddress = location.origin
  }
  const app = new AppSession(appStorage(), { model: platform.model(), initialAddress })
  const player = await makePlayer(fakes !== undefined)
  const input = new Input(document.body)
  // A remote that points (LG's Magic Remote): pointing focuses, a click presses, the wheel steps.
  const pointer = platform.pointer ? new PointerInput(input) : undefined
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) app.suspend()
    else app.resume()
    platform.refreshScreenSaver()
  })
  window.addEventListener('pagehide', () => app.shutdown())
  const root = createRoot(document.getElementById('root')!)
  const exit = () => {
    app.shutdown()
    platform.refreshScreenSaver()
    if (platform.exit()) return
    root.unmount()
    pointer?.dispose()
    input.dispose()
    closed('Preview closed', 'Reload this page to open WatchMyMedia again.')
  }
  root.render(<App app={app} input={input} player={player} exit={exit}/>)
  app.start()
  platform.refreshScreenSaver()
}

void start().catch(() => closed('WatchMyMedia couldn’t start.', `This needs app storage and the ${platform.words.tv}’s web APIs. For a browser preview, run ${platform.words.preview}.`))
