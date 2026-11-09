package org.logh7.gateway

import org.logh7.engine.NavigationRejection
import org.logh7.engine.TacticalGroundAction
import org.logh7.engine.TacticalGroundOrder

/** The CD does not explain a ground order's acknowledgement or final state. Author: Peppone Choi. */
object TacticalGroundNotices {
    private fun label(deploy:Boolean)=if(deploy)"육전" else "철수"
    fun accepted(deploy:Boolean)="[${label(deploy)}] 접수 · 약 ${TacticalGroundOrder.PREPARATION_SECONDS}초 · CP 0"
    internal fun resumed(orders: Collection<TacticalGroundOrder>, now: Long, speed: Long): String? {
        val pending = orders.filter { it.readyAt > now }
        val next = pending.minByOrNull { it.readyAt } ?: return null
        val seconds = (next.readyAt - now + speed - 1) / speed
        return if (pending.size == 1) "[${label(next.action == TacticalGroundAction.DEPLOY)}] 진행 중 · 약 ${seconds}초 남음"
            else "[육전대] ${pending.size}건 진행 중 · 다음 완료 약 ${seconds}초"
    }
    fun finished(deploy:Boolean,cancelled:Boolean,count:Int)=if(cancelled)"[${label(deploy)}] 조건 변경으로 취소"
        else "[${label(deploy)}] ${count}유닛 ${if(deploy)"상륙" else "재탑승"} 완료"
    fun stopped(actions:Set<TacticalGroundAction>):String? = actions.takeIf {it.isNotEmpty()}?.joinToString(
        separator="·",prefix="[정지] ",postfix=" 취소") {label(it==TacticalGroundAction.DEPLOY)}

    fun text(deploy:Boolean,reason:NavigationRejection?):String? {
        val action=label(deploy)
        val why=when(reason) {
            NavigationRejection.BASE->"정박·주둔 거점이 없습니다."
            NavigationRejection.CREW->"장갑병은 특수 행성·요새에 강하할 수 없습니다."
            NavigationRejection.RANGE->"함정이 명령 범위 밖입니다."
            NavigationRejection.UNIT->if(deploy)"탑재한 육전대가 없습니다." else "병력 없음·탑재 정원 초과"
            NavigationRejection.BUSY->"이미 다른 명령을 수행 중입니다."
            NavigationRejection.GRID->"거점 육전대 정원 30유닛 초과입니다."
            NavigationRejection.CARD->"이 함정의 지휘 권한이 없습니다."
            NavigationRejection.WOUNDED->"부상 중에는 명령할 수 없습니다."
            NavigationRejection.TACTICAL->"전술 화면에서만 명령할 수 있습니다."
            NavigationRejection.ACCESS->"이 육전 특기는 사용할 수 없습니다."
            NavigationRejection.CLOCK,NavigationRejection.DISABLED->"지금은 명령을 접수할 수 없습니다."
            else->return null
        }
        return "[$action] $why"
    }
}

/** Refused 041f of a mobile fortress whose propulsion an orbital attack broke (CD p.46); the CD shows no result. */
internal const val FORTRESS_PROPULSION_LOST = "[이동] 추진 장치가 파괴되어 요새를 움직일 수 없습니다."
