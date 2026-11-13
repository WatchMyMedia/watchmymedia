package media.watchmy.client.baselineprofile

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

/*
 * What people do first in the app (README, "Baseline profile"). On the fakes
 * (-Pfakes=true): the sample library, no server. The phone journeys sign in,
 * browse and play; the TV ones (-PforceTv=true) pair, browse with the D-pad
 * and play. The fakes' player has no video, so playing there runs the
 * player's controls and pickers, not ExoPlayer. On a real build with a
 * fixture server (the fixtureServer argument, fixture_server.py), the phone
 * journey signs in to it, loads Home's posters and the Movies grid, and plays
 * a file through ExoPlayer: the data layer, the image loader and the player.
 *
 * Elements are found by the test tags in core:ui's TestTags, which
 * MainActivity exposes as resource ids, rather than by their wording.
 */

/** The app under test: the instrumentation passes its package (build.gradle.kts). */
val targetPackage: String
    get() = argument("targetAppId") ?: "media.watchmy.client"

/**
 * The fixture server a real build signs in to, as typed in the server
 * picker (`10.0.2.2:28130` from an emulator), or null on the fakes. Passed as
 * `-Pandroid.testInstrumentationRunnerArguments.fixtureServer=…`.
 */
val fixtureServer: String?
    get() = argument("fixtureServer")

/** The fixture's account the real-build journeys sign in as (deploy/fixture/fixture.sh). */
private val fixtureUser: String get() = argument("fixtureUser") ?: "viewer"
private val fixturePassword: String get() = argument("fixturePassword") ?: "fixture-viewer-1"

private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotEmpty() }

/** core:ui's TestTags, as the journeys find them; keep the two in step. */
internal object Tags {
    const val TV = "tv"
    const val HOME = "home"
    const val PLAY = "play"
    const val GUIDE = "guide"
    const val LIBRARY = "library"
    const val SERVER_ADDRESS = "server-address"
    const val SERVER_CONNECT = "server-connect"
    const val SIGN_IN_NAME = "sign-in-name"
    const val SIGN_IN_PASSWORD = "sign-in-password"
    const val SIGN_IN = "sign-in"
}

/** An element by its test tag. */
internal fun tag(name: String): BySelector = By.res(name)

private const val WAIT_MS = 10_000L

/** Home from a server: the first load, with its images, on a busy emulator. */
internal const val LOAD_MS = 20_000L

/** The fake pairing signs the TV in after a few seconds. */
private const val PAIRING_MS = 20_000L

/**
 * Fails the journey, leaving what was on screen in the test's output
 * (baselineprofile/build/outputs/connected_android_test_additional_output/):
 * journey-failure.png and journey-failure.xml, the window's hierarchy.
 */
internal fun UiDevice.fail(message: String): Nothing {
    argument("additionalTestOutputDir")?.let { dir ->
        runCatching {
            takeScreenshot(File(dir, "journey-failure.png"))
            dumpWindowHierarchy(File(dir, "journey-failure.xml"))
        }
    }
    error(message)
}

/** Waits for [selector] and returns it; fails the journey when it doesn't come. */
internal fun UiDevice.waitFor(selector: BySelector, timeoutMs: Long = WAIT_MS): UiObject2 =
    wait(Until.findObject(selector), timeoutMs) ?: fail("Nothing on screen matches $selector")

private fun UiDevice.waitForText(text: String, timeoutMs: Long = WAIT_MS) = waitFor(By.text(text), timeoutMs)

/** Of the elements matching [selector], the lowest on screen: the bottom bar's "TV Shows". */
private fun UiDevice.lowest(selector: BySelector): UiObject2 =
    findObjects(selector).maxByOrNull { it.visibleBounds.top } ?: fail("Nothing on screen matches $selector")

private val UiObject2.bounds: Rect get() = visibleBounds

/**
 * The scrollable [pick] chooses, once the screen has settled: what is on
 * screen while it still changes goes stale between finding and measuring.
 */
private fun UiDevice.scrollable(pick: (List<UiObject2>) -> UiObject2?): UiObject2? {
    repeat(3) {
        waitForIdle()
        try {
            return pick(findObjects(By.scrollable(true)))
        } catch (_: StaleObjectException) {
            // Changed under us: look again.
        }
    }
    return null
}

/** The tallest list on screen, or in [within]: a page, rather than a row of cards on it. */
private fun UiDevice.tallestList(within: UiObject2? = null): UiObject2? =
    (within?.findObjects(By.scrollable(true)) ?: findObjects(By.scrollable(true))).maxByOrNull { it.bounds.height() }

/**
 * [selector], once on screen: scrolled to, toward [direction], when it is
 * below (or above) the fold, as the picker's Connect and sign-in's button can
 * be on a short landscape screen, and as a page's Play is after a scan.
 */
private fun UiDevice.reveal(selector: BySelector, direction: Direction = Direction.DOWN): UiObject2 {
    if (!hasObject(selector)) tallestList()?.scrollUntil(direction, Until.findObject(selector))
    return waitFor(selector)
}

/** A card's long-press menu ("Mark as watched", "Go to movie"). */
private val CARD_MENU = By.text(Pattern.compile("Mark as (un)?watched"))

/**
 * Closes a card's menu that a fling opened: on a busy emulator a fling's
 * touches can arrive slowly enough to count as a long press on the card
 * under them.
 */
private fun UiDevice.closeCardMenu() {
    if (hasObject(CARD_MENU)) {
        pressBack()
        wait(Until.gone(CARD_MENU), WAIT_MS)
    }
}

/**
 * Flings the list [find] finds along and back to its start, as a person
 * scanning it would. The list is found again for each fling: a fling back
 * past the top pulls to refresh, which can replace what was found.
 */
private fun UiDevice.scan(forward: Direction, back: Direction, find: UiDevice.() -> UiObject2?) {
    fun fling(direction: Direction) {
        repeat(3) {
            try {
                val list = find() ?: return
                // Clear of the system's edge gestures, and along the fling only: a margin across a short row
                // leaves a swipe of a few pixels, which is a tap on whatever card is under it.
                val height = list.visibleBounds.height()
                val across = if (direction == Direction.LEFT || direction == Direction.RIGHT) height / 4 else height / 5
                list.setGestureMargins(displayWidth / 5, across, displayWidth / 5, across)
                list.fling(direction)
                closeCardMenu()
                return
            } catch (_: StaleObjectException) {
                waitForIdle()
            }
        }
    }
    repeat(2) { fling(forward) }
    waitForIdle()
    // One more than forward: a fling back can stop short of the start.
    repeat(3) { fling(back) }
    waitForIdle()
}

/** What the app opens on. */
enum class FirstScreen { TvPairing, ServerPicker, SignIn, Home }

/** The screen the app shows after a start, waiting for one to settle. */
fun UiDevice.firstScreen(): FirstScreen {
    val screens = listOf(
        FirstScreen.TvPairing to By.textStartsWith("Link this TV"),
        FirstScreen.ServerPicker to tag(Tags.SERVER_ADDRESS),
        FirstScreen.SignIn to tag(Tags.SIGN_IN_NAME),
        FirstScreen.Home to tag(Tags.HOME),
    )
    repeat((LOAD_MS / 500).toInt()) {
        screens.firstOrNull { (_, selector) -> hasObject(selector) }?.let { return it.first }
        waitForIdle(500)
    }
    fail("None of the TV's pairing, the server picker, sign-in or Home showed")
}

/**
 * True when the app shows the TV UI, false for the phone's, once a first
 * screen has come: the TV's root carries the tv tag. (The fake pairing can
 * sign the TV in before the journey sees the pairing screen.)
 */
fun UiDevice.showsTv(): Boolean {
    firstScreen()
    return hasObject(tag(Tags.TV))
}

/** [UiDevice.showsTv] for the journeys. */
fun MacrobenchmarkScope.showsTv(): Boolean = device.showsTv()

/**
 * Signs in and ends on Home: on the fakes the TV pairs by itself and the
 * phone takes any name; a real build signs in to [fixtureServer].
 */
fun MacrobenchmarkScope.signIn(tv: Boolean) = when {
    tv -> tvPairedHome()
    fixtureServer != null -> fixtureSignIn()
    else -> phoneSignIn("Sam", "password")
}

// --- phone ---

/** The sign-in screen: name and password, then Home. */
fun MacrobenchmarkScope.phoneSignIn(name: String, password: String) {
    device.reveal(tag(Tags.SIGN_IN_NAME)).text = name
    device.reveal(tag(Tags.SIGN_IN_PASSWORD)).text = password
    device.reveal(tag(Tags.SIGN_IN)).click()
    device.waitFor(tag(Tags.HOME), LOAD_MS)
}

/**
 * A real build's start: the server picker (typing [fixtureServer], never a
 * server discovery finds), then sign-in, or Home when already signed in.
 */
fun MacrobenchmarkScope.fixtureSignIn() {
    val server = fixtureServer ?: error("No fixtureServer argument: the real-build journeys need a fixture server")
    when (device.firstScreen()) {
        FirstScreen.ServerPicker -> {
            device.reveal(tag(Tags.SERVER_ADDRESS)).text = server
            device.reveal(tag(Tags.SERVER_CONNECT)).click()
            phoneSignIn(fixtureUser, fixturePassword)
        }
        FirstScreen.SignIn -> phoneSignIn(fixtureUser, fixturePassword)
        FirstScreen.Home -> Unit
        FirstScreen.TvPairing -> error("The real-build journeys run on a phone build")
    }
}

/** Home: along the first row and back, then down the rows and back. */
fun MacrobenchmarkScope.phoneHome() {
    device.waitFor(tag(Tags.HOME), LOAD_MS)
    device.scan(Direction.RIGHT, Direction.LEFT) { scrollable { all -> all.firstOrNull { it.bounds.height() < it.bounds.width() / 2 } } }
    // Home's page: the tallest list in it (its rows scroll too).
    device.scan(Direction.DOWN, Direction.UP) { findObject(tag(Tags.HOME))?.let { tallestList(it) } }
}

/** A library from the bottom bar ("TV Shows", "Movies"): down its grid and back, then [title]'s page. */
private fun MacrobenchmarkScope.phoneLibrary(section: String, title: String) {
    device.lowest(By.text(section)).click()
    device.waitFor(tag(Tags.LIBRARY), LOAD_MS)
    device.scan(Direction.DOWN, Direction.UP) { findObject(tag(Tags.LIBRARY)) }
    device.waitFor(By.textStartsWith(title)).click()
    device.waitFor(tag(Tags.PLAY), LOAD_MS)
    device.scan(Direction.DOWN, Direction.UP) { tallestList() }
}

/** TV Shows: the grid, then a show's page. */
fun MacrobenchmarkScope.phoneLibraryAndShow() = phoneLibrary("TV Shows", "Harbor Lights")

/** A real build's Movies: the grid's posters, then a movie's page. */
fun MacrobenchmarkScope.fixtureMovies() = phoneLibrary("Movies", "Paper Moons")

/**
 * From a show's or a movie's page: Play, the controls, the Subtitles picker,
 * and back out. On a real build the file plays through ExoPlayer meanwhile.
 */
fun MacrobenchmarkScope.phonePlay() {
    // The scan can stop short of the top, and a page's list composes only what is on screen.
    device.reveal(tag(Tags.PLAY), Direction.UP).click()
    // The system's first-time note about full screen, on a fresh device.
    device.wait(Until.findObject(By.text("Got it")), 3_000)?.click()
    // The controls show as it starts and hide while it plays, on a busy emulator sometimes before the
    // journey looks: let them hide, then bring them back unless they are up.
    device.wait(Until.hasObject(By.desc("Back")), LOAD_MS)
    device.wait(Until.gone(By.desc("Back")), WAIT_MS)
    if (!device.hasObject(By.text("Subtitles"))) device.click(device.displayWidth / 2, device.displayHeight / 3)
    device.waitForText("Subtitles").click()
    device.waitForIdle()
    // Back closes the picker, then leaves the player for the page.
    backTo(tag(Tags.PLAY))
    device.pressBack()
}

/** Presses Back until [selector] shows, a few times at most. */
private fun MacrobenchmarkScope.backTo(selector: BySelector) {
    repeat(4) {
        if (device.wait(Until.hasObject(selector), 2_000) == true) return
        device.pressBack()
        device.waitForIdle()
    }
    device.waitFor(selector)
}

// --- TV ---

/** The pairing screen until the fakes sign in, then Home. */
fun MacrobenchmarkScope.tvPairedHome() {
    device.waitFor(tag(Tags.HOME), PAIRING_MS)
    device.waitForIdle()
}

/**
 * Presses [key] [times], letting the screen settle after each. Accessibility
 * goes idle while a row, the rail or a pager still slides, and a key pressed
 * then can land elsewhere: the TV journey reached Settings instead of TV Shows
 * on a busy emulator, where the same keys a second apart did not.
 */
private fun MacrobenchmarkScope.press(times: Int, key: () -> Unit) = repeat(times) {
    key()
    device.waitForIdle()
    SystemClock.sleep(SETTLE_MS)
}

/** How long each key's slide is given to finish, with room for a busy emulator. */
private const val SETTLE_MS = 500L

/** Home with the remote: from the hero down to Continue watching, along it, down the rows and back up. */
fun MacrobenchmarkScope.tvHome() {
    // The first key may only take the screen out of touch mode, so the counts allow one spare.
    press(1) { device.pressDPadDown() }
    press(4) { device.pressDPadRight() }
    press(3) { device.pressDPadDown() }
    press(5) { device.pressDPadUp() }
}

/** The rail to a section [below] Home (1 is TV Shows, 3 is Live TV), from Home. */
private fun MacrobenchmarkScope.tvRailTo(below: Int, arrived: BySelector) {
    for (attempt in 1..2) {
        // Left along the hero's buttons opens the rail on Home (more Lefts stay there); the sections are below it.
        press(3) { device.pressDPadLeft() }
        press(below) { device.pressDPadDown() }
        device.pressDPadCenter()
        if (device.wait(Until.hasObject(arrived), 5_000) == true) return
        // Focus was elsewhere and Centre opened something: back to Home and again.
        backTo(tag(Tags.HOME))
        press(5) { device.pressDPadUp() }
    }
    device.waitFor(arrived)
}

/** The rail to TV Shows, along and down its grid, then a show's page. */
fun MacrobenchmarkScope.tvLibraryAndShow() {
    tvRailTo(1, By.textContains(" shows · "))
    // From the filters down into the grid, along it and down, then a show.
    press(3) { device.pressDPadDown() }
    press(3) { device.pressDPadRight() }
    press(2) { device.pressDPadDown() }
    press(1) { device.pressDPadUp() }
    device.pressDPadCenter()
    device.waitFor(tag(Tags.PLAY))
}

/** From a show's page: play, seek with the remote, open the subtitles picker, and back out. */
fun MacrobenchmarkScope.tvPlay() {
    device.waitFor(tag(Tags.PLAY)).click()
    device.waitFor(By.descStartsWith("Position"))
    press(2) { device.pressDPadRight() }
    press(1) { device.pressKeyCode(KeyEvent.KEYCODE_CAPTIONS) }
    press(2) { device.pressDPadDown() }
    // Back closes the picker, then leaves the player for the show's page.
    backTo(tag(Tags.PLAY))
    device.pressBack()
}

/** The rail to Live TV's guide (the fakes have listings), from Home, and down into its programmes. */
fun MacrobenchmarkScope.tvGuide() {
    tvRailTo(3, tag(Tags.GUIDE))
    press(2) { device.pressDPadDown() }
}

/** Through the guide with the remote: down the channels, along the programmes, and back to where it began. */
fun MacrobenchmarkScope.tvGuideMoves() {
    press(6) { device.pressDPadDown() }
    press(4) { device.pressDPadRight() }
    press(6) { device.pressDPadUp() }
    press(4) { device.pressDPadLeft() }
}
