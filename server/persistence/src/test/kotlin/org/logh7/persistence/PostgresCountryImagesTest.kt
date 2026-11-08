package org.logh7.persistence

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class PostgresCountryImagesTest {
    private fun fixture(block:(()->Connection)->Unit) {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL");assumeTrue(!url.isNullOrBlank(),"PostgreSQL image tests run in country-schema CI")
        val schema="images_"+UUID.randomUUID().toString().replace("-","")
        fun connect()=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").also {it.schema=schema}
        DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").use {admin->
            admin.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                connect().use {db->db.createStatement().use {sql->
                    for(file in listOf("V1__national_definitions.sql","V2__country_colors.sql","V3__country_definition_revision.sql","V5__country_images.sql"))
                        sql.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/$file")).bufferedReader().use {it.readText()})
                    sql.execute("""INSERT INTO nation_calendar VALUES ('fixture','Fixture',0);
                        INSERT INTO nation_currency VALUES ('fixture','Fixture','Q',0);
                        INSERT INTO nation_command_system VALUES ('fixture'); INSERT INTO nation_rank_system VALUES ('fixture');
                        INSERT INTO nation_office_system VALUES ('fixture'); INSERT INTO nation_peerage_system VALUES ('fixture');
                        INSERT INTO nation_ship_style VALUES ('empire','Empire');
                        INSERT INTO nation_asset_profile(profile_key,native_key) VALUES ('empire','native/empire'),('alliance','native/alliance');
                        INSERT INTO nation_family VALUES ('fixture','Fixture','fixture','fixture','fixture','fixture','fixture','fixture','empire');
                        INSERT INTO nation_country(country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile)
                        VALUES(2,'empire','Empire','fixture',2,0,'empire'),(3,'alliance','Alliance','fixture',3,0,'alliance'),(42,'third','Third','fixture',3,0,'empire');""")
                }}
                block(::connect)
            } finally {admin.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }
    private fun upload(color:Int):CountryImageUpload {
        val image=BufferedImage(8,4,BufferedImage.TYPE_INT_ARGB)
        for(y in 0..3)for(x in 0..7)image.setRGB(x,y,color)
        return CountryImageUpload.decode(ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray())
    }
    @Test fun originalMetadataReplacementHistoryAndCountryBindingSurviveReopening()=fixture {connect->
        val store=PostgresCountryImages(connect);val original=upload(0x80ffffff.toInt());val replacement=upload(0xffff0000.toInt())
        val kind=CountryImageKind.EMBLEM
        assertEquals(CountryImageOutcome.INVALID,store.save("../bad",kind,0,"Name","",original))
        assertEquals(CountryImageOutcome.INVALID,store.save("badge",kind,0,"Name","",null))
        assertEquals(CountryImageOutcome.CHANGED,store.save("badge",kind,0,"문장 <시험>","원본",original))
        assertEquals(CountryImageOutcome.CHANGED,store.save("badge",kind,1,"이름 수정","설명 수정",null))
        assertContentEquals(original.png,store.read("badge")!!.png)
        assertEquals(CountryImageOutcome.CHANGED,store.save("badge",kind,2,"교체","",replacement))
        assertEquals(CountryImageOutcome.CONFLICT,store.save("badge",kind,2,"오래된 수정","",null))
        assertEquals(CountryImageOutcome.INVALID,store.save("badge",CountryImageKind.FLAG,3,"교체","",null))
        assertEquals(CountryImageOutcome.CHANGED,store.restore("badge",3,1))
        val reopened=PostgresCountryImages(connect);val restored=reopened.read("badge")!!
        assertContentEquals(original.png,restored.png);assertContentEquals(original.rgba,restored.rgba)
        assertEquals("문장 <시험>",restored.info.title);assertEquals(4L,restored.info.revision)
        assertEquals(listOf(4L,3L,2L,1L),reopened.history("badge").map {it.revision})
        assertContentEquals(replacement.png,reopened.read("badge",3)!!.png)
        assertEquals(CountryImageOutcome.UNCHANGED,reopened.restore("badge",4,1))
        val revision=store.snapshot().revision
        assertEquals(CountryImageOutcome.CONFLICT,store.bind(42,revision-1,kind,"badge"))
        assertEquals(CountryImageOutcome.NOT_FOUND,store.bind(999,revision,kind,"badge"))
        assertEquals(CountryImageOutcome.INVALID,store.bind(42,revision,CountryImageKind.FLAG,"badge"))
        assertEquals(CountryImageOutcome.CHANGED,store.bind(42,revision,kind,"badge"))
        val bound=PostgresCountryImages(connect).snapshot()
        assertContentEquals(original.rgba,bound.countries.getValue(42).emblem)
        assertEquals(2,bound.countries.getValue(42).nativeEmblem)
        assertNull(bound.countries.getValue(2).emblem);assertNull(bound.countries.getValue(3).emblem)
        // Reusing a generated profile for another country must not make a later edit global.
        connect().use {db->db.createStatement().use {it.executeUpdate("UPDATE nation_country SET asset_profile=(SELECT asset_profile FROM nation_country WHERE country_id=42) WHERE country_id=3")}}
        assertEquals(CountryImageOutcome.CHANGED,store.bind(42,bound.revision,kind,null))
        val cleared=store.snapshot();assertNull(cleared.countries.getValue(42).emblem)
        assertContentEquals(original.rgba,cleared.countries.getValue(3).emblem)
        assertEquals("empire",PostgresCountryDefinitions(connect).load().catalog.countries.getValue(2).assets)
    }
    @Test fun twoStaleWritersCannotReplaceEachOtherAndFailedChangesDoNotAdvancePublication()=fixture {connect->
        val store=PostgresCountryImages(connect);val kind=CountryImageKind.MAP_ICON
        assertEquals(CountryImageOutcome.CHANGED,store.save("map",kind,0,"Map","",upload(-1)))
        val gate=java.util.concurrent.CountDownLatch(1)
        val outcomes=Executors.newFixedThreadPool(2).use {pool->
            val jobs=listOf("First","Second").map {title->CompletableFuture.supplyAsync({gate.await();store.save("map",kind,1,title,"",null)},pool)}
            gate.countDown();jobs.map {it.get()}
        }
        assertEquals(1,outcomes.count {it==CountryImageOutcome.CHANGED});assertEquals(1,outcomes.count {it==CountryImageOutcome.CONFLICT})
        assertEquals(2,store.snapshot().revision);assertEquals(2,store.history("map").size)
        assertEquals(CountryImageOutcome.NOT_FOUND,store.restore("map",2,999))
        assertEquals(2,store.snapshot().revision)
    }

    @Test fun candidatePortraitUsesTheCurrentUploadedVersionInItsOwnWorld()=fixture {connect->
        fun migrate(vararg files:String)=connect().use { db -> db.createStatement().use { sql ->
            for(file in files)sql.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/$file"))
                .bufferedReader().use {it.readText()})
        } }
        migrate("V11__original_candidate_profile.sql","V12__original_candidate_country.sql",
            "V13__original_candidate_peerage.sql","V14__original_candidate_female_faces.sql")
        val world=UUID.randomUUID()
        val candidates=PostgresOriginalCandidates(connect,world)
        val images=PostgresCountryImages(connect)
        val fallback=OriginalCandidateProfile("시험","후보",10001,10,List(8){50})
        assertTrue(candidates.load().isEmpty())
        assertFalse(candidates.supportsPortraits)
        assertNotNull(candidates.save(1001,0,fallback,CountryId(2)))
        migrate("V15__original_candidate_portrait_asset.sql")
        assertEquals(fallback,candidates.load().single().profile)
        assertTrue(candidates.supportsPortraits)

        val kind=CountryImageKind.PORTRAIT
        val original=upload(0xffeeaa33.toInt())
        val replacement=upload(0xff336699.toInt())
        assertEquals(CountryImageOutcome.CHANGED,images.save("face",kind,0,"초상","",original))
        assertEquals(CountryImageOutcome.CHANGED,images.save("flag",CountryImageKind.FLAG,0,"깃발","",original))
        assertEquals(CountryImageOutcome.INVALID,images.bind(2,images.snapshot().revision,kind,"face"))
        for(key in listOf("missing","flag"))assertFailsWith<IllegalArgumentException> {
            candidates.save(1001,1,fallback.copy(portraitKey=key),CountryId(2))
        }
        val assigned=fallback.copy(portraitKey="face")
        assertEquals(assigned,candidates.save(1001,1,assigned,CountryId(2))?.profile)
        assertEquals(assigned,candidates.save(1003,0,assigned,CountryId(2))?.profile)
        assertNull(candidates.save(1001,1,fallback,CountryId(2)))
        assertEquals(CountryImageOutcome.CHANGED,images.save("face",kind,1,"새 초상","",replacement))
        val reopened=PostgresOriginalCandidates(connect,world)
        val saved=reopened.load().first()
        assertEquals(assigned,saved.profile)
        assertContentEquals(replacement.png,images.read(checkNotNull(saved.profile.portraitKey))?.png)
        val thumbnails=reopened.loadPortraits()
        assertEquals(setOf("face"),thumbnails.keys)
        assertEquals(2L,thumbnails.getValue("face").revision)
        assertContentEquals(replacement.rgba,thumbnails.getValue("face").rgba)
        assertTrue(PostgresOriginalCandidates(connect,UUID.randomUUID()).load().isEmpty())
        assertEquals(fallback,reopened.save(1001,2,fallback,CountryId(2))?.profile)
    }
}
