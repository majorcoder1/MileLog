package com.milelog.export

import android.content.Context
import android.net.Uri
import com.milelog.data.Category
import com.milelog.data.CategoryKind
import com.milelog.data.DeductionClass
import com.milelog.data.Purpose
import com.milelog.data.Repo
import com.milelog.data.Trip
import com.milelog.data.TripSource
import com.milelog.data.Txn
import com.milelog.data.TxnType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads an export from another mileage app — Everlance in particular — and folds it in.
 *
 * Their column names move around between exports and report types, so nothing here is
 * hard-coded to one layout. Headers are normalised and matched against a list of
 * spellings, and anything it cannot place is reported rather than guessed at.
 */
object CsvImport {

    data class Row(val values: Map<String, String>) {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> values[k]?.takeIf { it.isNotBlank() } }
    }

    enum class Kind { TRIPS, TRANSACTIONS, UNKNOWN }

    data class Preview(
        val kind: Kind,
        val fileName: String,
        val headers: List<String>,
        val tripCount: Int = 0,
        val txnCount: Int = 0,
        val miles: Double = 0.0,
        val expenseCents: Long = 0,
        val revenueCents: Long = 0,
        val firstDay: Long? = null,
        val lastDay: Long? = null,
        val duplicates: Int = 0,
        val skipped: Int = 0,
        val problem: String? = null
    ) {
        val usable: Boolean get() = problem == null && (tripCount > 0 || txnCount > 0)
    }

    data class Result(val tripsAdded: Int, val txnsAdded: Int, val skipped: Int)

    // ---- column spellings we understand -------------------------------------------------

    private val DATE = listOf("date", "tripdate", "day", "startdate", "transactiondate", "datetime")
    private val START_TIME = listOf("starttime", "startedat", "begin", "begintime", "timestart")
    private val END_TIME = listOf("endtime", "endedat", "finish", "finishtime", "timeend")
    private val MILES = listOf("miles", "distance", "distancemiles", "mileage", "tripdistance", "totalmiles")
    // Trips file: Everlance calls the work/personal column "Purpose", others call it "Category".
    private val TRIP_PURPOSE = listOf("purpose", "category", "type", "tag", "classification", "workpurpose", "trippurpose")
    // Transactions file: "Category" there means the expense category, not the purpose.
    private val TXN_PURPOSE = listOf("purpose", "tag", "classification", "workpurpose")
    private val NOTES = listOf("notes", "note", "description", "memo", "comment")
    private val START_ADDR = listOf("startlocation", "startaddress", "origin", "from", "startingpoint")
    private val END_ADDR = listOf("endlocation", "endaddress", "destination", "to", "endingpoint")
    private val VEHICLE = listOf("vehicle", "car", "vehiclename")
    private val MERCHANT = listOf("merchant", "merchantname", "vendor", "payee", "name")
    private val AMOUNT = listOf("amount", "total", "value", "price", "cost", "amountusd")
    private val CATEGORY = listOf("category", "expensecategory", "transactioncategory", "subcategory")
    private val TXN_TYPE = listOf("type", "transactiontype", "kind", "direction")

    /** Header text down to letters and digits, so "Start Location" and "start_location" match. */
    private fun normalise(header: String): String =
        header.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    // ---- reading -----------------------------------------------------------------------

    fun read(context: Context, uri: Uri): Pair<List<String>, List<Row>> {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Could not open that file.")

        val lines = splitRecords(text.removePrefix("﻿"))
        if (lines.isEmpty()) return emptyList<String>() to emptyList()

        val rawHeaders = lines.first()
        val headers = rawHeaders.map { normalise(it) }
        val rows = lines.drop(1)
            .filter { cells -> cells.any { it.isNotBlank() } }
            .map { cells ->
                Row(headers.indices.associate { i -> headers[i] to (cells.getOrNull(i)?.trim() ?: "") })
            }
        return rawHeaders to rows
    }

    /** Splits CSV text into records, honouring quotes and newlines inside quoted fields. */
    internal fun splitRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    records.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); records.add(row)
        }
        return records.filter { it.any { cell -> cell.isNotBlank() } }
    }

    // ---- value parsing -----------------------------------------------------------------

    private val DATE_PATTERNS = listOf(
        "M/d/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "M/d/yy", "MM/dd/yy",
        "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "yyyy/MM/dd"
    )
    private val DATETIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss",
        "M/d/yyyy H:mm", "M/d/yyyy h:mm a", "MM/dd/yyyy HH:mm", "MM/dd/yyyy h:mm a"
    )
    private val TIME_PATTERNS = listOf("h:mm a", "hh:mm a", "H:mm", "HH:mm", "HH:mm:ss")

    fun parseDate(raw: String?): LocalDate? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        DATETIME_PATTERNS.forEach { p ->
            runCatching {
                return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p, Locale.US)).toLocalDate()
            }
        }
        DATE_PATTERNS.forEach { p ->
            runCatching {
                return LocalDate.parse(v, DateTimeFormatter.ofPattern(p, Locale.US))
            }
        }
        // A bare date at the front of a longer string, e.g. "03/02/2026 4:31 PM EST".
        val head = v.substringBefore(' ')
        if (head != v) return parseDate(head)
        return null
    }

    fun parseTime(raw: String?): LocalTime? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        TIME_PATTERNS.forEach { p ->
            runCatching { return LocalTime.parse(v.uppercase(Locale.US), DateTimeFormatter.ofPattern(p, Locale.US)) }
        }
        // "03/02/2026 4:31 PM" — take what follows the date.
        val tail = v.substringAfter(' ', "").trim()
        if (tail.isNotEmpty() && tail != v) return parseTime(tail)
        return null
    }

    /** "$1,234.56", "(12.34)" and "-12.34" all come back as cents. */
    fun parseCents(raw: String?): Long? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val negative = v.startsWith("(") && v.endsWith(")") || v.startsWith("-")
        val digits = v.filter { it.isDigit() || it == '.' }
        val amount = digits.toDoubleOrNull() ?: return null
        val cents = Math.round(amount * 100)
        return if (negative) -cents else cents
    }

    fun parseMiles(raw: String?): Double? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return v.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
    }

    fun detectKind(headers: List<String>): Kind {
        val h = headers.map { normalise(it) }.toSet()
        val hasMiles = MILES.any { it in h }
        val hasMoney = AMOUNT.any { it in h }
        val hasMerchant = MERCHANT.any { it in h }
        return when {
            hasMiles -> Kind.TRIPS
            hasMerchant || hasMoney -> Kind.TRANSACTIONS
            else -> Kind.UNKNOWN
        }
    }

    // ---- preview -----------------------------------------------------------------------

    suspend fun preview(context: Context, uri: Uri, fileName: String): Preview {
        val (rawHeaders, rows) = runCatching { read(context, uri) }
            .getOrElse { return Preview(Kind.UNKNOWN, fileName, emptyList(), problem = it.message) }

        if (rows.isEmpty()) {
            return Preview(Kind.UNKNOWN, fileName, rawHeaders, problem = "That file has no rows in it.")
        }
        val kind = detectKind(rawHeaders)
        if (kind == Kind.UNKNOWN) {
            return Preview(
                Kind.UNKNOWN, fileName, rawHeaders,
                problem = "Could not tell what this file holds. It needs a distance column " +
                    "for trips, or an amount column for transactions."
            )
        }

        val repo = Repo.get(context)
        var miles = 0.0
        var expense = 0L
        var revenue = 0L
        var count = 0
        var duplicates = 0
        var skipped = 0
        var first: Long? = null
        var last: Long? = null

        for (row in rows) {
            val date = parseDate(row.first(*DATE.toTypedArray()))
            if (date == null) { skipped++; continue }
            val day = date.toEpochDay()
            first = minOf(first ?: day, day)
            last = maxOf(last ?: day, day)

            if (kind == Kind.TRIPS) {
                val m = parseMiles(row.first(*MILES.toTypedArray()))
                if (m == null || m <= 0) { skipped++; continue }
                val start = startMillis(date, row)
                if (repo.trips.countMatching(start - 12 * 3600_000L, start + 12 * 3600_000L, m) > 0) {
                    duplicates++; continue
                }
                miles += m
                count++
            } else {
                val cents = parseCents(row.first(*AMOUNT.toTypedArray()))
                if (cents == null || cents == 0L) { skipped++; continue }
                val merchant = row.first(*MERCHANT.toTypedArray()).orEmpty()
                if (repo.txns.countMatching(day, merchant, Math.abs(cents)) > 0) {
                    duplicates++; continue
                }
                if (isRevenue(row, cents)) revenue += Math.abs(cents) else expense += Math.abs(cents)
                count++
            }
        }

        return Preview(
            kind = kind,
            fileName = fileName,
            headers = rawHeaders,
            tripCount = if (kind == Kind.TRIPS) count else 0,
            txnCount = if (kind == Kind.TRANSACTIONS) count else 0,
            miles = miles,
            expenseCents = expense,
            revenueCents = revenue,
            firstDay = first,
            lastDay = last,
            duplicates = duplicates,
            skipped = skipped
        )
    }

    // ---- committing --------------------------------------------------------------------

    suspend fun commit(context: Context, uri: Uri): Result {
        val repo = Repo.get(context)
        val (rawHeaders, rows) = read(context, uri)
        val kind = detectKind(rawHeaders)

        val purposes = repo.purposes.allNow().associateBy { it.name.lowercase(Locale.US) }.toMutableMap()
        val categories = repo.categories.allNow().associateBy { it.name.lowercase(Locale.US) }.toMutableMap()
        val vehicles = repo.vehicles.allNow()
        val defaultVehicle = repo.defaultVehicleId()

        suspend fun purposeFor(name: String?): Long? {
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            purposes[n.lowercase(Locale.US)]?.let { return it.id }
            val cls = when {
                n.contains("personal", true) || n.contains("commute", true) -> DeductionClass.PERSONAL
                n.contains("medical", true) -> DeductionClass.MEDICAL
                n.contains("charit", true) -> DeductionClass.CHARITY
                n.contains("moving", true) -> DeductionClass.MOVING
                else -> DeductionClass.BUSINESS
            }
            val id = repo.purposes.insert(Purpose(name = n, deductionClass = cls, sortOrder = 900))
            repo.purposes.allNow().firstOrNull { it.name.equals(n, true) }?.let {
                purposes[n.lowercase(Locale.US)] = it
                return it.id
            }
            return id.takeIf { it > 0 }
        }

        suspend fun categoryFor(name: String?, kindOf: CategoryKind): Long? {
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            categories[n.lowercase(Locale.US)]?.let { return it.id }
            repo.categories.insert(Category(name = n, kind = kindOf, sortOrder = 900))
            repo.categories.allNow().firstOrNull { it.name.equals(n, true) }?.let {
                categories[n.lowercase(Locale.US)] = it
                return it.id
            }
            return null
        }

        var trips = 0
        var txns = 0
        var skipped = 0

        for (row in rows) {
            val date = parseDate(row.first(*DATE.toTypedArray()))
            if (date == null) { skipped++; continue }
            val day = date.toEpochDay()

            if (kind == Kind.TRIPS) {
                val m = parseMiles(row.first(*MILES.toTypedArray()))
                if (m == null || m <= 0) { skipped++; continue }
                val start = startMillis(date, row)
                if (repo.trips.countMatching(start - 12 * 3600_000L, start + 12 * 3600_000L, m) > 0) {
                    skipped++; continue
                }
                val endTime = parseTime(row.first(*END_TIME.toTypedArray()))
                val end = if (endTime != null) {
                    date.atTime(endTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else start

                val vehicleName = row.first(*VEHICLE.toTypedArray())
                repo.trips.insert(
                    Trip(
                        startEpoch = start,
                        endEpoch = maxOf(end, start),
                        miles = m,
                        purposeId = purposeFor(row.first(*TRIP_PURPOSE.toTypedArray())),
                        vehicleId = vehicleName
                            ?.let { n -> vehicles.firstOrNull { it.name.equals(n, true) }?.id }
                            ?: defaultVehicle,
                        startAddress = row.first(*START_ADDR.toTypedArray()).orEmpty(),
                        endAddress = row.first(*END_ADDR.toTypedArray()).orEmpty(),
                        notes = row.first(*NOTES.toTypedArray()).orEmpty(),
                        source = TripSource.MANUAL
                    )
                )
                trips++
            } else {
                val cents = parseCents(row.first(*AMOUNT.toTypedArray()))
                if (cents == null || cents == 0L) { skipped++; continue }
                val merchant = row.first(*MERCHANT.toTypedArray()).orEmpty()
                if (repo.txns.countMatching(day, merchant, Math.abs(cents)) > 0) { skipped++; continue }

                val revenue = isRevenue(row, cents)
                repo.txns.insert(
                    Txn(
                        type = if (revenue) TxnType.REVENUE else TxnType.EXPENSE,
                        amountCents = Math.abs(cents),
                        dateEpochDay = day,
                        purposeId = purposeFor(row.first(*TXN_PURPOSE.toTypedArray())),
                        categoryId = categoryFor(
                            row.first(*CATEGORY.toTypedArray()),
                            if (revenue) CategoryKind.REVENUE else CategoryKind.EXPENSE
                        ),
                        merchant = merchant,
                        notes = row.first(*NOTES.toTypedArray()).orEmpty(),
                        vehicleId = defaultVehicle
                    )
                )
                txns++
            }
        }
        return Result(trips, txns, skipped)
    }

    private fun startMillis(date: LocalDate, row: Row): Long {
        val time = parseTime(row.first(*START_TIME.toTypedArray()))
            ?: parseTime(row.first(*DATE.toTypedArray()))
            ?: LocalTime.NOON
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Money coming in, rather than going out. */
    private fun isRevenue(row: Row, cents: Long): Boolean {
        val type = row.first(*TXN_TYPE.toTypedArray())?.lowercase(Locale.US).orEmpty()
        return when {
            type.contains("income") || type.contains("revenue") || type.contains("earning") ||
                type.contains("credit") || type.contains("deposit") -> true
            type.contains("expense") || type.contains("debit") || type.contains("spend") -> false
            // No type column: money written as a positive number is treated as spending,
            // which is what these exports almost always hold.
            else -> false
        }
    }
}
