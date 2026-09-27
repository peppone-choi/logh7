package org.logh7.persistence
import org.logh7.engine.WorldSnapshot

/** Persistence boundary only; PostgreSQL adapter/write-behind scheduling is pending. */
interface SnapshotStore { suspend fun append(snapshot: WorldSnapshot) }
