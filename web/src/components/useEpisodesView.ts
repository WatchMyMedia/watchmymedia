import { useState } from 'react'

export type EpisodesView = 'list' | 'strip'

const KEY = 'wmm.episodesView'

/** How a show's episodes are laid out, a list with synopses or a strip of stills, remembered on this browser for every show. */
export function useEpisodesView(): [EpisodesView, (v: EpisodesView) => void] {
  const [view, setView] = useState<EpisodesView>(() => {
    try {
      return localStorage.getItem(KEY) === 'strip' ? 'strip' : 'list'
    } catch {
      return 'list'
    }
  })
  return [
    view,
    (v) => {
      setView(v)
      try {
        localStorage.setItem(KEY, v)
      } catch {
        // Storage refused: the choice holds for the visit.
      }
    },
  ]
}
