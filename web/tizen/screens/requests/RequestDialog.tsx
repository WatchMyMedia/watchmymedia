import { Dialog } from '../../ui/Overlays'

export type RequestTarget = {
  kind: 'movie' | 'tv'
  tmdbId: number
  title: string
  /** For a show: the seasons asked for; none asks for the whole show. */
  seasons?: number[]
}

/**
 * The request dialog (Requests on the canvas), over the screen that opens
 * it: search's Not in your library, a show's missing seasons and episodes,
 * a list's missing titles. The search and settings feature draws it; until
 * then it says so and closes.
 */
export function RequestDialog({ target, onClose }: { target: RequestTarget; onClose: (requested: boolean) => void }) {
  return <Dialog title={`Request ${target.title}`} onCancel={() => onClose(false)} actions={[
    { id: 'request/close', label: 'Close', safe: true, onPress: () => onClose(false) },
  ]}>
    <p className="tv-body">Requests come with the search and settings feature.</p>
  </Dialog>
}
