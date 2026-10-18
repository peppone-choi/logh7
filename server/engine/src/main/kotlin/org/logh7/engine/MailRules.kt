package org.logh7.engine

/**
 * 메일 용량·수신의 순수 판정. 저장·송신·삭제·카드 주소 귀속·프로토콜 연결은 다루지 않는다.
 * 근거: docs/manual/03-communication.md의 한국어 매뉴얼 요약(W p.16, evidence:manual).
 * 일본어 원문은 미대조이며 원본 클라이언트 동작을 검증한 결과가 아니다.
 */
object MailRules {
    const val ADDRESS_BOOK_CAPACITY = 100
    const val INBOX_CAPACITY = 120

    /** 주소록에 한 개를 더 넣을 수 있는가. 중복 주소 처리·삭제는 호출자 책임이다. */
    fun addressBookAdd(currentEntries: Int): AddressBookVerdict {
        require(currentEntries in 0..ADDRESS_BOOK_CAPACITY)
        return if (currentEntries < ADDRESS_BOOK_CAPACITY) AddressBookVerdict.ADDABLE else AddressBookVerdict.FULL
    }

    /**
     * 새 메일 1통의 수신 가능 여부만 반환한다. 주소록 등록 여부는 조건이 아니다.
     * 전술 중 보류/반송은 정하지 않으며 수신함이 가득 차도 자동 삭제하지 않는다.
     * 입력 검증 후 전술을 용량보다 먼저 판정하는 것은 설계 선택(evidence:guess)이지 원본 사실이 아니다.
     */
    fun delivery(recipientInboxCount: Int, recipientInTactics: Boolean): MailDeliveryVerdict {
        require(recipientInboxCount in 0..INBOX_CAPACITY)
        return when {
            recipientInTactics -> MailDeliveryVerdict.RECIPIENT_IN_TACTICS
            recipientInboxCount >= INBOX_CAPACITY -> MailDeliveryVerdict.INBOX_FULL
            else -> MailDeliveryVerdict.DELIVERABLE
        }
    }
}

enum class AddressBookVerdict { ADDABLE, FULL }
enum class MailDeliveryVerdict { DELIVERABLE, RECIPIENT_IN_TACTICS, INBOX_FULL }
