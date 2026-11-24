// What a server's API level lets the app offer. A feature from a later level
// turns on by the server's own `apiLevel`, and below it the app asks that
// server nothing new and behaves as it did.

/**
 * API level 34: who is in a title (`Credit.personId`, a person's titles),
 * Remove from Continue watching, the libraries' counts, combined filters and
 * the Rated filter, recordings' New, watched and time left, and a title's
 * copies found by its TMDB id, which one card across servers needs.
 */
export const LEVEL_34 = 34

export function from34(apiLevel: number | undefined): boolean {
  return (apiLevel ?? 0) >= LEVEL_34
}
