// What was typed in a Search, kept while pages open on top of it so that
// coming back finds the same results. A switch made from a search result
// puts the words there for the new server's Search, which the title then
// sits on: Return from the title asks them again, on that server first.

let kept: { scope: string; text: string } | undefined

/** One Search on one session: the session's number and the screen's place in the stack. */
export const searchScope = (serial: number, entryKey: number | undefined) => `${serial}/${entryKey}`

export function keepSearch(scope: string, text: string) { kept = { scope, text } }

export function keptSearch(scope: string): string { return kept?.scope === scope ? kept.text : '' }
