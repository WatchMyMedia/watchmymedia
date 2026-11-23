// The boards' placeholder gradients, for cards without a picture (the fakes,
// unmatched files): one pair per title, the same every time.
const TONES: [string, string][] = [
  ['#1F3A4D', '#0B161D'], ['#4A2C3A', '#1A0E14'], ['#3D4A2B', '#131A0E'], ['#4D3A1F', '#1C140A'],
  ['#2B2F4A', '#0F111C'], ['#4A2B2B', '#1A0E0E'], ['#2F4A44', '#0E1B18'],
]

export function placeholderTone(title: string): [string, string] {
  let h = 0
  for (let i = 0; i < title.length; i++) h = (h * 31 + title.charCodeAt(i)) | 0
  return TONES[Math.abs(h) % TONES.length]
}

/** How many cards of a kind fit across a row beside the rail, whole (four 16:9 cards or six posters, the next peeking): for drawing only what is near the view. */
export const CARDS_IN_VIEW: Record<'poster' | 'wide' | 'episode' | 'continue' | 'live', number> = {
  poster: 6, wide: 4, episode: 4, continue: 4, live: 4,
}
