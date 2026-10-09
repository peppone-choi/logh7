package org.logh7.engine.strategy

import org.junit.jupiter.api.Test
import kotlin.test.*

class StrategyTest {
    private val definitions = StrategyCatalogLoader.load()
    private fun command(cost: TableValue = TableValue.Fixed(10), wait: TableValue = TableValue.Fixed(0), duration: TableValue = TableValue.Fixed(0)) =
        CommandDefinition("synthetic/test", CommandGroup.PERSONAL, "test", "", "", "", cost, wait, duration, 69, 2, ManualStatus.UNKNOWN, "불명")
    private fun pipeline(command: CommandDefinition = command(), balance: CpBalance = CpBalance(100,100),
        resolution: CommandResolution? = CommandResolution(10,CpPool.PCP,0,0), enabled: Boolean = true,
        resolver: CommandResolver = CommandResolver { resolution }) = StrategyPipeline(StrategyCatalog(listOf(command)),
        CardPermissions(mapOf("synthetic-card" to setOf(command.id))), resolver, if(enabled) setOf(command.id) else emptySet(), balance)
    private fun submit(p: StrategyPipeline, time: Long = 0) = p.submit("synthetic/test", listOf("synthetic-card"), time)
    private fun rejected(result: SubmissionResult, reason: StrategyRejection) = assertEquals(reason,(result as SubmissionResult.Rejected).reason)

    @Test fun manualW68to74_all81OriginalFieldsAndGroupsAreRetained() {
        val commands = definitions.catalog.commands
        assertEquals(81,commands.size)
        assertEquals(listOf(16,15,8,6,10,12,14),CommandGroup.entries.map { group -> commands.count { it.group==group } })
        val csv = javaClass.getResourceAsStream("/strategy/strategy-commands.csv")!!.reader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.isRegularFile(it.resolve("docs/manual/data/strategy-commands.csv")) }
        assertContentEquals(java.nio.file.Files.readAllBytes(root.resolve("docs/manual/data/strategy-commands.csv")),
            javaClass.getResourceAsStream("/strategy/strategy-commands.csv")!!.readBytes())
        val manualStatuses = java.nio.file.Files.readAllLines(root.resolve("docs/manual/07-command-table.md"))
            .filter { it.startsWith("| ") }.map { it.trim('|').split('|').map(String::trim) }
            .filter { it.size == 6 }.associate { it[0] to it[5].replace("**", "") }
        val raw = csv.lineSequence().filter { it.isNotEmpty() }.drop(1).toList()
        commands.zip(raw).forEach { (command,line) ->
            assertEquals(listOf(command.group.sourceName,command.sourceName,command.rawCp,command.rawWait,command.rawDuration,command.page.toString()),line.split(','))
            assertEquals("${command.group.key}/${command.sourceName}",command.id)
            assertTrue(command.rawStatus.isNotBlank())
            assertEquals(manualStatuses.getValue(command.sourceName),command.rawStatus)
        }
        assertEquals(commands.map { it.id }.toSet(),StrategyCatalog(commands.reversed()).byId.keys)
        assertEquals(ManualStatus.UNIMPLEMENTED,definitions.catalog.byId.getValue("intelligence/帰還工作").manualStatus)
        assertEquals("동상",definitions.catalog.byId.getValue("intelligence/帰還工作").rawStatus)
    }
    @Test fun manualW68_fixedRangeDistanceAndEmptyRemainDistinct() {
        val warp=definitions.catalog.byId.getValue("operation/ワープ航行")
        assertEquals("40",warp.rawCp); assertEquals("",warp.rawWait)
        assertEquals(TableValue.DistanceProportional(40),warp.cp)
        assertEquals(TableValue.DistanceProportional(null),warp.wait)
        assertEquals(TableValue.Fixed(0),warp.duration)
        assertEquals(TableValue.Range(48,960),definitions.catalog.byId.getValue("operation/燃料補給").duration)
        assertEquals(TableValue.Range(10,1280),definitions.catalog.byId.getValue("command/作戦計画").cp)
    }
    @Test fun registryRejectsMalformedDuplicateUnknownAndInvalidUtf8BeforePublication() {
        fun read(csv: ByteArray, props: String = "card.maximum=16\ncp.substitution_multiplier=2\nstatus.personal/test=UNKNOWN|불명\n") =
            StrategyCatalogLoader.read(csv.inputStream(),props.byteInputStream())
        val header="group,command,cp,wait,duration,page\n"
        val valid=header+"個人コマンド,test,10,0,0,69\n"
        assertEquals(1,read(valid.toByteArray()).catalog.commands.size)
        for (text in listOf(valid+"個人コマンド,test,10,0,0,69\n",valid.replace("10","-1"),valid.replace("10","9223372036854775808"),
            valid.replace("10","20～10"),valid.replace("個人コマンド","absent"),valid.replace(",69",",75"),header+"\"unclosed"))
            assertFails { read(text.toByteArray()) }
        assertFails { read(byteArrayOf(-1)) }
        assertFails { read(valid.toByteArray(),"card.maximum=16\ncard.maximum=16\ncp.substitution_multiplier=2\n") }
        assertFails { read(valid.toByteArray(),"card.maximum=16\ncp.substitution_multiplier=2\nstatus.personal/test=UNKNOWN|불명\nstale=1") }
    }
    @Test fun manualW27_zeroCostRequiresPermissionButNoCpKindOrBalance() {
        val zero=command(TableValue.Fixed(0)); val p=pipeline(zero,CpBalance(0,0),CommandResolution(0,null,0,0))
        rejected(p.submit(zero.id,emptyList(),0),StrategyRejection.UNAUTHORIZED)
        assertEquals(emptyList(),p.jobs())
        val paid=(submit(p) as SubmissionResult.Accepted).job.payment
        assertEquals(CpBalance(0,0),p.balance); assertEquals(0L,paid.spent); assertEquals(0L,paid.experienceEligibleCp)
    }
    @Test fun manualW27_directBothPoolsAndFullDoubleSubstitutionAreAtomic() {
        for (pool in CpPool.entries) {
            val direct=CpPayment.charge(CpBalance(10,10),10,pool) as PaymentResult.Paid
            assertEquals(10L,direct.experienceEligibleCp); assertFalse(direct.substituted)
            val balance=if(pool==CpPool.PCP) CpBalance(3,20) else CpBalance(20,3)
            val substituted=CpPayment.charge(balance,10,pool) as PaymentResult.Paid
            assertEquals(3L,substituted.balance.inPool(pool)); assertEquals(20L,substituted.spent)
            assertTrue(substituted.substituted); assertEquals(0L,substituted.experienceEligibleCp)
        }
    }
    @Test fun manualW27_mixedInsufficientOverflowAndUnknownMakeNoPayment() {
        assertEquals(PaymentResult.Rejected(StrategyRejection.UNRESOLVED_MIXED_PAYMENT),CpPayment.charge(CpBalance(3,14),10,CpPool.PCP))
        assertEquals(PaymentResult.Rejected(StrategyRejection.INSUFFICIENT_CP),CpPayment.charge(CpBalance(3,13),10,CpPool.PCP))
        assertEquals(PaymentResult.Rejected(StrategyRejection.OVERFLOW),CpPayment.charge(CpBalance(0,Long.MAX_VALUE),Long.MAX_VALUE,CpPool.PCP))
        assertEquals(PaymentResult.Rejected(StrategyRejection.UNRESOLVED_CP),CpPayment.charge(CpBalance(100,100),10,null))
        val p=pipeline(balance=CpBalance(3,14)); rejected(submit(p),StrategyRejection.UNRESOLVED_MIXED_PAYMENT)
        assertEquals(CpBalance(3,14),p.balance); assertTrue(p.jobs().isEmpty())
    }
    @Test fun manualW27_sixteenCardsAndExplicitMappingWithoutGroupHeuristics() {
        val mapping=mutableMapOf("card" to mutableSetOf("known")); val permissions=CardPermissions(mapping)
        mapping["card"]!!.clear()
        assertTrue(permissions.allows(listOf("card")+(1..15).map { "unknown$it" },"known"))
        assertFalse(permissions.allows(listOf("card")+(1..16).map { "unknown$it" },"known"))
        assertFalse(permissions.allows(listOf("card","card"),"known"))
        assertFalse(permissions.allows(listOf("individual"),"known"))
    }
    @Test fun permissionAndFlagsPrecedeInjectedResolutionAndUnknownPolicyNeverDebits() {
        var calls=0; val p=pipeline(resolver=CommandResolver { calls++;null })
        rejected(p.submit("synthetic/test",emptyList(),0),StrategyRejection.UNAUTHORIZED); assertEquals(0,calls)
        rejected(submit(p),StrategyRejection.UNRESOLVED_TIMING); assertEquals(1,calls)
        rejected(submit(pipeline(enabled=false)),StrategyRejection.DISABLED)
        rejected(submit(pipeline(command(TableValue.Unspecified))),StrategyRejection.UNRESOLVED_CP)
        rejected(submit(pipeline(command(wait=TableValue.Unspecified))),StrategyRejection.UNRESOLVED_TIMING)
        rejected(submit(pipeline(resolution=CommandResolution(11,CpPool.PCP,0,0))),StrategyRejection.INVALID_RESOLUTION)
        assertEquals(CpBalance(100,100),p.balance); assertTrue(p.jobs().isEmpty())
    }
    @Test fun manualW28_injectedGameTimeOrdersCatchupByTimeAndRegistration() {
        val c=command(wait=TableValue.Fixed(1),duration=TableValue.Range(2,3))
        val p=pipeline(c,resolution=CommandResolution(10,CpPool.PCP,5,10,5))
        submit(p,100);submit(p,100)
        assertTrue(p.advance(104).isEmpty())
        assertEquals(listOf(StrategyJobEvent(0,JobEventKind.STARTED,105),StrategyJobEvent(1,JobEventKind.STARTED,105),
            StrategyJobEvent(0,JobEventKind.COMPLETED,115),StrategyJobEvent(1,JobEventKind.COMPLETED,115)),p.advance(200))
        assertTrue(p.advance(200).isEmpty());assertFails { p.advance(199) }
        assertTrue(p.jobs().all { it.state==JobState.COMPLETED })
    }
    @Test fun zeroDurationCompletesInRegistrationOrderAndRejectedTimeOverflowDoesNotConsumeId() {
        val p=pipeline()
        submit(p,0);submit(p,0)
        assertEquals(listOf(0L,0L,1L,1L),p.advance(0).map { it.job })
        val overflow=pipeline(command(wait=TableValue.Fixed(1)),resolution=CommandResolution(10,CpPool.PCP,1,0,1))
        rejected(submit(overflow,Long.MAX_VALUE),StrategyRejection.OVERFLOW)
        assertEquals(CpBalance(100,100),overflow.balance);assertTrue(overflow.jobs().isEmpty())
        assertEquals(0L,(submit(overflow,0) as SubmissionResult.Accepted).job.id)
    }
    @Test fun tableTimingRequiresExplicitUnitsAndRejectsOutOfRangeBeforeDebit() {
        val c=command(wait=TableValue.Fixed(8),duration=TableValue.Range(48,960))
        rejected(submit(pipeline(c,resolution=CommandResolution(10,CpPool.PCP,8,48))),StrategyRejection.UNRESOLVED_TIMING)
        for (resolution in listOf(CommandResolution(10,CpPool.PCP,8,47,1),CommandResolution(10,CpPool.PCP,7,48,1),
            CommandResolution(10,CpPool.PCP,8,48,0),CommandResolution(10,CpPool.PCP,8,961,1))) {
            val p=pipeline(c,resolution=resolution);rejected(submit(p),StrategyRejection.INVALID_RESOLUTION)
            assertEquals(CpBalance(100,100),p.balance);assertTrue(p.jobs().isEmpty())
        }
        val overflow=pipeline(c,resolution=CommandResolution(10,CpPool.PCP,8,48,Long.MAX_VALUE))
        rejected(submit(overflow),StrategyRejection.OVERFLOW);assertTrue(overflow.jobs().isEmpty())
        val good=pipeline(c,resolution=CommandResolution(10,CpPool.PCP,16,1920,2))
        assertTrue(submit(good) is SubmissionResult.Accepted)
    }
    @Test fun fixedRangeAndDistanceCostsAreValidatedWithoutInventingRangeOrDistanceFormulas() {
        val range=command(TableValue.Range(1,320))
        for (cost in listOf(0L,321L)) rejected(submit(pipeline(range,resolution=CommandResolution(cost,CpPool.PCP,0,0))),StrategyRejection.INVALID_RESOLUTION)
        assertTrue(submit(pipeline(range,resolution=CommandResolution(1,CpPool.PCP,0,0))) is SubmissionResult.Accepted)
        val distance=command(TableValue.DistanceProportional(40),TableValue.DistanceProportional(null))
        val missing=pipeline(distance,resolution=null);rejected(submit(missing),StrategyRejection.UNRESOLVED_TIMING)
        assertEquals(CpBalance(100,100),missing.balance)
        assertTrue(submit(pipeline(distance,resolution=CommandResolution(40,CpPool.MCP,42,0))) is SubmissionResult.Accepted)
        assertEquals(CpBalance(0,Long.MAX_VALUE),(CpPayment.charge(CpBalance(Long.MAX_VALUE,Long.MAX_VALUE),Long.MAX_VALUE,CpPool.PCP) as PaymentResult.Paid).balance)
    }
}
