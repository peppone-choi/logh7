package org.logh7.gateway

import org.logh7.engine.NavigationRejection

/** Why a 육전/철수 order was refused; the CD client displays no result for 040f/0410. Author: Peppone Choi. */
object TacticalGroundNotices {
    fun text(deploy:Boolean,reason:NavigationRejection?):String? {
        val action=if(deploy)"육전" else "철수"
        val why=when(reason) {
            NavigationRejection.BASE->if(deploy)"정박 거점이 없거나 장갑병 강하 불가 거점입니다."
                else "정박·주둔 거점이 없습니다."
            NavigationRejection.RANGE->"함정이 명령 범위 밖입니다."
            NavigationRejection.UNIT->if(deploy)"탑재한 육전대가 없습니다." else "철수할 육전대가 없거나 탑재 한도 초과입니다."
            NavigationRejection.BUSY->"이미 다른 명령을 수행 중입니다."
            NavigationRejection.GRID->"거점 육전대 정원 30유닛 초과입니다."
            NavigationRejection.CARD->"이 함정의 지휘 권한이 없습니다."
            NavigationRejection.WOUNDED->"부상 중에는 명령할 수 없습니다."
            NavigationRejection.TACTICAL->"전술 화면에서만 명령할 수 있습니다."
            else->return null
        }
        return "[$action] $why"
    }
}
