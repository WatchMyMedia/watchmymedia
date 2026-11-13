package media.watchmy.client.ui

/**
 * Test tags on the elements the baseline-profile journeys and benchmarks
 * find (`:baselineprofile`, Journeys.kt). MainActivity turns on
 * `testTagsAsResourceId`, so UI Automator sees each as a resource id
 * (`By.res(...)`) whatever the element's wording. The journeys spell the same
 * strings: rename one in both places, or the journeys stop finding it.
 */
object TestTags {
    /** Home once it has loaded from the server: its rows (and on a phone, an empty library's message). */
    const val HOME = "home"

    /** The main Play (or Resume) button of a show's or a movie's page. */
    const val PLAY = "play"

    /** The TV Live TV guide's channel rows. */
    const val GUIDE = "guide"

    /** The phone's and tablet's library grid, with or without arranged rows above it. */
    const val LIBRARY = "library"

    /** The phone server picker's address field and its Connect button. */
    const val SERVER_ADDRESS = "server-address"
    const val SERVER_CONNECT = "server-connect"

    /** The phone sign-in's fields and its button. */
    const val SIGN_IN_NAME = "sign-in-name"
    const val SIGN_IN_PASSWORD = "sign-in-password"
    const val SIGN_IN = "sign-in"
}
