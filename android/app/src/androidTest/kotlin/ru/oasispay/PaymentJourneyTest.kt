package ru.oasispay

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.ui.PaymentViewModel
import java.io.File

/** Explicit opt-in, isolated-bank test. Never enrolls against a default/user bank. */
@RunWith(AndroidJUnit4::class)
class PaymentJourneyTest {
    @Test fun isolatedPaymentJourney() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val role = arguments.getString("qaRole")
        assumeTrue("Set explicit isolated bank arguments", role in listOf("terminal", "buyer"))
        val url = requireNotNull(arguments.getString("qaUrl"))
        require(url == "https://127.0.0.1:28443") { "Only the isolated QA endpoint is allowed" }
        val pin = requireNotNull(arguments.getString("qaPin"))
        val code = requireNotNull(arguments.getString("qaCode"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as OasisPayApplication
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 37) permissions += Manifest.permission.ACCESS_LOCAL_NETWORK
        for (permission in permissions) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val terminalRole = role == "terminal"
        app.roles.choose(if (terminalRole) PaymentRole.TERMINAL else PaymentRole.BUYER)
        if (terminalRole) app.terminal.enroll(url, pin, code) else {
            app.buyer.configure(url, pin, code)
            app.buyer.refreshAccount()
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: PaymentViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[PaymentViewModel::class.java] }
        suspend fun waitFor(timeout: Long = 30_000, condition: () -> Boolean) = withTimeout(timeout) {
            try {
                while (!condition()) delay(100)
            } catch (error: Exception) {
                println("QA state: ${model.state.value}")
                throw error
            }
        }
        fun capture(name: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(context.getExternalFilesDir("qa")), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        waitFor { model.state.value.initialized && model.state.value.storageHealthy }
        delay(800)
        if (terminalRole) {
            assertTrue(app.terminal.snapshot().registrationNumber.startsWith("OP-T-"))
            capture("terminal-ready")
            // The bank is never contacted directly by the terminal after registration.
            instrumentation.uiAutomation.executeShellCommand("svc wifi disable").close()
            instrumentation.uiAutomation.executeShellCommand("svc data disable").close()
            instrumentation.runOnMainSync { model.createOrder("199,00", true) }
            waitFor { model.state.value.armed && model.state.value.terminal.invoice != null }
            delay(1000)
            capture("terminal-waiting")
            waitFor(180_000) { model.state.value.terminal.invoice?.complete == true }
            val invoice = app.terminal.snapshot().invoice!!
            assertEquals(PaymentStatus.PAID, invoice.status)
            assertFalse("Payment completion must not invent goods delivery", invoice.fulfilled)
            assertTrue(model.state.value.events.any { it.message.contains("AES-256-GCM") })
            assertTrue(model.state.value.events.any { it.message.contains("подпись", ignoreCase = true) })
            waitFor { !model.state.value.armed && !model.state.value.terminalPeerConnected }
            delay(500)
            capture("terminal-paid")
            fun findNode(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (predicate(node)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                }
                return null
            }
            repeat(2) {
                findNode { node -> node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(250)
            }
            findNode { it.text?.toString() == "Открыть" }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            repeat(3) {
                findNode { node -> node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(250)
            }
            capture("terminal-protocol")
        } else {
            assertTrue(app.buyer.snapshot().registrationNumber.startsWith("OP-C-"))
            assertEquals(500000L, app.buyer.snapshot().balance)
            capture("buyer-wallet")
            // Buyer UI starts discovery automatically when the wallet is ready.
            waitFor(35_000) { model.state.value.buyer.payment != null && !model.state.value.busy }
            assertNull(app.buyer.snapshot().payment!!.status)
            capture("buyer-confirm")
            // Network/device protocol integration only; owner prompt is exercised separately by UI.
            val hash = app.buyer.snapshot().payment!!.requestHash
            instrumentation.runOnMainSync { model.pay(hash) }
            waitFor(60_000) { model.state.value.buyer.payment?.complete == true && !model.state.value.busy }
            assertEquals(PaymentStatus.PAID, app.buyer.snapshot().payment!!.status)
            assertEquals(480100L, app.buyer.snapshot().balance)
            delay(500)
            capture("buyer-paid")
        }
    }
}
