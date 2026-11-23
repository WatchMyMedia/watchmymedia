import type { components } from '../../../src/api/schema'

// What a Continue watching card's actions say.

type ContinueItem = components['schemas']['ContinueItem']

export function continueMenuWords(item: { kind: ContinueItem['kind'] }): { open: string; note: string; removed: string } {
  return {
    open: item.kind === 'episode' ? 'Episodes' : 'More info',
    note: 'Removing it keeps your place. It comes back when you play it again.',
    removed: 'Removed from Continue watching. Your place is kept.',
  }
}
