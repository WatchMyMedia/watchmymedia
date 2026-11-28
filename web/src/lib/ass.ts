import { useEffect, useMemo, useRef, type RefObject } from 'react'

type Renderer = {
  ready: Promise<void>
  renderer: { setTrackByUrl(url: string): Promise<void> }
  destroy(): Promise<void>
  /** Seconds added to the video's time before drawing (JASSUB 2.x keeps it as a plain field). */
  timeOffset: number
  _demandRender?: (repaint?: boolean) => Promise<void>
  _worker?: Worker
}

/** Stops a renderer, even one whose worker never became ready (destroy alone would wait for it forever). */
function stop(r: Renderer) {
  void r.destroy().catch(() => {})
  r._worker?.terminate()
}

/**
 * Renders ASS/SSA subtitles over the video with JASSUB (libass in WASM).
 * Loaded on first use so the main bundle stays small; falls back silently
 * when the renderer cannot start. JASSUB finds its own worker, wasm and
 * default font: the bundler emits them from its `new URL(…, import.meta.url)`
 * references, and passing the files in by hand pointed it at the wrong worker.
 */
export function useAssRenderer(videoRef: RefObject<HTMLVideoElement | null>) {
  const renderer = useRef<Renderer | null>(null)
  const currentUrl = useRef<string>('')
  // A subtitle timing: JASSUB draws at the video's time plus its offset, so
  // cues shown 0.4 s later are drawn from 0.4 s earlier in the track.
  const offset = useRef(0)

  useEffect(
    () => () => {
      if (renderer.current) stop(renderer.current)
      renderer.current = null
    },
    [],
  )

  return useMemo(
    () => ({
      show: (url: string) => {
        if (currentUrl.current === url) return
        currentUrl.current = url
        void (async () => {
          const video = videoRef.current
          if (!video) return
          try {
            if (renderer.current) {
              await renderer.current.ready
              await renderer.current.renderer.setTrackByUrl(url)
              return
            }
            const { default: JASSUB } = await import('jassub')
            // hide() or another show() while the module loaded.
            if (currentUrl.current !== url || renderer.current) return
            renderer.current = new JASSUB({ video, subUrl: url, timeOffset: offset.current }) as unknown as Renderer
          } catch (e) {
            console.warn('ASS renderer unavailable', e)
          }
        })()
      },
      hide: () => {
        currentUrl.current = ''
        if (renderer.current) stop(renderer.current)
        renderer.current = null
      },
      /** Moves the cues by a subtitle timing in milliseconds (positive later), drawing again at once while paused. */
      setTiming: (ms: number) => {
        const seconds = -ms / 1000
        if (offset.current === seconds) return
        offset.current = seconds
        const r = renderer.current
        if (!r) return
        r.timeOffset = seconds
        void r._demandRender?.(true)?.catch(() => undefined)
      },
    }),
    [videoRef],
  )
}
