package org.logh7.gateway

/** Chat line for a proposal/order arriving while the recipient is online; the CD only shows office rows on reopening
 * the mailbox. Kept within the native 64-character chat body. Author: Peppone Choi. */
object ProposalNotice {
    fun pending(count:Int):String = "[직무 편지] 미처리 ${count}건"

    fun arrived(sender:String):String = "[직무 편지] ${sender.take(12)}에게서 제안·명령이 도착했습니다. 편지함을 여세요."
}
