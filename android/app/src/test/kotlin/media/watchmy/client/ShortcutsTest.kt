package media.watchmy.client

import android.app.Application
import android.content.res.XmlResourceParser
import media.watchmy.client.model.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launcher's shortcuts (res/xml/shortcuts.xml, PLAN-ANDROID-REVIEW
 * §6.3): Search, Live TV and Downloads, each a link the app opens, aimed at
 * this build's package and MainActivity, and named in the manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShortcutsTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private data class Shortcut(val id: String, val label: String, val data: String, val target: String)

    private fun shortcuts(): List<Shortcut> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val found = mutableListOf<Shortcut>()
        var id = ""
        var label = ""
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType != XmlResourceParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> {
                    id = parser.getAttributeValue(ANDROID, "shortcutId")
                    label = context.getString(parser.getAttributeResourceValue(ANDROID, "shortcutShortLabel", 0))
                }
                "intent" -> {
                    val pkg = parser.getAttributeResourceValue(ANDROID, "targetPackage", 0).let { if (it != 0) context.getString(it) else parser.getAttributeValue(ANDROID, "targetPackage") }
                    found += Shortcut(id, label, parser.getAttributeValue(ANDROID, "data"), "$pkg/${parser.getAttributeValue(ANDROID, "targetClass")}")
                }
            }
        }
        return found
    }

    @Test fun `Search, Live TV and Downloads open their links in this build's MainActivity`() {
        val all = shortcuts()
        assertEquals(listOf("Search", "Live TV", "Downloads"), all.map { it.label })
        assertEquals(listOf(DeepLink.Search, DeepLink.LiveGuide, DeepLink.Downloads), all.map { DeepLink.open(it.data, null) })
        all.forEach { assertEquals(it.id, "${context.packageName}/${MainActivity::class.java.name}", it.target) }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
