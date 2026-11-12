export type ImageSize = 'w92' | 'w154' | 'w185' | 'w300' | 'w342' | 'w500' | 'w780' | 'w1280' | 'original'

/** Builds the server URL for a TMDB image path such as "/abc.jpg". */
export function img(path: string | undefined, size: ImageSize): string | undefined {
  if (!path) return undefined
  return `/api/images/${size}${path.startsWith('/') ? path : `/${path}`}`
}
