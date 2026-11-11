package org.logh7.persistence

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Windows readers can briefly deny replacement. Retry the move of the already forced file. */
internal fun replaceAtomically(source: Path, target: Path, beforeMove: () -> Unit = {}) {
    for (attempt in 0..3) {
        try {
            beforeMove()
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
            return
        } catch (denied: AccessDeniedException) {
            if (attempt == 3) throw denied
            Thread.sleep(10L * (attempt + 1))
        }
    }
}
