// The on-screen keyboard Search and a watchlist's name type with: four rows
// of ten keys, then Shift, the symbols, Space, Delete and Clear. The TV's
// own keyboard (accents, other alphabets, the voice key) is one key below.

export type KeyboardMode = 'letters' | 'symbols'

const LETTERS = ['1234567890', 'qwertyuiop', 'asdfghjkl\'', 'zxcvbnm,.-']
const SYMBOLS = ['1234567890', '!@#$%&*()?', '/:;"+=_~<>', '[]{}^`|\\£€']

/** The keys' characters, row by row, for a mode; letters in capitals with Shift. */
export function keyRows(mode: KeyboardMode, shift: boolean): string[][] {
  const rows = mode === 'symbols' ? SYMBOLS : LETTERS
  return rows.map((r) => [...(shift && mode === 'letters' ? r.toUpperCase() : r)])
}

export type KeyPress = { kind: 'char'; char: string } | { kind: 'space' } | { kind: 'delete' } | { kind: 'clear' }

/** The longest query search takes. */
export const MAX_QUERY = 100

/**
 * The text after a key: a character or a space at the end (never two
 * spaces, or one first), Delete takes the last off, Clear empties it. It
 * stops at `max` characters (a search's, or a watchlist's name's).
 */
export function typeKey(text: string, press: KeyPress, max = MAX_QUERY): string {
  switch (press.kind) {
    case 'clear': return ''
    case 'delete': return [...text].slice(0, -1).join('')
    case 'space': return !text || text.endsWith(' ') || text.length >= max ? text : `${text} `
    case 'char': return text.length >= max ? text : text + press.char
  }
}
