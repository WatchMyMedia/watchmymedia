package media.watchmy.client.tv

import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices

/**
 * The TV fakes without their Live TV reminders. The fakes set two for
 * tonight from the wall clock (Dollar Dash and Station 9), and while one is
 * due its banner takes Back first, as it does on a TV: a test that presses
 * Back then would dismiss the banner instead. Call with `fakeLatencyMs` at 0.
 * [several] false is a TV signed in to one server, where signing out goes
 * to its sign-in.
 */
internal fun quietTvFakes(several: Boolean = true): FakeAppServices = FakeAppServices(tv = true, several = several).also { services ->
    runBlocking { services.live.refreshReminders()?.items?.forEach { services.live.forget(it.id) } }
    check(services.live.reminders.value?.items.isNullOrEmpty()) { "The fakes still have reminders" }
}
