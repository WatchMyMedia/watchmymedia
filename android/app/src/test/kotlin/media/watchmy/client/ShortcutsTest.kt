package media.watchmy.client

import android.app.Application
import android.content.Intent
import android.content.res.XmlResourceParser
import android.net.Uri
import media.watchmy.client.model.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launcher's shortcuts (res/xml/shortcuts.xml):
 * Search, Live TV and Downloads, each a link the app opens and that
 * reaches MainActivity through its watchmymedia:// filter.
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
                    // As Android stores it: a targetPackage is kept as written, so none is given (see shortcuts.xml).
                    val target = listOfNotNull(parser.getAttributeValue(ANDROID, "targetPackage"), parser.getAttributeValue(ANDROID, "targetClass")).joinToString("/")
                    found += Shortcut(id, label, parser.getAttributeValue(ANDROID, "data"), target)
                }
            }
        }
        return found
    }

    @Test fun `Search, Live TV and Downloads open their links in this build's MainActivity`() {
        val all = shortcuts()
        assertEquals(listOf("Search", "Live TV", "Downloads"), all.map { it.label })
        assertEquals(listOf(DeepLink.Search, DeepLink.LiveGuide, DeepLink.Downloads), all.map { DeepLink.open(it.data, null) })
        all.forEach { shortcut ->
            // No named package: Android keeps a targetPackage as written, so it can't follow -PappIdSuffix.
            assertEquals(shortcut.id, "", shortcut.target)
            val opens = context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.data)).setPackage(context.packageName), 0)
            assertEquals(shortcut.id, MainActivity::class.java.name, opens?.activityInfo?.name)
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
