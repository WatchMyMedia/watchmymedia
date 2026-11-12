let generation = 0

/** Async replies captured before a sign-in change may not repopulate its cache. */
export function authScope() { return generation }
export function changeAuthScope() { generation++ }
