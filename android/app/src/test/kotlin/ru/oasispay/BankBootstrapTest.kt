package ru.oasispay

import org.junit.Assert.*
import org.junit.Test
import ru.oasispay.domain.*

class BankBootstrapTest {
    private val terminalCode = "T".repeat(32)
    private val buyerCode = "B".repeat(32)
    private val block = "Container bank Healthy\nURL              https://192.168.1.10:8443\nTLS SHA-256      ${"ab".repeat(32)}\nКод кассы        $terminalCode\nКод покупателя   $buyerCode\n"

    @Test fun roleSelectsOnlyItsOwnCodeAndIgnoresDockerProgress() {
        assertEquals(terminalCode, BankBootstrap.parse(block, PaymentRole.TERMINAL).code)
        val buyer = BankBootstrap.parse(block.replace("\n", "\r\n"), PaymentRole.BUYER)
        assertEquals(buyerCode, buyer.code)
        assertEquals("https://192.168.1.10:8443", buyer.url)
        assertEquals("ab".repeat(32), buyer.pin)
    }
    @Test fun duplicatesMissingRoleInsecureUrlAndInvalidPinAreRejected() {
        for (bad in listOf(block + "URL https://example.org\n", block.replace("https:", "http:"),
            block.replace("ab".repeat(32), "bad"), block.substringBefore("Код покупателя"),
            block.replace(buyerCode, "B".repeat(64)))) {
            assertThrows(Exception::class.java) { BankBootstrap.parse(bad, PaymentRole.BUYER) }
        }
        assertThrows(Exception::class.java) {
            BankBootstrap("https://example.org/api", "ab".repeat(32), buyerCode).validated()
        }
    }
}
