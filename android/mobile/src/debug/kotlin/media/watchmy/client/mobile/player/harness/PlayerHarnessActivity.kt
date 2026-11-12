package media.watchmy.client.mobile.player.harness

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import kotlinx.coroutines.delay
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeCastService
import media.watchmy.client.fake.FakeLiveCastRemote
import media.watchmy.client.mobile.live.LivePlayerScreen
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.ui.LocalAppServices
import androidx.compose.runtime.remember
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.mobile.player.PlayerScreen
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.CastService
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import media.watchmy.client.ui.WmmColors

/**
 * Debug builds only: [PlayerScreen] on the fakes, so the phone player can
 * be tried before browsing leads to it. Extras: `scenario` (a [Scenario]
 * name, default Controls) and `speed` (how fast the fake clock runs).
 * Leaving the player shows a list of scenarios to start again.
 */
class PlayerHarnessActivity : ComponentActivity() {
    private var scenario by mutableStateOf<Scenario?>(Scenario.Controls)
    private lateinit var services: HarnessServices

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        services = HarnessServices(FakeAppServices(), intent.getFloatExtra(EXTRA_SPEED, 1f))
        scenario = Scenario.from(intent.getStringExtra(EXTRA_SCENARIO)) ?: Scenario.Controls
        addOnNewIntentListener(Consumer<Intent> { scenario = Scenario.from(it.getStringExtra(EXTRA_SCENARIO)) ?: Scenario.Controls })
        setContent {
            ProvideAppServices(services) {
                WatchMyMediaPhoneTheme {
                    val current = scenario
                    if (current != null) {
                        services.scenario = current
                        // Handover: a TV turns up a few seconds into playing here.
                        LaunchedEffect(current) {
                            if (current in listOf(Scenario.Handover, Scenario.LiveHandover, Scenario.LiveNotLive)) {
                                delay(4_000)
                                (services.cast as? FakeCastService)?.connect()
                            }
                            if (current == Scenario.LiveStopped) {
                                delay(5_000)
                                (services.cast as? FakeCastService)?.live?.adminStop()
                            }
                        }
                        key(current) {
                            if (current.live) LiveHarness(current, onExit = { scenario = null })
                            else PlayerScreen(request(current), onExit = { scenario = null })
                        }
                    } else {
                        ScenarioList(onPick = { scenario = it })
                    }
                }
            }
        }
    }

    private fun request(s: Scenario) = PlayRequest(fileId = 9101, startMs = s.startMs, title = "Harbor Lights", subtitle = "S03 · E07 · The Long Dark")

    private companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_SPEED = "speed"
    }
}

private class HarnessServices(fakes: FakeAppServices, private val speed: Float) : AppServices by fakes {
    var scenario = Scenario.Controls
        set(value) {
            if (value != field) cast = castFor(value)
            field = value
        }
    override val playback = PlaybackControllerFactory { HarnessPlaybackController(scenario, FakePlaybackController(speed = speed)) }

    /** The casting scenarios have a TV around (connected from the start for Casting); the others none. */
    override var cast: CastService = castFor(scenario)
        private set

    private fun castFor(s: Scenario): CastService = when (s) {
        Scenario.Casting -> FakeCastService(connected = true, player = FakePlaybackController(speed = speed))
        Scenario.Handover -> FakeCastService(connected = false, player = FakePlaybackController(speed = speed))
        Scenario.LiveCasting, Scenario.LiveStopped -> FakeCastService("Living Room TV", connected = true)
        Scenario.LiveHandover -> FakeCastService("Living Room TV", connected = false)
        Scenario.LiveNotLive -> FakeCastService("Living Room TV", connected = false, live = FakeLiveCastRemote(receiverLive = false))
        Scenario.LiveBusy -> FakeCastService("Living Room TV", connected = true, live = FakeLiveCastRemote(busy = setOf(LIVE_TARGET)))
        else -> CastService.None
    }
}

/** The channel the Live TV scenarios open: 4.1, as the LiveCast board draws. */
private const val LIVE_TARGET = "live-4-1"

/** The live player on the fakes' channels and listings; the remote from the start where a TV is connected. */
@Composable
private fun LiveHarness(scenario: Scenario, onExit: () -> Unit) {
    val services = LocalAppServices.current
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    // The fake sign-in may still be finishing: ask until the channels come.
    LaunchedEffect(services) {
        while (channels.isEmpty()) {
            channels = services.live.channels()
            if (channels.isEmpty()) delay(500)
        }
    }
    val target = channels.firstOrNull { it.id == LIVE_TARGET } ?: return
    val onTv = scenario in listOf(Scenario.LiveCasting, Scenario.LiveBusy, Scenario.LiveStopped)
    LivePlayerScreen(LiveTarget(target), channels, onExit = onExit, listings = true, castable = true, onTv = onTv)
}

@Composable
private fun ScenarioList(onPick: (Scenario) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(WmmColors.Ground).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Player harness", style = MaterialTheme.typography.headlineMedium, color = WmmColors.Text)
        Text("The phone player on the fake episode. Pick a scenario.", style = MaterialTheme.typography.bodyMedium, color = WmmColors.TextMuted)
        Scenario.entries.forEach { s ->
            OutlinedButton(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) { Text(s.title, color = WmmColors.Text) }
        }
    }
}
