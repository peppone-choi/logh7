package org.logh7.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Call with this session store's writer lock held. Moving/restoring a world preserves its settlement identity. */
object FileWorldIdentity {
    fun loadOrCreate(directory:Path,directorySync:DirectorySync):String {
        require(Files.isDirectory(directory))
        val file=directory.resolve("world.identity")
        if(Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file)==37L) {"Invalid world identity file"}
            val value=Files.readString(file)
            require(value==UUID.fromString(value.trim()).toString()+"\n") {"Invalid world identity file"}
            return value.trim()
        }
        val identity=UUID.randomUUID().toString()
        val temporary=Files.createTempFile(directory,"world-identity-",".tmp")
        try {
            FileChannel.open(temporary,WRITE).use {channel->
                val bytes=ByteBuffer.wrap((identity+"\n").toByteArray(Charsets.US_ASCII))
                while(bytes.hasRemaining())channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary,file,ATOMIC_MOVE)
            if(directorySync==DirectorySync.REQUIRED)FileChannel.open(directory,READ).use {it.force(true)}
        } finally {Files.deleteIfExists(temporary)}
        return identity
    }
}
