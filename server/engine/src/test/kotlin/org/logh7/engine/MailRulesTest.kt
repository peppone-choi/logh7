package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MailRulesTest {
    @Test fun emptyAddressBookAcceptsAnEntry() {
        assertEquals(AddressBookVerdict.ADDABLE, MailRules.addressBookAdd(0))
    }

    @Test fun addressBookAcceptsItsLastAvailableEntry() {
        assertEquals(AddressBookVerdict.ADDABLE, MailRules.addressBookAdd(99))
    }

    @Test fun fullAddressBookRejectsAnEntry() {
        assertEquals(AddressBookVerdict.FULL, MailRules.addressBookAdd(100))
    }

    @Test fun invalidAddressBookCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { MailRules.addressBookAdd(-1) }
        assertFailsWith<IllegalArgumentException> { MailRules.addressBookAdd(101) }
    }

    @Test fun emptyInboxReceivesMailDuringStrategy() {
        assertEquals(MailDeliveryVerdict.DELIVERABLE, MailRules.delivery(0, false))
    }

    @Test fun inboxReceivesItsLastAvailableMailDuringStrategy() {
        assertEquals(MailDeliveryVerdict.DELIVERABLE, MailRules.delivery(119, false))
    }

    @Test fun fullInboxCannotReceiveMailDuringStrategy() {
        assertEquals(MailDeliveryVerdict.INBOX_FULL, MailRules.delivery(120, false))
    }

    @Test fun emptyInboxCannotReceiveMailDuringTactics() {
        assertEquals(MailDeliveryVerdict.RECIPIENT_IN_TACTICS, MailRules.delivery(0, true))
    }

    @Test fun tacticsTakesPrecedenceOverFullInbox() {
        assertEquals(MailDeliveryVerdict.RECIPIENT_IN_TACTICS, MailRules.delivery(120, true))
    }

    @Test fun invalidInboxCountsAreRejectedBeforeTacticsCheck() {
        for (inTactics in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> { MailRules.delivery(-1, inTactics) }
            assertFailsWith<IllegalArgumentException> { MailRules.delivery(121, inTactics) }
        }
    }

    @Test fun capacitiesMatchManualSummary() {
        assertEquals(100, MailRules.ADDRESS_BOOK_CAPACITY)
        assertEquals(120, MailRules.INBOX_CAPACITY)
    }

    @Test fun repeatedDeliveryChecksReturnTheSameVerdict() {
        val first = MailRules.delivery(119, false)
        val second = MailRules.delivery(119, false)
        assertEquals(MailDeliveryVerdict.DELIVERABLE, first)
        assertEquals(first, second)
    }
}
