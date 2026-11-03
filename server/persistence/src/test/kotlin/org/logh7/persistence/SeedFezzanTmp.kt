package org.logh7.persistence

import org.logh7.engine.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Throwaway: multi3 holds Iserlohn's fortress commander card beside it. Not committed. */
class SeedFezzanTmp {
    @Test fun seed() {
        val dir=System.getenv("SEED_DIR") ?: return
        val now=System.getenv("SEED_NOW").toLong()
        val p=TacticalBasePosition.at(GalaxyBase(1040,1253,110,1,"Iserlohn",0,12f,86400,0,0f,2f),now)
        val flag=TacticalPoint(p.x+5f,p.y)
        FileWorldStateStore(Path.of(dir)).use {store->
            val nav=store.loadNavigation();val stocks=checkNotNull(store.loadStocks())
            val base=nav.getValue("multi3");println("m3 grid=${base.grid} cp=${base.cpPaused} base=${base.baseId} mode=${base.mode} energy=${base.energy} fire=${base.fire} range=${base.commandRange} strategy=${base.strategy} warp=${base.warpTarget} dock=${base.dockTarget}")
            val player=base.copy(cpPaused=true,motion=TacticalMotion(1253,now,TacticalPose(flag,0f),0f,0f,listOf(flag)))
            val s=stocks.strategic
            val offices=s.offices.filter {it.card!=51 || it.seat!=1040}+OfficeHolding(51,1040,10002)
            store.saveWorld(nav+("multi3" to player),stocks.copy(strategic=s.copy(offices=offices)))
            println("seeded fortress=$p flag=$flag offices=${offices.filter {it.character==10002}}")
        }
    }
}
