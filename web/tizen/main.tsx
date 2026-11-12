import { createRoot } from 'react-dom/client'
import { App } from './App'
import { AppModel } from './app-model'
import { AVPlayPlayer, type Player, type SamsungWindow } from './avplay'
import './app.css'

async function start() {
  const samsung = window as SamsungWindow
  if (!import.meta.env.DEV && !samsung.webapis?.avplay) {
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      const timeout = setTimeout(() => reject(new Error('Samsung API timeout')), 10_000)
      script.src = '$WEBAPIS/webapis/webapis.js'
      script.onload = () => { clearTimeout(timeout); resolve() }
      script.onerror = () => { clearTimeout(timeout); reject(new Error('Samsung APIs unavailable')) }
      document.head.append(script)
    })
  }
  let player: Player
  if (samsung.webapis?.avplay) {
    document.querySelector('#browser-player')?.remove()
    player = new AVPlayPlayer(samsung.webapis.avplay, (event) => model?.onPlayerUpdate(event))
  }
  else if (import.meta.env.DEV) {
    const { BrowserPlayer } = await import('./browser-player')
    document.querySelector('#av-player')?.remove()
    player = new BrowserPlayer(document.querySelector<HTMLVideoElement>('#browser-player')!, (event) => model?.onPlayerUpdate(event))
  } else throw new Error('Samsung AVPlay unavailable')
  const model = new AppModel(localStorage, player, import.meta.env.DEV ? location.origin : '')
  for (const key of ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaRewind', 'MediaFastForward']) {
    try { samsung.tizen?.tvinputdevice.registerKey(key) } catch { /* Unsupported optional key; onscreen controls remain usable. */ }
  }
  let previous: boolean | undefined
  const saver = (force = false) => {
    const playing = !document.hidden && model.snapshot().playback.state === 'playing'
    if (!force && playing === previous) return
    previous = playing
    const common = samsung.webapis?.appcommon
    if (common) {
      const states = common.AppCommonScreenSaverState
      try { common.setScreenSaver(playing ? states.SCREEN_SAVER_OFF : states.SCREEN_SAVER_ON, () => {}, () => {}) } catch { /* Verify on hardware; no native exception text is logged. */ }
    }
  }
  model.subscribe(() => saver())
  document.addEventListener('visibilitychange', () => { if (document.hidden) model.suspend(); saver(true) })
  window.addEventListener('pagehide', () => { model.suspend(); saver(true) })
  const reactRoot = createRoot(document.getElementById('root')!)
  reactRoot.render(<App model={model} exit={() => {
    void model.shutdown()
    saver(true)
    reactRoot.unmount()
    if (samsung.tizen) samsung.tizen.application.getCurrentApplication().exit()
    else createRootView('Preview closed', 'Reload this page to reopen the prototype.')
  }}/>)
  model.start(); saver(true)
}

function createRootView(title: string, message: string) {
  document.documentElement.classList.remove('playing-surface')
  // Text-only fallback; never surface native errors or URLs with tokens.
  const root = document.getElementById('root')!
  root.replaceChildren()
  const section = document.createElement('section'); section.className = 'boot-error'
  const heading = document.createElement('h1'); heading.textContent = title
  const copy = document.createElement('p'); copy.textContent = message
  section.append(heading, copy); root.append(section)
}
void start().catch(() => createRootView('The prototype could not start.', 'This build needs Samsung AVPlay and app storage. For a browser preview, run pnpm dev:tizen. Signing and TV testing are still separate steps.'))
