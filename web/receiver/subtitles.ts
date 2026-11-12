import type { components } from '../src/api/schema'
import type { LoadData } from '../src/cast/protocol'

type Decision = components['schemas']['Decision']

/** A separately deployed receiver must never send newer fields to old servers. */
export function subtitleRequest(load: LoadData, apiLevel: number) {
  const subtitleMode = apiLevel >= 18 && (load.subtitleMode === 'auto' || load.subtitleMode === 'off')
    ? load.subtitleMode : undefined
  return { subtitleId: load.subtitleId ?? undefined, subtitleMode }
}

/** New senders opt in; manual picks and legacy senders keep their own selection. */
export function startingSubtitle(load: LoadData, decision: Pick<Decision, 'subtitleId' | 'subtitles'>, apiLevel: number): string | undefined {
  if (load.subtitleId) return load.subtitleId
  if (apiLevel < 18 || load.subtitleMode !== 'auto') return undefined
  const selected = decision.subtitleId
  return selected && decision.subtitles.some((track) => track.id === selected) ? selected : undefined
}

/** A manual choice applies to this episode, including through quality/audio reloads. */
export function manualSubtitleSelection(load: LoadData, id: string | null): LoadData {
  return { ...load, subtitleId: id, subtitleMode: 'off' }
}

/** Only a sender that originally opted in restarts defaults on the next episode. */
export function nextEpisodeSubtitles(load: LoadData, originalMode: LoadData['subtitleMode']): LoadData {
  return { ...load, subtitleId: null, subtitleMode: originalMode }
}
