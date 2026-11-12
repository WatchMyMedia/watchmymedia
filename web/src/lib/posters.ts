// The rules behind Change poster (PLAN-POSTERS.md): what the dialog accepts
// before it asks the server, and what it says about a picture's shape.

/** The server's limit on an uploaded picture. */
export const MAX_POSTER_BYTES = 20 * 1024 * 1024

const TYPES = ['image/jpeg', 'image/png', 'image/webp']

export const NOT_A_PICTURE = 'That file isn’t a picture we can use (JPEG, PNG or WebP).'
export const TOO_LARGE = 'That picture is too large (20 MB at most).'

/**
 * Why a chosen file can't be a poster, or nothing when it can be sent. A
 * file without a type (some drags have none) is left for the server, which
 * reads the bytes.
 */
export function pictureProblem(file: { type: string; size: number }): string | undefined {
  if (file.type && !TYPES.includes(file.type)) return NOT_A_PICTURE
  if (file.size > MAX_POSTER_BYTES) return TOO_LARGE
  return undefined
}

/**
 * What to say when a picture isn't poster-shaped. Posters are drawn 2:3 and
 * cropped to fill, so a wider picture loses its sides and a taller one its
 * top and bottom. Within a tenth of 2:3 nothing worth saying is lost.
 */
export function cropNote(width: number, height: number): string | undefined {
  if (!(width > 0) || !(height > 0)) return undefined
  const off = width / height / (2 / 3)
  if (off > 1.1) return 'This picture is wider than a poster. Its sides will be cut off.'
  if (off < 0.9) return 'This picture is taller than a poster. Its top and bottom will be cut off.'
  return undefined
}

/** Whether text is a link the server would try: http or https with a host. */
export function isPictureLink(text: string): boolean {
  try {
    const u = new URL(text.trim())
    return (u.protocol === 'http:' || u.protocol === 'https:') && u.hostname !== ''
  } catch {
    return false
  }
}

/** The first picture in a paste or drop, if it carried one. */
export function firstPicture(files: ArrayLike<File> | undefined | null): File | undefined {
  if (!files) return undefined
  return Array.from(files).find((f) => f.type.startsWith('image/')) ?? files[0]
}

/** What a failed save says: the server's own words, except where it has none. */
export function saveProblem(status: number, detail: string): string {
  if (status === 413) return TOO_LARGE
  return detail
}
