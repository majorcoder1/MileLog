package com.milelog.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.milelog.data.MileLogDb
import com.milelog.data.Repo
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup is a zip of the database plus the receipt photos. Restoring swaps the
 * database file back in, so nothing is lost in translation.
 */
object Backup {

    private const val KEEP = 30
    private const val DB_NAME = "milelog.db"
    private const val MARKER = "milelog-backup"
    /** Must match the version on MileLogDb. */
    private const val SCHEMA_VERSION = 1

    fun dir(context: Context): File = File(context.filesDir, "backups").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Writes today's backup and trims anything older than the last [KEEP]. */
    fun create(context: Context, name: String? = null): File {
        val repo = Repo.get(context)
        // Fold the write-ahead log into the main file so one file is the whole picture.
        runCatching { repo.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }

        val fileName = name ?: "$MARKER-${LocalDate.now()}.zip"
        val out = File(dir(context), fileName)
        // Written under a temporary name and moved into place, so a run that dies
        // halfway cannot leave a truncated file looking like the latest backup.
        val partial = File(dir(context), "$fileName.part")
        val dbFile = context.getDatabasePath(DB_NAME)
        val receipts = File(context.filesDir, "receipts")

        ZipOutputStream(partial.outputStream().buffered()).use { zip ->
            listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { f ->
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("db/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            receipts.listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("receipts/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write("MileLog backup\ncreated=${System.currentTimeMillis()}\ndb=$DB_NAME\n".toByteArray())
            zip.closeEntry()
        }

        if (!partial.renameTo(out)) {
            partial.copyTo(out, overwrite = true)
            partial.delete()
        }

        repo.prefs.lastBackupEpoch = System.currentTimeMillis()
        list(context).drop(KEEP).forEach { it.delete() }
        return out
    }

    /**
     * Replaces everything with the contents of a backup zip.
     *
     * The order matters. The candidate is proved readable before anything on the phone
     * is touched, the live database is folded flat and copied aside before it is
     * deleted, and if putting the replacement in fails the original goes back. If even
     * that fails the set-aside copy is deliberately left on disk and named in the error,
     * because the alternative is having no copy at all.
     */
    fun restore(context: Context, uri: Uri): Result<Unit> = runCatching {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val dbFile = context.getDatabasePath(DB_NAME)
        // Beside the app's own files, not in the cache: Android empties the cache under
        // storage pressure, which is exactly when a restore is likely to be failing.
        val safety = File(File(context.filesDir, "restore-safety").apply { mkdirs() }, DB_NAME)
        var keepSafety = false

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open that file." }
                unzipTo(input, staging)
            }

            val stagedDb = File(staging, "db/$DB_NAME")
            require(stagedDb.exists()) { "That file is not a MileLog backup." }
            val problem = whyUnusable(stagedDb)
            require(problem == null) { problem!! }

            // Fold the write-ahead log into the main file while the database is still
            // open, so the copy set aside below is the whole picture.
            runCatching {
                Repo.get(context).db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            }
            MileLogDb.reset()
            Repo.reset()
            dbFile.parentFile?.mkdirs()

            if (dbFile.exists()) {
                dbFile.copyTo(safety, overwrite = true)
                require(safety.length() == dbFile.length()) {
                    "Could not set your current data aside, so the restore was not started."
                }
            }

            try {
                listOf("", "-wal", "-shm").forEach { File("${dbFile.path}$it").delete() }
                stagedDb.copyTo(dbFile, overwrite = true)
                File(staging, "db/$DB_NAME-wal").takeIf { it.exists() }
                    ?.copyTo(File("${dbFile.path}-wal"), overwrite = true)
                require(whyUnusable(dbFile) == null) { "The restored file would not open." }
            } catch (failure: Exception) {
                val recovered = runCatching {
                    if (safety.exists()) {
                        listOf("", "-wal", "-shm").forEach { File("${dbFile.path}$it").delete() }
                        safety.copyTo(dbFile, overwrite = true)
                    }
                }.isSuccess
                MileLogDb.reset()
                Repo.reset()
                if (recovered) {
                    throw IllegalStateException(
                        "Restore failed and your data was put back. ${failure.message}", failure
                    )
                }
                keepSafety = true
                throw IllegalStateException(
                    "Restore failed and your data could not be put back automatically. " +
                        "A copy of it is safe at ${safety.absolutePath} — do not clear the " +
                        "app's storage. ${failure.message}",
                    failure
                )
            }

            val receipts = File(context.filesDir, "receipts").apply { mkdirs() }
            File(staging, "receipts").listFiles()?.forEach { f ->
                f.copyTo(File(receipts, f.name), overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
            if (!keepSafety) safety.delete()
        }
    }

    /**
     * Opens a candidate read-write, so SQLite can replay a write-ahead log rather than
     * refusing the file, and returns why it cannot be used — or null if it can.
     */
    private fun whyUnusable(file: File): String? {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        }.getOrNull() ?: return "That file is not a database MileLog can read."

        return db.use {
            val tables = runCatching {
                it.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name IN ('trips','txns','purposes')",
                    null
                ).use { c -> c.count }
            }.getOrDefault(0)
            if (tables < 3) return@use "That file is not a MileLog backup."

            // Room keeps its schema version here. A backup from a newer build would be
            // installed and then refused on the next open, with the old data already gone.
            val version = runCatching {
                it.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }.getOrDefault(0)
            if (version > SCHEMA_VERSION) {
                return@use "That backup came from a newer version of MileLog ($version). " +
                    "Update the app before restoring it."
            }
            null
        }
    }

    private fun unzipTo(input: InputStream, target: File) {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(target, entry.name)
                // Never let a crafted zip write outside the staging folder.
                if (!outFile.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw SecurityException("Bad entry in backup: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
