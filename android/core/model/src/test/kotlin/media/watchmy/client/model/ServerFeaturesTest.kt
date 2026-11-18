package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFeaturesTest {
    @Test
    fun `each feature starts at its level`() {
        val features = mapOf<String, Pair<Int, (ServerFeatures) -> Boolean>>(
            "castTokens" to (2 to { it.castTokens }),
            "automaticQuality" to (3 to { it.automaticQuality }),
            "requests" to (5 to { it.requests }),
            "delete" to (6 to { it.delete }),
            "hdrProfile" to (8 to { it.hdrProfile }),
            "trickplay" to (9 to { it.trickplay }),
            "offline" to (10 to { it.offline }),
            "liveTv" to (11 to { it.liveTv }),
            "liveGuide" to (13 to { it.liveGuide }),
            "liveCast" to (14 to { it.liveCast }),
            "livePersonal" to (15 to { it.livePersonal }),
            "subtitlePreferences" to (18 to { it.subtitlePreferences }),
            "liveRecordings" to (19 to { it.liveRecordings }),
            "trailers" to (20 to { it.trailers }),
            "audioLanguages" to (21 to { it.audioLanguages }),
            "rows" to (22 to { it.rows }),
            "autoRecovery" to (24 to { it.autoRecovery }),
            "liveProfile" to (25 to { it.liveProfile }),
            "liveAutomatic" to (26 to { it.liveAutomatic }),
            "tokenWithDecision" to (28 to { it.tokenWithDecision }),
            "liveStatusWaits" to (28 to { it.liveStatusWaits }),
            "pcmProfile" to (31 to { it.pcmProfile }),
        )
        for ((name, entry) in features) {
            val (level, has) = entry
            assertFalse("$name below $level", has(ServerFeatures(level - 1)))
            assertTrue("$name at $level", has(ServerFeatures(level)))
            assertTrue("$name above $level", has(ServerFeatures(level + 1)))
        }
        assertEquals(0, ServerFeatures(0).level)
    }
}
