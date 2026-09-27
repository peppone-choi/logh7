plugins { kotlin("jvm") }
val generated = layout.buildDirectory.dir("generated/sources/protocol/kotlin")
val generateCodecs = tasks.register("generateCodecs") {
    inputs.file("schema/messages.yaml"); outputs.dir(generated)
    doLast {
        // Deliberately restricted YAML subset: explicit name/opcode pairs only.
        val text = file("schema/messages.yaml").readText()
        val entries = Regex("name: ([A-Za-z][A-Za-z0-9]*)\\s+opcode: (0x[0-9A-Fa-f]+)").findAll(text).toList()
        require(entries.isNotEmpty()) { "No messages found in schema" }
        val target = generated.get().file("org/logh7/protocol/generated/MessageId.kt").asFile
        target.parentFile.mkdirs()
        target.writeText("package org.logh7.protocol.generated\n\nenum class MessageId(val opcode: Int) {\n" + entries.joinToString(",\n") { "    ${it.groupValues[1]}(${it.groupValues[2]})" } + ";\n    fun encodeOpcode(): ByteArray = byteArrayOf((opcode ushr 8).toByte(), opcode.toByte())\n    companion object { fun decodeOpcode(bytes: ByteArray): MessageId { require(bytes.size == 2); val code = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255); return entries.first { it.opcode == code } } }\n}\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generated) }
tasks.named("compileKotlin") { dependsOn(generateCodecs) }

dependencies { testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
