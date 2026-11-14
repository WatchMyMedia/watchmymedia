package media.watchmy.client

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstForegroundTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
    }

    @Test fun `waits for the first start, and runs once`() {
        val first = FirstForeground(owner.lifecycle)
        val ran = mutableListOf<String>()
        first.then { ran += "cast" }
        first.then { ran += "session" }
        // A broadcast or a job: the process is created and never comes to the foreground.
        assertEquals(emptyList<String>(), ran)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(listOf("cast", "session"), ran)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("cast", "session"), ran)
    }

    @Test fun `runs at once once the app has been in the foreground`() {
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val first = FirstForeground(owner.lifecycle)
        var ran = 0
        first.then { ran++ }
        assertEquals(1, ran)
    }
}
