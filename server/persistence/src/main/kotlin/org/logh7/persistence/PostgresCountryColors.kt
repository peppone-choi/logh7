package org.logh7.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import org.logh7.engine.*

/** All country palette writes share a short parent-row transaction. Author: Peppone Choi (peppone-choi). */
class PostgresCountryColors(private val connect:()->Connection):CountryColorStore {
    private fun ResultSet.colors()=CountryColors(getObject("primary_rgb") as Int?,getObject("secondary_rgb") as Int?,
        getObject("map_rgb") as Int?,getObject("fleet_rgb") as Int?)

    override fun list():List<CountryAppearance> = connect().use {connection ->
        connection.prepareStatement("""SELECT c.country_id,c.display_name,c.family_key,COALESCE(p.revision,0) AS revision,
            p.primary_rgb,p.secondary_rgb,p.map_rgb,p.fleet_rgb FROM nation_country c
            LEFT JOIN nation_country_colors p USING (country_id) ORDER BY c.country_id""").use {query ->
            query.queryTimeout=5
            query.executeQuery().use {rows->buildList {while(rows.next())add(CountryAppearance(rows.getInt("country_id"),
                rows.getString("display_name"),rows.getString("family_key"),rows.colors(),rows.getLong("revision")))}}
        }
    }

    override fun save(countryId:Int,expectedRevision:Long,colors:CountryColors):CountryColorOutcome {
        require(countryId>0 && expectedRevision>=0)
        return connect().use {connection ->
            connection.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit=false
            try {
                // Lock first, then read the palette in a fresh statement snapshot (including a formerly absent row).
                val found=connection.prepareStatement("SELECT country_id FROM nation_country WHERE country_id=? FOR UPDATE").use {query ->
                    query.queryTimeout=5;query.setInt(1,countryId);query.executeQuery().use {it.next()}
                }
                var current=CountryColors();var revision=0L
                if(found)connection.prepareStatement("SELECT revision,primary_rgb,secondary_rgb,map_rgb,fleet_rgb FROM nation_country_colors WHERE country_id=?").use {query ->
                    query.queryTimeout=5;query.setInt(1,countryId);query.executeQuery().use {rows->if(rows.next()){current=rows.colors();revision=rows.getLong("revision")}}
                }
                val outcome=when {
                    !found->CountryColorOutcome.NOT_FOUND
                    revision!=expectedRevision->CountryColorOutcome.CONFLICT
                    current==colors->CountryColorOutcome.UNCHANGED
                    else-> {
                        val next=Math.addExact(revision,1L)
                        val changed=connection.prepareStatement("""INSERT INTO nation_country_colors
                            (country_id,revision,primary_rgb,secondary_rgb,map_rgb,fleet_rgb) VALUES (?,?,?,?,?,?)
                            ON CONFLICT (country_id) DO UPDATE SET revision=EXCLUDED.revision,primary_rgb=EXCLUDED.primary_rgb,
                            secondary_rgb=EXCLUDED.secondary_rgb,map_rgb=EXCLUDED.map_rgb,fleet_rgb=EXCLUDED.fleet_rgb,
                            updated_at=CURRENT_TIMESTAMP WHERE nation_country_colors.revision=?""").use {query ->
                            query.queryTimeout=5;query.setInt(1,countryId);query.setLong(2,next)
                            listOf(colors.primary,colors.secondary,colors.map,colors.fleet).forEachIndexed {index,value ->
                                if(value==null)query.setNull(index+3,Types.INTEGER) else query.setInt(index+3,value)
                            }
                            query.setLong(7,expectedRevision);query.executeUpdate()
                        }
                        if(changed==1)CountryColorOutcome.CHANGED else CountryColorOutcome.CONFLICT
                    }
                }
                connection.commit();outcome
            } catch(failure:Exception) {
                try {connection.rollback()} catch(rollback:Exception){failure.addSuppressed(rollback)}
                throw failure
            }
        }
    }
}
