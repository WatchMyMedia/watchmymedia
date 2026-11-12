import { describe, expect, it } from 'vitest'
import { cropNote, firstPicture, isPictureLink, MAX_POSTER_BYTES, NOT_A_PICTURE, pictureProblem, saveProblem, TOO_LARGE } from './posters'

describe('pictureProblem', () => {
  it('accepts the three kinds the server keeps', () => {
    for (const type of ['image/jpeg', 'image/png', 'image/webp']) expect(pictureProblem({ type, size: 1000 })).toBeUndefined()
  })
  it('refuses other files by type', () => {
    expect(pictureProblem({ type: 'image/gif', size: 10 })).toBe(NOT_A_PICTURE)
    expect(pictureProblem({ type: 'application/pdf', size: 10 })).toBe(NOT_A_PICTURE)
  })
  it('leaves a file with no type to the server', () => {
    expect(pictureProblem({ type: '', size: 10 })).toBeUndefined()
  })
  it('refuses a picture over the limit, and takes one at it', () => {
    expect(pictureProblem({ type: 'image/png', size: MAX_POSTER_BYTES })).toBeUndefined()
    expect(pictureProblem({ type: 'image/png', size: MAX_POSTER_BYTES + 1 })).toBe(TOO_LARGE)
  })
})

describe('cropNote', () => {
  it('says nothing for a poster shape, or close to one', () => {
    expect(cropNote(1000, 1500)).toBeUndefined()
    expect(cropNote(1000, 1426)).toBeUndefined() // 27 x 40 one-sheets
  })
  it('names what is lost', () => {
    expect(cropNote(1920, 1080)).toMatch(/sides/)
    expect(cropNote(1000, 1000)).toMatch(/sides/)
    expect(cropNote(600, 1800)).toMatch(/top and bottom/)
  })
  it('says nothing before the size is known', () => {
    expect(cropNote(0, 0)).toBeUndefined()
  })
})

describe('isPictureLink', () => {
  it('takes http and https links', () => {
    expect(isPictureLink('https://posters.example/a.jpg')).toBe(true)
    expect(isPictureLink('  http://posters.example/a.jpg ')).toBe(true)
  })
  it('refuses the rest', () => {
    for (const text of ['', 'posters.example/a.jpg', 'ftp://posters.example/a.jpg', 'file:///a.jpg', 'https://', 'data:image/png;base64,AAAA']) {
      expect(isPictureLink(text)).toBe(false)
    }
  })
})

describe('firstPicture', () => {
  const file = (name: string, type: string) => new File(['x'], name, { type })
  it('prefers a picture among what was dropped', () => {
    expect(firstPicture([file('notes.txt', 'text/plain'), file('a.png', 'image/png')])?.name).toBe('a.png')
  })
  it('falls back to the first file, for the server to judge', () => {
    expect(firstPicture([file('a.bin', '')])?.name).toBe('a.bin')
    expect(firstPicture([])).toBeUndefined()
    expect(firstPicture(null)).toBeUndefined()
  })
})

describe('saveProblem', () => {
  it('words a body the server cut off', () => {
    expect(saveProblem(413, 'request body is too large')).toBe(TOO_LARGE)
  })
  it('keeps the server’s words otherwise', () => {
    expect(saveProblem(422, 'That link points to this network, which isn’t allowed.')).toMatch(/this network/)
  })
})
