package media.watchmy.client.baselineprofile

import android.graphics.Rect
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
import java.util.regex.Pattern

/*
 * What people do first in the app, on the fakes (-Pfakes=true, README
 * "Baseline profile"): the sample library, no server. The phone journeys
 * sign in, browse and play; the TV ones (-PforceTv=true) pair, browse with
 * the D-pad and play. The fakes' player has no video, so playing runs the
 * player's controls and pickers, not ExoPlayer.
 */

/** The app under test: the instrumentation passes its package (build.gradle.kts). */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")?.takeIf { it.isNotEmpty() } ?: "media.watchmy.client"

private const val WAIT_MS = 10_000L

/** The fake pairing signs the TV in after a few seconds. */
private const val PAIRING_MS = 20_000L

/** Waits for [selector] and returns it; fails the journey when it doesn't come. */
private fun UiDevice.waitFor(selector: BySelector, timeoutMs: Long = WAIT_MS): UiObject2 =
    wait(Until.findObject(selector), timeoutMs) ?: error("Nothing on screen matches $selector")

private fun UiDevice.waitForText(text: String, timeoutMs: Long = WAIT_MS) = waitFor(By.text(text), timeoutMs)

/** Of the elements matching [selector], the lowest on screen: the bottom bar's "TV Shows", the sign-in button. */
private fun UiDevice.lowest(selector: BySelector): UiObject2 =
    findObjects(selector).maxByOrNull { it.visibleBounds.top } ?: error("Nothing on screen matches $selector")

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

/** Flings [list] along and back to its start, as a person scanning it would. */
private fun UiDevice.scan(list: UiObject2, forward: Direction, back: Direction) {
    // Clear of the system's edge gestures.
    list.setGestureMargin(displayWidth / 5)
    repeat(2) { list.fling(forward) }
    waitForIdle()
    // One more than forward: a fling back can stop short of the start.
    repeat(3) { list.fling(back) }
    waitForIdle()
}

/** True when the app shows the TV UI (its pairing screen), false for the phone's sign-in. */
fun MacrobenchmarkScope.showsTv(): Boolean {
    val tv = By.textStartsWith("Link this TV")
    val phone = By.clazz("android.widget.EditText")
    repeat((WAIT_MS / 500).toInt()) {
        if (device.hasObject(tv)) return true
        if (device.hasObject(phone)) return false
        device.waitForIdle(500)
    }
    error("Neither the TV's pairing screen nor the phone's sign-in showed")
}

/** Signs in on the fakes: the TV pairs by itself; the phone takes any name. Ends on Home. */
fun MacrobenchmarkScope.signIn(tv: Boolean) = if (tv) tvPairedHome() else phoneSignIn()

// --- phone ---

/** The sign-in screen the fakes start on: any name, the password "password". */
fun MacrobenchmarkScope.phoneSignIn() {
    val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT_MS) ?: error("No sign-in fields")
    fields[0].text = "Sam"
    fields[1].text = "password"
    device.lowest(By.text("Sign in")).click()
    device.waitForText("Continue watching")
}

/** Home: down the rows and back, then along Continue watching. */
fun MacrobenchmarkScope.phoneHome() {
    val page = device.waitFor(By.scrollable(true).hasDescendant(By.text("Continue watching")))
    val row = device.scrollable { all -> all.firstOrNull { it.bounds.height() < it.bounds.width() / 2 } }
    row?.let { device.scan(it, Direction.RIGHT, Direction.LEFT) }
    device.scan(page, Direction.DOWN, Direction.UP)
}

/** TV Shows: the grid, then a show's page. */
fun MacrobenchmarkScope.phoneLibraryAndShow() {
    device.lowest(By.text("TV Shows")).click()
    device.waitFor(By.textContains(" shows · "))
    val grid = device.scrollable { all -> all.maxByOrNull { it.bounds.height() } } ?: error("No library grid")
    device.scan(grid, Direction.DOWN, Direction.UP)
    device.waitForText("Harbor Lights").click()
    val page = device.waitFor(By.scrollable(true).hasDescendant(By.textStartsWith("Play next")))
    device.scan(page, Direction.DOWN, Direction.UP)
}

/** From a show's page: Play next, the controls, the Subtitles picker, and back out. */
fun MacrobenchmarkScope.phonePlay() {
    device.waitFor(By.textStartsWith("Play next")).click()
    // The system's first-time note about full screen, on a fresh device.
    device.wait(Until.findObject(By.text("Got it")), 3_000)?.click()
    device.waitFor(By.desc("Back"))
    // Let the controls hide, then bring them back.
    device.wait(Until.gone(By.desc("Back")), WAIT_MS)
    device.click(device.displayWidth / 2, device.displayHeight / 3)
    device.waitForText("Subtitles").click()
    device.waitForIdle()
    // Back closes the picker, then leaves the player for the show's page.
    backTo(By.textStartsWith("Play next"))
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
    device.waitForText("Continue watching", PAIRING_MS)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.press(times: Int, key: () -> Unit) = repeat(times) {
    key()
    device.waitForIdle()
}

/** Home with the remote: from the hero down to Continue watching, along it, down the rows and back up. */
fun MacrobenchmarkScope.tvHome() {
    // The first key may only take the screen out of touch mode, so the counts allow one spare.
    press(1) { device.pressDPadDown() }
    press(4) { device.pressDPadRight() }
    press(3) { device.pressDPadDown() }
    press(5) { device.pressDPadUp() }
}

/** The show page's main button: "Play S01 E01", "Play next · S03 E05" or "Resume · …". */
private val PLAY = By.text(Pattern.compile("(Play|Resume).*"))

/** The rail to TV Shows, along and down its grid, then a show's page. */
fun MacrobenchmarkScope.tvLibraryAndShow() {
    val library = By.textContains(" shows · ")
    for (attempt in 1..2) {
        // Left along the hero's buttons opens the rail on Home (more Lefts stay there); TV Shows is below it.
        press(3) { device.pressDPadLeft() }
        press(1) { device.pressDPadDown() }
        device.pressDPadCenter()
        if (device.wait(Until.hasObject(library), 5_000) == true) break
        // Focus was elsewhere and Centre opened something: back to Home and again.
        backTo(By.text("Continue watching"))
        press(5) { device.pressDPadUp() }
    }
    device.waitFor(library)
    // From the filters down into the grid, along it and down, then a show.
    press(3) { device.pressDPadDown() }
    press(3) { device.pressDPadRight() }
    press(2) { device.pressDPadDown() }
    press(1) { device.pressDPadUp() }
    device.pressDPadCenter()
    device.waitFor(PLAY)
}

/** From a show's page: play, seek with the remote, open the subtitles picker, and back out. */
fun MacrobenchmarkScope.tvPlay() {
    device.waitFor(PLAY).click()
    device.waitFor(By.descStartsWith("Position"))
    press(2) { device.pressDPadRight() }
    press(1) { device.pressKeyCode(KeyEvent.KEYCODE_CAPTIONS) }
    press(2) { device.pressDPadDown() }
    // Back closes the picker, then leaves the player for the show's page.
    backTo(PLAY)
    device.pressBack()
}
