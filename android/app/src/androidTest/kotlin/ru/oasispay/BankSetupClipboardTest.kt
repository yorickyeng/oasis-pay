package ru.oasispay

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.oasispay.domain.BankBootstrap
import ru.oasispay.domain.PaymentRole
import ru.oasispay.ui.components.BankSetup
import ru.oasispay.ui.theme.OasisPayTheme

@RunWith(AndroidJUnit4::class)
class BankSetupClipboardTest {
    @Test fun importsWholeConsoleBlockIntoTheSelectedRole() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
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
        suspend fun click(label: String) {
            repeat(10) {
                var node = find { it.text?.toString() == label }
                if (node != null) {
                    while (node != null && !node.isClickable) node = node.parent
                    if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                }
                find { n -> n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(200)
            }
            error("Cannot find action $label")
        }
        val text = "URL https://127.0.0.1:8443\nTLS SHA-256 ${"ab".repeat(32)}\nКод кассы ${"T".repeat(32)}\nКод покупателя ${"B".repeat(32)}"
        for (role in PaymentRole.entries) {
            var imported: BankBootstrap? = null
            instrumentation.runOnMainSync {
                val clip = ClipData.newPlainText("oasis test setup", text)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                activity.setContent {
                    OasisPayTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            BankSetup("Настройка", "", false, { url, pin, code -> imported = BankBootstrap(url, pin, code) }, role, { _, _ -> })
                        }
                    }
                }
            }
            delay(500)
            click("Вставить настройки из буфера")
            delay(200)
            click("Подключить")
            delay(200)
            assertEquals("https://127.0.0.1:8443", imported?.url)
            assertEquals("ab".repeat(32), imported?.pin)
            assertEquals((if (role == PaymentRole.TERMINAL) "T" else "B").repeat(32), imported?.code)
        }
    }
}
