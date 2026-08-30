package com.milelog.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.milelog.data.DayRange
import com.milelog.data.DeductionClass
import com.milelog.data.Fmt
import com.milelog.data.Repo
import com.milelog.data.TaxSummary
import com.milelog.data.TxnType
import java.io.File
import java.time.LocalDate

/** Builds the year-end spreadsheet and hands it to the mail app. */
object Exporter {

    private const val XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    data class Result(val xlsx: File, val csvTrips: File, val csvTxns: File, val summary: TaxSummary)

    suspend fun buildYear(context: Context, year: Int): Result =
        build(context, DayRange.forYear(year), "MileLog-$year")

    suspend fun build(context: Context, range: DayRange, baseName: String): Result {
        val repo = Repo.get(context)
        val summary = repo.summarize(range)
        val trips = repo.trips.listBetween(range.fromMillis, range.toMillis)
        val txns = repo.txns.listBetween(range.fromDay, range.toDay)
        val purposes = repo.purposes.allNow().associateBy { it.id }
        val categories = repo.categories.allNow().associateBy { it.id }
        val vehicles = repo.vehicles.allNow().associateBy { it.id }

        val dir = File(context.filesDir, "exports").apply { mkdirs() }

        // ---- Summary ----
        val summarySheet = Xlsx.Sheet("Summary").apply {
            columnWidths = listOf(30.0, 18.0, 14.0, 14.0, 14.0)
            row(Xlsx.Cell.Text("MileLog summary", Xlsx.BOLD))
            row(
                Xlsx.Cell.Text("Period"),
                Xlsx.Cell.Text("${Fmt.date(range.fromDay)} through ${Fmt.date(range.toDay)}")
            )
            row(Xlsx.Cell.Text("Generated"), Xlsx.Cell.Day(LocalDate.now().toEpochDay()))
            blank()

            row(Xlsx.Cell.Text("Miles", Xlsx.HEADER), Xlsx.Cell.Text("", Xlsx.HEADER))
            row(Xlsx.Cell.Text("Total miles driven"), Xlsx.Cell.Num(summary.totalMiles, Xlsx.MILES))
            row(Xlsx.Cell.Text("Business miles"), Xlsx.Cell.Num(summary.businessMiles, Xlsx.MILES))
            row(Xlsx.Cell.Text("Medical / charity / moving miles"), Xlsx.Cell.Num(summary.otherMiles, Xlsx.MILES))
            row(Xlsx.Cell.Text("Personal miles"), Xlsx.Cell.Num(summary.personalMiles, Xlsx.MILES))
            row(Xlsx.Cell.Text("Unclassified miles"), Xlsx.Cell.Num(summary.unclassifiedMiles, Xlsx.MILES))
            row(Xlsx.Cell.Text("Number of trips"), Xlsx.Cell.Num(summary.tripCount.toDouble()))
            blank()

            row(
                Xlsx.Cell.Text("Mileage deduction", Xlsx.HEADER),
                Xlsx.Cell.Text("Class", Xlsx.HEADER),
                Xlsx.Cell.Text("Miles", Xlsx.HEADER),
                Xlsx.Cell.Text("Rate (cents)", Xlsx.HEADER),
                Xlsx.Cell.Text("Deduction", Xlsx.HEADER)
            )
            summary.slices.forEach { s ->
                row(
                    Xlsx.Cell.Text(s.label),
                    Xlsx.Cell.Text(s.deductionClass.name.lowercase().replaceFirstChar { it.uppercase() }),
                    Xlsx.Cell.Num(s.miles, Xlsx.MILES),
                    Xlsx.Cell.Num(s.centsPerMile),
                    Xlsx.Cell.Num(s.dollars, Xlsx.MONEY)
                )
            }
            row(
                Xlsx.Cell.Text("Total deduction", Xlsx.BOLD),
                Xlsx.Cell.Blank, Xlsx.Cell.Blank, Xlsx.Cell.Blank,
                Xlsx.Cell.Num(summary.deduction, Xlsx.MONEY)
            )
            if (summary.ratesEstimated) {
                row(Xlsx.Cell.Text("Note: some trips fell outside the rate table and used the most recent rate. Check Settings."))
            }
            if (summary.unclassifiedMiles > 0) {
                row(Xlsx.Cell.Text("Note: unclassified miles are not in the deduction. Classify them in the app and rebuild."))
            }
            blank()

            row(Xlsx.Cell.Text("Money", Xlsx.HEADER), Xlsx.Cell.Text("", Xlsx.HEADER))
            row(Xlsx.Cell.Text("Revenue"), Xlsx.Cell.Num(summary.revenueCents / 100.0, Xlsx.MONEY))
            row(Xlsx.Cell.Text("Expenses"), Xlsx.Cell.Num(summary.expenseCents / 100.0, Xlsx.MONEY))
            row(Xlsx.Cell.Text("Profit", Xlsx.BOLD), Xlsx.Cell.Num(summary.profitCents / 100.0, Xlsx.MONEY))
            blank()

            row(Xlsx.Cell.Text("Expenses by category", Xlsx.HEADER), Xlsx.Cell.Text("Amount", Xlsx.HEADER))
            summary.expensesByCategory.forEach {
                row(Xlsx.Cell.Text(it.name), Xlsx.Cell.Num(it.cents / 100.0, Xlsx.MONEY))
            }
            blank()

            row(Xlsx.Cell.Text("Miles by purpose", Xlsx.HEADER), Xlsx.Cell.Text("Miles", Xlsx.HEADER))
            summary.milesByPurpose.forEach {
                row(Xlsx.Cell.Text(it.name ?: "Unclassified"), Xlsx.Cell.Num(it.miles, Xlsx.MILES))
            }
        }

        // ---- Trips ----
        val tripSheet = Xlsx.Sheet("Trips").apply {
            columnWidths = listOf(12.0, 10.0, 10.0, 14.0, 12.0, 10.0, 11.0, 12.0, 14.0, 30.0, 30.0, 24.0, 16.0, 10.0)
            header(
                "Date", "Start", "End", "Purpose", "Class", "Miles", "Rate (cents)",
                "Deduction", "Vehicle", "From", "To", "Notes", "Tags", "Source"
            )
        }
        for (t in trips.sortedBy { it.startEpoch }) {
            val purpose = t.purposeId?.let { purposes[it] }
            val cls = purpose?.deductionClass
            val day = Fmt.epochDayOf(t.startEpoch)
            val (rate, _) = repo.rateFor(day)
            val cents = when (cls) {
                DeductionClass.BUSINESS -> rate.businessCents
                DeductionClass.MEDICAL -> rate.medicalCents
                DeductionClass.CHARITY -> rate.charityCents
                DeductionClass.MOVING -> rate.movingCents
                else -> 0.0
            }
            tripSheet.row(
                Xlsx.Cell.Day(day),
                Xlsx.Cell.Text(Fmt.time(t.startEpoch)),
                Xlsx.Cell.Text(Fmt.time(t.endEpoch)),
                Xlsx.Cell.Text(purpose?.name ?: "Unclassified"),
                Xlsx.Cell.Text(cls?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""),
                Xlsx.Cell.Num(t.miles, Xlsx.MILES),
                Xlsx.Cell.Num(cents),
                Xlsx.Cell.Num(t.miles * cents / 100.0, Xlsx.MONEY),
                Xlsx.Cell.Text(t.vehicleId?.let { vehicles[it]?.name } ?: ""),
                Xlsx.Cell.Text(t.startAddress),
                Xlsx.Cell.Text(t.endAddress),
                Xlsx.Cell.Text(t.notes),
                Xlsx.Cell.Text(t.tags),
                Xlsx.Cell.Text(t.source.name.lowercase())
            )
        }

        // ---- Expenses and revenue ----
        fun moneySheet(name: String, type: TxnType) = Xlsx.Sheet(name).apply {
            columnWidths = listOf(12.0, 22.0, 20.0, 16.0, 12.0, 28.0, 16.0, 22.0)
            header("Date", "Merchant", "Category", "Purpose", "Amount", "Notes", "Tags", "Receipt file")
            txns.filter { it.type == type }.sortedBy { it.dateEpochDay }.forEach { x ->
                row(
                    Xlsx.Cell.Day(x.dateEpochDay),
                    Xlsx.Cell.Text(x.merchant),
                    Xlsx.Cell.Text(x.categoryId?.let { categories[it]?.name } ?: ""),
                    Xlsx.Cell.Text(x.purposeId?.let { purposes[it]?.name } ?: ""),
                    Xlsx.Cell.Num(x.amountCents / 100.0, Xlsx.MONEY),
                    Xlsx.Cell.Text(x.notes),
                    Xlsx.Cell.Text(x.tags),
                    Xlsx.Cell.Text(x.receiptPath?.let { File(it).name } ?: "")
                )
            }
        }

        val receiptSheet = Xlsx.Sheet("Receipts").apply {
            columnWidths = listOf(12.0, 22.0, 12.0, 30.0)
            header("Date", "Merchant", "Amount", "Photo file")
            txns.filter { !it.receiptPath.isNullOrBlank() }.sortedBy { it.dateEpochDay }.forEach { x ->
                row(
                    Xlsx.Cell.Day(x.dateEpochDay),
                    Xlsx.Cell.Text(x.merchant),
                    Xlsx.Cell.Num(x.amountCents / 100.0, Xlsx.MONEY),
                    Xlsx.Cell.Text(File(x.receiptPath!!).name)
                )
            }
        }

        val xlsx = File(dir, "$baseName.xlsx")
        Xlsx.write(
            xlsx,
            listOf(
                summarySheet, tripSheet,
                moneySheet("Expenses", TxnType.EXPENSE),
                moneySheet("Revenue", TxnType.REVENUE),
                receiptSheet
            )
        )

        // ---- CSV backups of the same data ----
        val csvTrips = File(dir, "$baseName-trips.csv")
        csvTrips.writeText(buildString {
            appendLine("Date,Start,End,Purpose,Class,Miles,Vehicle,From,To,Notes,Tags,Source")
            trips.sortedBy { it.startEpoch }.forEach { t ->
                val p = t.purposeId?.let { purposes[it] }
                appendLine(
                    csvRow(
                        Fmt.date(Fmt.epochDayOf(t.startEpoch)), Fmt.time(t.startEpoch), Fmt.time(t.endEpoch),
                        p?.name ?: "Unclassified", p?.deductionClass?.name ?: "",
                        String.format(java.util.Locale.US, "%.2f", t.miles),
                        t.vehicleId?.let { vehicles[it]?.name } ?: "",
                        t.startAddress, t.endAddress, t.notes, t.tags, t.source.name
                    )
                )
            }
        })

        val csvTxns = File(dir, "$baseName-transactions.csv")
        csvTxns.writeText(buildString {
            appendLine("Date,Type,Merchant,Category,Purpose,Amount,Notes,Tags,Receipt")
            txns.sortedBy { it.dateEpochDay }.forEach { x ->
                appendLine(
                    csvRow(
                        Fmt.date(x.dateEpochDay), x.type.name, x.merchant,
                        x.categoryId?.let { categories[it]?.name } ?: "",
                        x.purposeId?.let { purposes[it]?.name } ?: "",
                        String.format(java.util.Locale.US, "%.2f", x.amountCents / 100.0),
                        x.notes, x.tags, x.receiptPath?.let { File(it).name } ?: ""
                    )
                )
            }
        })

        return Result(xlsx, csvTrips, csvTxns, summary)
    }

    private fun csvRow(vararg fields: String): String = fields.joinToString(",") { csvField(it) }

    /**
     * Text that starts with =, +, - or @ is run as a formula by Excel, LibreOffice and
     * Sheets. Merchant names and notes can come straight out of somebody else's export
     * file, so they are pinned as text before they go anywhere near a spreadsheet.
     */
    private fun csvField(field: String): String {
        val safe = if (field.isNotEmpty() && field.first() in "=+-@\t\r") "'" + field else field
        val mustQuote = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (mustQuote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Opens the mail app with the file already attached and the address filled in.
     * Nothing sends until the user taps send.
     */
    fun emailIntent(context: Context, files: List<File>, subject: String, body: String): Intent {
        val to = Repo.get(context).prefs.exportEmail
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = XLSX_MIME
        if (to.isNotBlank()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, body)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Send with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Drops a copy in the phone's Downloads folder so it is easy to find later. */
    fun saveToDownloads(context: Context, file: File): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, if (file.extension == "csv") "text/csv" else XLSX_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MileLog")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrNull()
}
