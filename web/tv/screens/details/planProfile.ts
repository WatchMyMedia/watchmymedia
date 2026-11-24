import { platform } from '../../platform/current'
import type { Profile } from '../../player/api'

// The TV's profile the title pages ask the server's plans with, once the
// TV's APIs have loaded: HDR10 where the TV shows it. One for the app, as it
// never changes while the app runs.
let profile: Profile | undefined

export const planProfile = () => (profile ??= platform.profiles.file({ hdr10: platform.takesHdr10() }))
