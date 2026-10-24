package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val SUFFIX = ".json"

/**
 * One JSON file per gateway in [dir] (`noBackupFilesDir`): a copy of what its agents last
 * reported, never a chat record, so it stays out of cloud backup.
 */
class ProjectSnapshotStore(
    private val dir: File,
    private val json: Json,
) {
    internal fun readAll(): Map<String, ProjectSnapshot> =
        dir
            .listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { file -> decode(file)?.let { file.name.removeSuffix(SUFFIX) to it } }
            .toMap()

    /** Temp file + rename, so a crash mid-write leaves the previous snapshot whole. */
    internal fun write(
        gatewaySourceId: String,
        snapshot: ProjectSnapshot,
    ) {
        dir.mkdirs()
        val temp = File(dir, "$gatewaySourceId$SUFFIX.tmp")
        temp.writeText(json.encodeToString(ProjectSnapshot.serializer(), snapshot))
        Files.move(temp.toPath(), fileFor(gatewaySourceId).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun delete(gatewaySourceId: String) {
        fileFor(gatewaySourceId).delete()
    }

    private fun fileFor(gatewaySourceId: String) = File(dir, "$gatewaySourceId$SUFFIX")

    // An unreadable snapshot is only a stale cache; the next fetch rewrites it.
    private fun decode(file: File): ProjectSnapshot? =
        try {
            json.decodeFromString(ProjectSnapshot.serializer(), file.readText())
        } catch (_: SerializationException) {
            null
        }
}
