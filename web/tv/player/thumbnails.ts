import { SheetLoader, sheetUrl, tileAt, tileBackground, type Trickplay } from '../../src/lib/trickplay'

/** One seek thumbnail, ready to draw: a box of width × height showing background. */
export type Thumbnail = {
  positionMs: number
  width: number
  height: number
  /** Its sheet has come; until then the box shows nothing. */
  loaded: boolean
  background: { backgroundImage: string; backgroundSize: string; backgroundPosition: string }
}

/**
 * The seek thumbnails of a decision's manifest: which
 * tile shows a time, and its sheet, fetched by the shared SheetLoader. The
 * sheets go through the TV's media URLs, signed when they are asked for.
 */
export class Thumbnails {
  private readonly loader: SheetLoader

  constructor(private readonly manifest: Trickplay, private readonly sign: (path: string) => string, loadImage: (url: string) => Promise<void>, onLoad: () => void) {
    this.loader = new SheetLoader(manifest, (path) => loadImage(sign(path)), onLoad)
  }

  /** A scrub starts at positionMs: its sheet and the ones either side. */
  scrubFrom(positionMs: number) {
    this.loader.scrubFrom(tileAt(this.manifest, positionMs))
  }

  at(positionMs: number): Thumbnail {
    const tile = tileAt(this.manifest, positionMs)
    this.loader.want(tile.sheet)
    const signed = { ...this.manifest, url: this.sign(sheetUrl(this.manifest, tile.sheet)) }
    return { positionMs, width: this.manifest.width, height: this.manifest.height, loaded: this.loader.loaded(tile.sheet), background: tileBackground(signed, tile) }
  }
}
