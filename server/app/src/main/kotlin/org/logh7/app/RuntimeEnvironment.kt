package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Process > local secrets > shared settings. Files are data, never shell code. Author: Peppone Choi (peppone-choi). */
class RuntimeEnvironment private constructor(private val values:Map<String,String>) {
    operator fun get(name:String):String?=values[name]

    companion object {
        fun load(process:Map<String,String> = System.getenv(), directory:Path = Path.of("").toAbsolutePath()):RuntimeEnvironment {
            val root=if(directory.fileName?.toString()=="server" && Files.isRegularFile(directory.resolve("../AGENTS.md")))
                directory.parent else directory
            val base=process["LOGH7_ENV_FILE"]?.let {Path.of(it).toAbsolutePath()} ?: root.resolve(".env")
            val local=base.resolveSibling(base.fileName.toString()+".local")
            val values=linkedMapOf<String,String>()
            for(path in listOf(base,local)) {
                if(!Files.exists(path))continue
                require(Files.size(path)<=1_048_576) {"Environment file is too large: $path"}
                val seen=hashSetOf<String>()
                Files.readAllLines(path,Charsets.UTF_8).forEachIndexed {index,raw ->
                    val line=raw.removePrefix("\uFEFF").trim().removePrefix("export ")
                    if(line.isEmpty() || line.startsWith('#'))return@forEachIndexed
                    val name=line.substringBefore('=')
                    require('=' in line && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && seen.add(name)) {
                        "Invalid or duplicate environment assignment: $path:${index+1}"
                    }
                    val rawValue=line.substringAfter('=').trim()
                    val value=if(rawValue.startsWith('"') || rawValue.startsWith('\'')) {
                        require(rawValue.length>=2 && rawValue.last()==rawValue.first()) {"Unclosed environment quote: $path:${index+1}"}
                        rawValue.substring(1,rawValue.lastIndex)
                    } else rawValue.replace(Regex("\\s+#.*$"),"").trimEnd()
                    require('\u0000' !in value) {"NUL in environment file: $path:${index+1}"}
                    values[name]=value
                }
            }
            values.putAll(process)
            return RuntimeEnvironment(Collections.unmodifiableMap(values))
        }
    }
}
