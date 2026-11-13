"""Tests for baseline_profile.py, the check that the Android app's profiles are current."""

import os
import tempfile
import unittest

import baseline_profile as bp


class TopLevelTest(unittest.TestCase):
    def test_inner_classes_lambdas_and_synthetics_count_as_their_class(self):
        for name in (
            "media/watchmy/client/mobile/home/HomeScreenKt",
            "media/watchmy/client/mobile/home/HomeScreenKt$HomeScreen$1$2",
            "media/watchmy/client/mobile/home/HomeScreenKt$$ExternalSyntheticLambda7",
            "media/watchmy/client/mobile/home/ComposableSingletons$HomeScreenKt",
            "media/watchmy/client/mobile/home/ComposableSingletons$HomeScreenKt$lambda$1",
        ):
            self.assertEqual("media/watchmy/client/mobile/home/HomeScreenKt", bp.top_level(name), name)

    def test_profile_rules_count_only_the_apps_classes(self):
        text = "\n".join([
            "Lmedia/watchmy/client/LocalNetworkGateKt;",
            "HSPLmedia/watchmy/client/LocalNetworkGateKt;->LocalNetworkGate(ZLkotlin/jvm/functions/Function2;)V",
            "PLmedia/watchmy/client/tv/TvAppKt$$ExternalSyntheticLambda7;-><init>()V",
            "Landroidx/compose/runtime/Composer;",
            "HSPLokhttp3/OkHttpClient;-><init>()V",
            "",
        ])
        self.assertEqual(
            {"media/watchmy/client/LocalNetworkGateKt": 2, "media/watchmy/client/tv/TvAppKt": 1},
            bp.profile_rules(text),
        )


class SourceClassesTest(unittest.TestCase):
    def test_a_kotlin_file_gives_its_facade_and_its_declarations(self):
        text = """
package media.watchmy.client.tv.detail

import androidx.compose.runtime.Composable

/** A class mentioned in a comment: class NotThis. */
@Immutable
data class PlayAction(val label: String)

internal sealed interface Pick

private enum class Side { Left, Right }

object Labels {
    class Nested
}

fun interface Callback { fun call() }

typealias Old = PlayAction
"""
        self.assertEqual(
            {
                "media/watchmy/client/tv/detail/" + n
                for n in ("ShowPageScreenKt", "PlayAction", "Pick", "Side", "Labels", "Nested", "Callback")
            },
            bp.source_classes(text, "ShowPageScreen.kt"),
        )

    def test_jvm_name_replaces_the_facade(self):
        text = '@file:JvmName("Wording")\npackage media.watchmy.client.model\n\nfun label() = ""\n'
        self.assertEqual({"media/watchmy/client/model/Wording"}, bp.source_classes(text, "Labels.kt"))

    def test_a_java_file_gives_its_class(self):
        self.assertEqual(
            {"media/watchmy/client/Old"},
            bp.source_classes("package media.watchmy.client;\n\npublic final class Old {}\n", "Old.java"),
        )


class CheckTest(unittest.TestCase):
    def test_generated_classes_are_never_missing(self):
        named = {"media/watchmy/client/R", "media/watchmy/client/BuildConfig", "media/watchmy/client/Gone"}
        self.assertEqual(["media/watchmy/client/Gone"], bp.missing(named, set()))

    def test_a_few_stale_rules_pass_and_a_screens_worth_fails(self):
        self.assertFalse(bp.stale(49, 100))
        self.assertFalse(bp.stale(60, 2000))
        self.assertTrue(bp.stale(461, 6708))

    def test_main_reads_the_profiles_and_the_sources(self):
        with tempfile.TemporaryDirectory() as android:
            src = os.path.join(android, "mobile", "src", "main", "kotlin", "media", "watchmy", "client", "mobile")
            os.makedirs(src)
            with open(os.path.join(src, "HomeScreen.kt"), "w", encoding="utf-8") as f:
                f.write("package media.watchmy.client.mobile\n\nfun HomeScreen() {}\n")
            built = os.path.join(android, "mobile", "build", "generated")
            os.makedirs(built)
            with open(os.path.join(built, "ShowScreen.kt"), "w", encoding="utf-8") as f:
                f.write("package media.watchmy.client.mobile\n\nfun ShowScreen() {}\n")
            profiles = os.path.join(android, bp.PROFILE_DIR)
            os.makedirs(profiles)

            def write(home, show):
                rules = ["HSPLmedia/watchmy/client/mobile/HomeScreenKt$a;->b()V"] * home
                rules += ["HSPLmedia/watchmy/client/mobile/ShowScreenKt$a;->b()V"] * show
                for name in bp.PROFILES:
                    with open(os.path.join(profiles, name), "w", encoding="utf-8") as f:
                        f.write("\n".join(rules))

            # A file under build/ is not a source: ShowScreenKt is gone.
            write(home=1000, show=10)
            self.assertEqual(0, bp.main(["baseline_profile.py", android]))
            write(home=1000, show=100)
            self.assertEqual(1, bp.main(["baseline_profile.py", android]))


if __name__ == "__main__":
    unittest.main()
