import { useEffect } from 'react'

// The video plane: AVPlay draws under the web page (and the preview's
// <video> sits under #root), so the page must be see-through while a player
// shows video. `.playing-surface` on <html> (ui/kit.css) makes html, body
// and #root transparent and shows the plane; a player screen holds it while
// it is on top, and the app is opaque again once no player holds it.

let holders = 0

function apply() {
  document.documentElement.classList.toggle('playing-surface', holders > 0)
}

/** Makes the page see-through over the video plane; call the returned function to let go. */
export function holdVideoSurface(): () => void {
  holders++
  apply()
  let held = true
  return () => {
    if (!held) return
    held = false
    holders--
    apply()
  }
}

/** Holds the video surface while mounted (and `on`): for the player screens, which are drawn only while on top. */
export function useVideoSurface(on = true) {
  useEffect(() => on ? holdVideoSurface() : undefined, [on])
}
