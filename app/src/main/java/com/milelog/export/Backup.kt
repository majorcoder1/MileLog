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
        val dbFile = context.getDatabasePath(DB_NAME)
        val receipts = File(context.filesDir, "receipts")

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
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

        repo.prefs.lastBackupEpoch = System.currentTimeMillis()
        list(context).drop(KEEP).forEach { it.delete() }
        return out
    }

    /**
     * Replaces everything with the contents of a backup zip. The caller should
     * restart the app afterwards so nothing is holding a stale handle.
     */
    fun restore(context: Context, uri: Uri): Result<Unit> = runCatching {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val dbFile = context.getDatabasePath(DB_NAME)
        val safety = File(context.cacheDir, "pre-restore-$DB_NAME")

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open that file." }
                unzipTo(input, staging)
            }

            val stagedDb = File(staging, "db/$DB_NAME")
            require(stagedDb.exists()) { "That file is not a MileLog backup." }
            // Prove it is really one of ours BEFORE anything on the phone is touched.
            require(holdsMileLogData(stagedDb)) {
                "That file is not a MileLog backup, or it was written by a newer version."
            }

            MileLogDb.reset()
            Repo.reset()
            dbFile.parentFile?.mkdirs()

            // Keep the current data until the replacement is proven to open.
            if (dbFile.exists()) dbFile.copyTo(safety, overwrite = true)

            try {
                listOf("", "-wal", "-shm").forEach { File("${dbFile.path}$it").delete() }
                stagedDb.copyTo(dbFile, overwrite = true)
                File(staging, "db/$DB_NAME-wal").takeIf { it.exists() }
                    ?.copyTo(File("${dbFile.path}-wal"), overwrite = true)
                require(holdsMileLogData(dbFile)) { "The restored file would not open." }
            } catch (failure: Exception) {
                // Put the original back rather than leave him with nothing.
                if (safety.exists()) {
                    listOf("", "-wal", "-shm").forEach { File("${dbFile.path}$it").delete() }
                    safety.copyTo(dbFile, overwrite = true)
                }
                MileLogDb.reset()
                Repo.reset()
                throw IllegalStateException(
                    "Restore failed and your data was put back. ${failure.message}", failure
                )
            }

            val receipts = File(context.filesDir, "receipts").apply { mkdirs() }
            File(staging, "receipts").listFiles()?.forEach { f ->
                f.copyTo(File(receipts, f.name), overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
            safety.delete()
        }
    }

    /** Opens a candidate file read-only and checks it carries the tables we expect. */
    private fun holdsMileLogData(file: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('trips','txns','purposes')",
                null
            ).use { it.count >= 3 }
        }
    }.getOrDefault(false)

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
