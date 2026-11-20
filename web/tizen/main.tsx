import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { Input } from './app/input'
import { REGISTERED_KEYS } from './app/keys'
import { exitApp, loadWebApis, onTV, refreshScreenSaver, registerKeys, tvModel } from './app/platform'
import { AppSession } from './session/session'
import { AVPlayPlayer, type Player, type SamsungWindow } from './player'
import './ui/tokens.css'
import './ui/kit.css'
import './screens/start/start.css'
import './screens/placeholders.css'

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
 * The app's one player, made once: AVPlay on the TV; in the development
 * preview only, the browser's video (imported so the TV build leaves it
 * out); none where neither is there, as when the TV build opens in a
 * desktop browser, which can sign in and browse but not play.
 */
async function makePlayer(): Promise<Player | undefined> {
  const avplay = (window as SamsungWindow).webapis?.avplay
  if (avplay) {
    document.querySelector('#browser-player')?.remove()
    return new AVPlayPlayer(avplay)
  }
  if (!import.meta.env.DEV) return undefined
  const { BrowserPlayer } = await import('./player/browser-player')
  document.querySelector('#av-player')?.remove()
  return new BrowserPlayer(document.querySelector<HTMLVideoElement>('#browser-player')!)
}

async function start() {
  // Samsung's webapis.js (AVPlay, the screensaver, the model) comes with the packaged app only.
  if (!import.meta.env.DEV || onTV()) await loadWebApis().catch(() => undefined)
  registerKeys(REGISTERED_KEYS)
  const fakes = fakesMode()
  let initialAddress = ''
  if (fakes !== undefined) {
    const { installFakes } = await import('./fakes')
    initialAddress = installFakes(fakes, appStorage())
  } else if (import.meta.env.DEV) {
    // The fixture preview: this page's own address, which Vite proxies to the fixture server.
    initialAddress = location.origin
  }
  const app = new AppSession(appStorage(), { model: tvModel(), initialAddress })
  const player = await makePlayer()
  const input = new Input(document.body)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) app.suspend()
    else app.resume()
    refreshScreenSaver()
  })
  window.addEventListener('pagehide', () => app.shutdown())
  const root = createRoot(document.getElementById('root')!)
  const exit = () => {
    app.shutdown()
    refreshScreenSaver()
    if (exitApp()) return
    root.unmount()
    input.dispose()
    closed('Preview closed', 'Reload this page to open WatchMyMedia again.')
  }
  root.render(<App app={app} input={input} player={player} exit={exit}/>)
  app.start()
  refreshScreenSaver()
}

void start().catch(() => closed('WatchMyMedia couldn’t start.', 'This needs app storage and the Samsung TV’s web APIs. For a browser preview, run pnpm dev:tizen.'))
