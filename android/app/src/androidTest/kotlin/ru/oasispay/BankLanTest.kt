package ru.oasispay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.oasispay.data.bank.BankClient
import ru.oasispay.domain.PaymentRole

/** Explicit opt-in on a disposable QA device and a separate bank on port 18443. */
@RunWith(AndroidJUnit4::class)
class BankLanTest {
    @Test fun registerOverLan() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qaLan") == "1")
        val url = requireNotNull(args.getString("qaUrl"))
        require(java.net.URI(url).port == 18443)
        val pin = requireNotNull(args.getString("qaPin"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OasisPayApplication
        BankClient.checkConnection(url, pin)
        val terminal = args.getString("qaRole") == "terminal"
        val code = requireNotNull(args.getString("qaCode"))
        if (terminal) {
            app.terminal.enroll(url, pin, code)
            app.terminal.createOrder(19900, "ble-l2cap/1")
        } else {
            app.buyer.configure(url, pin, code)
            app.buyer.refreshAccount()
            check(app.buyer.snapshot().balance != null)
        }
        app.roles.choose(if (terminal) PaymentRole.TERMINAL else PaymentRole.BUYER)
    }
}
