package com.milelog.export

import android.content.Context
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
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open that file." }
            unzipTo(input, staging)
        }

        val stagedDb = File(staging, "db/$DB_NAME")
        require(stagedDb.exists()) { "That file is not a MileLog backup." }

        MileLogDb.reset()
        Repo.reset()

        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { File("${dbFile.path}$it").delete() }
        stagedDb.copyTo(dbFile, overwrite = true)
        File(staging, "db/$DB_NAME-wal").takeIf { it.exists() }?.copyTo(File("${dbFile.path}-wal"), overwrite = true)

        val receipts = File(context.filesDir, "receipts").apply { mkdirs() }
        File(staging, "receipts").listFiles()?.forEach { f ->
            f.copyTo(File(receipts, f.name), overwrite = true)
        }
        staging.deleteRecursively()
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
