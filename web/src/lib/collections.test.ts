import { describe, expect, it } from 'vitest'
import { collectionHave, collectionLine, collectionPartWords, yearsWords } from './collections'

describe('collections', () => {
  it('words the years', () => {
    expect(yearsWords(1995, 2003)).toBe('1995–2003')
    expect(yearsWords(2019, 2019)).toBe('2019')
    expect(yearsWords(undefined, 2019)).toBe('2019')
    expect(yearsWords()).toBe('')
  })

  it('says how much of a collection the library has', () => {
    expect(collectionLine({ count: 2, total: 3, firstYear: 1995, lastYear: 2003 })).toBe('2 of 3 · 1995–2003')
    expect(collectionLine({ count: 4 })).toBe('4 films')
    expect(collectionHave({ count: 3, total: 3 })).toBe('You have all three')
    expect(collectionHave({ count: 2, total: 2 })).toBe('You have both')
    expect(collectionHave({ count: 12, total: 12 })).toBe('You have all 12')
    expect(collectionHave({ count: 2, total: 5 })).toBe('You have 2 of 5')
    expect(collectionHave({ count: 2 })).toBe('You have 2 films')
  })

  it('says where a movie sits in its collection', () => {
    expect(collectionPartWords({ id: 1, name: 'Fathoms', part: 2, total: 3, have: 3 })).toBe('Part 2 of 3 · you have all three')
    expect(collectionPartWords({ id: 1, name: 'Fathoms', part: 1, total: 3, have: 2 })).toBe('Part 1 of 3 · you have 2 of 3')
    expect(collectionPartWords({ id: 1, name: 'Fathoms', have: 2 })).toBe('')
    expect(collectionPartWords(undefined)).toBe('')
  })
})
