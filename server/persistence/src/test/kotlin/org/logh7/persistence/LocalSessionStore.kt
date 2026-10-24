package org.logh7.persistence

import org.logh7.engine.SessionRules
import java.nio.file.Path

/** Process-crash tests explicitly use Windows file-only durability; Linux keeps directory force. */
internal fun localSessionStore(directory: Path, rules: SessionRules, initialGeneration: Long = 1,
    checkpointEvery: Long = 256, failPoint: (StorePoint) -> Unit = {}) = FileSessionStore(directory, rules,
    initialGeneration, checkpointEvery, directorySync = if (System.getProperty("os.name").startsWith("Windows"))
        DirectorySync.FILE_CONTENTS_ONLY else DirectorySync.REQUIRED, failPoint = failPoint)
