package ru.oasispay

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in visual check on previously initialized QA emulators; never changes bank data. */
@RunWith(AndroidJUnit4::class)
class PaymentVisualTest {
    @Test fun captureConfirmedStateAndProtocolConsole() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qaVisual") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val terminal = args.getString("qaTerminal") == "1"
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            return null
        }
        if (terminal) {
            repeat(2) {
                find { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(250)
            }
            var node = find { it.text?.toString() == "Открыть" }
            assertNotNull("Console button must be accessible", node)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Console must expand", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(400)
            repeat(4) {
                find { n -> n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(200)
            }
            // Accessibility prunes offscreen text; capture before asserting so QA can inspect scroll position.
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(context.getExternalFilesDir("qa")), "terminal-protocol-inspect.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            println("Console expanded label visible: " + (find { it.text?.toString() == "Свернуть" } != null))
        }
        val filename = args.getString("qaFilename") ?: if (terminal) "terminal-protocol" else "buyer-paid"
        require(filename.matches(Regex("[a-z0-9-]{1,64}")))
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(requireNotNull(context.getExternalFilesDir("qa")), "$filename.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
