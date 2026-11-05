package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.logh7.protocol.LobbyMessages

/** Stable lobby IDs; all mutable files of a world live under its own directory. */
internal data class SessionRuntimeConfiguration(val id:Int,val name:String,val environment:RuntimeEnvironment,val stateDirectory:Path?=null) {
    companion object {
        fun load(base:RuntimeEnvironment):List<SessionRuntimeConfiguration> {
            val file=base["LOGH7_SESSIONS_FILE"]?.takeIf {it.isNotBlank()}?.let {Path.of(it).toAbsolutePath().normalize()}
                ?: return listOf(SessionRuntimeConfiguration(1,LobbyMessages.LOCAL_SESSION_NAME,base))
            require(Files.size(file)<=262144) {"Session configuration is too large"}
            val values=object:Properties() {
                override fun put(key:Any,value:Any):Any? {
                    require(!containsKey(key)) {"Duplicate session configuration field"}
                    return super.put(key,value)
                }
            }
            Files.newBufferedReader(file).use {reader->values.load(reader)}
            val key=Regex("session\\.([1-9][0-9]{0,4})\\.(name|gamePort|updatePort|sessionPort|opsPort|stateDirectory|worldFile)")
            require(values.isNotEmpty() && values.stringPropertyNames().all {key.matches(it)}) {"Unknown session configuration field"}
            val ids=values.stringPropertyNames().map {key.matchEntire(it)!!.groupValues[1].toInt()}.distinct().sorted()
            require(ids.size<=org.logh7.gateway.GameSessionDirectory.MAX_SESSIONS && ids.all {it<=65535}) {
                "Native lobby frame budget supports at most 128 sessions with IDs in 1..65535"}
            fun path(value:String)=Path.of(value).let {if(it.isAbsolute)it else file.parent.resolve(it)}.toAbsolutePath().normalize()
            fun canonical(value:Path):Path {
                val absolute=value.toAbsolutePath().normalize()
                var ancestor=absolute
                while(!Files.exists(ancestor))ancestor=checkNotNull(ancestor.parent)
                return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize()
            }
            val fameFile=canonical(base["LOGH7_ACCOUNT_FAME_FILE"]?.takeIf {it.isNotBlank()}?.let {Path.of(it)}
                ?: file.parent.resolve("account-fame.properties"))
            require(!Files.exists(fameFile) || Files.isRegularFile(fameFile)) {"Account fame must be a file"}
            val roots=mutableListOf<Path>();val ports=hashSetOf<Int>()
            val result=ids.map {id->
                fun required(field:String)=values.getProperty("session.$id.$field")?.trim()?.takeIf {it.isNotEmpty()}
                    ?: error("Missing session.$id.$field")
                val name=required("name")
                require(name.length<=12 && name.none {it.isISOControl() || it.isSurrogate()}) {"Session names must fit 12 native characters"}
                val root=canonical(path(required("stateDirectory")))
                require(!Files.exists(root) || Files.isDirectory(root)) {"Session state root must be a directory"}
                require(roots.none {root.startsWith(it) || it.startsWith(root)}) {"Session state directories must not overlap"}
                roots.add(root)
                val overrides=linkedMapOf<String,String?>()
                for((field,variable) in listOf("gamePort" to "GAME","updatePort" to "UPDATE","sessionPort" to "SESSION","opsPort" to "OPS")) {
                    val port=required(field).toIntOrNull()
                    require(port!=null && port in 1..65535 && ports.add(port)) {"All session ports must be valid and distinct"}
                    overrides["LOGH7_${variable}_PORT"]=port.toString()
                }
                val world=values.getProperty("session.$id.worldFile")?.let {path(it).toString()} ?: base["LOGH7_WORLD_FILE"]
                require(!world.isNullOrBlank()) {"Each session needs a world file"}
                overrides["LOGH7_WORLD_FILE"]=world
                for((variable,relative) in mapOf("CHARACTERS_FILE" to "characters.properties","NAVIGATION_FILE" to "navigation",
                    "PLANET_STOCK_FILE" to "planet-stocks","SESSION_GENERATION_FILE" to "session-generation",
                    "WORLD_STATE_DIR" to "world","SESSION_STORE_DIR" to "session","OPS_AUDIT_FILE" to "ops-audit.jsonl"))
                    overrides["LOGH7_$variable"]=root.resolve(relative).toString()
                for((variable,relative) in mapOf("CAPTURE_DIR" to "capture","BACKUP_DIR" to "backups","BACKUP_EXPORT_DIR" to "backup-export"))
                    overrides["LOGH7_$variable"]=base["LOGH7_$variable"]?.let {root.resolve(relative).toString()}
                require(overrides.filterKeys {it.endsWith("_DIR") || it.endsWith("_FILE")}.values.filterNotNull()
                    .filter {it!=world}.all {canonical(Path.of(it)).startsWith(root)}) {"Session state files must remain inside their own directory"}
                SessionRuntimeConfiguration(id,name,base.overlay(overrides+mapOf("LOGH7_ACCOUNT_FAME_FILE" to fameFile.toString())),root)
            }
            val inputs=listOfNotNull(file,base["LOGH7_ACCOUNTS_FILE"]?.let {Path.of(it)},base["LOGH7_OPS_USERS_FILE"]?.let {Path.of(it)},
                base["LOGH7_OPS_TOKEN_FILE"]?.let {Path.of(it)},base["LOGH7_SESSION_RULES"]?.let {Path.of(it)})+
                result.map {Path.of(checkNotNull(it.environment["LOGH7_WORLD_FILE"]))}
            require(inputs.map(::canonical).none {input->roots.any {input.startsWith(it)}}) {"Shared input files must be outside session state directories"}
            require(roots.none {fameFile.startsWith(it)} && inputs.map(::canonical).none {it==fameFile}) {
                "Shared account fame must be outside world state folders and separate from input files"}
            return result
        }
    }
}
