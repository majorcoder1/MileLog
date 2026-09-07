package com.milelog.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One rate period's slice of a year's driving. */
data class RateSlice(
    val label: String,
    val deductionClass: DeductionClass,
    val miles: Double,
    val centsPerMile: Double
) {
    val dollars: Double get() = miles * centsPerMile / 100.0
}

data class TaxSummary(
    val range: DayRange,
    val totalMiles: Double = 0.0,
    val businessMiles: Double = 0.0,
    val personalMiles: Double = 0.0,
    val unclassifiedMiles: Double = 0.0,
    val otherMiles: Double = 0.0,
    val milesByPurpose: List<PurposeMiles> = emptyList(),
    val slices: List<RateSlice> = emptyList(),
    val revenueCents: Long = 0,
    val expenseCents: Long = 0,
    val expensesByCategory: List<CategoryTotal> = emptyList(),
    val tripCount: Int = 0,
    /** True when some driving fell outside every rate row we know about. */
    val ratesEstimated: Boolean = false
) {
    val deduction: Double get() = slices.sumOf { it.dollars }
    val profitCents: Long get() = revenueCents - expenseCents
}

class Repo(context: Context) {
    val db: MileLogDb = MileLogDb.get(context)
    val prefs = Prefs(context)

    val trips get() = db.trips()
    val txns get() = db.txns()
    val purposes get() = db.purposes()
    val categories get() = db.categories()
    val vehicles get() = db.vehicles()
    val places get() = db.places()
    val rates get() = db.rates()
    val schedule get() = db.schedule()
    val service get() = db.service()

    suspend fun seed() = Seed.runIfNeeded(db)

    /**
     * Every tag used before, across trips and transactions, so they can be picked
     * rather than retyped. Tags live as comma-separated text on each record, which is
     * why they are split out here rather than queried directly.
     */
    fun knownTags(): Flow<List<String>> =
        combine(db.trips().allTagText(), db.txns().allTagText()) { trips, txns ->
            (trips + txns)
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it.lowercase() }
                .reduce { _, keep, _ -> keep }
                .values
                .sortedBy { it.lowercase() }
        }

    fun purposesFlow(): Flow<List<Purpose>> = db.purposes().all()
    fun vehiclesFlow(): Flow<List<Vehicle>> = db.vehicles().all()

    /**
     * The rate row covering [day]. Days past the last row we know about fall back to
     * that row, so a new year still produces a number instead of a zero.
     */
    suspend fun rateFor(day: Long): Pair<MileageRate, Boolean> {
        db.rates().forDay(day)?.let { return it to false }
        val all = db.rates().allNow()
        val nearest = all.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
            ?: all.minByOrNull { it.fromEpochDay }
            ?: MileageRate(
                label = "unset", fromEpochDay = day, toEpochDay = day,
                businessCents = 0.0, medicalCents = 0.0, charityCents = 0.0, movingCents = 0.0
            )
        return nearest to true
    }

    private fun MileageRate.centsFor(cls: DeductionClass): Double = when (cls) {
        DeductionClass.BUSINESS -> businessCents
        DeductionClass.MEDICAL -> medicalCents
        DeductionClass.CHARITY -> charityCents
        DeductionClass.MOVING -> movingCents
        DeductionClass.PERSONAL -> 0.0
    }

    /**
     * Everything the Taxes tab and the spreadsheet need for one span of days.
     *
     * Runs off the main thread and reads the rate table once. It used to query the rates
     * once per trip from whatever dispatcher called it, which on a full year meant
     * thousands of database round trips on the UI thread.
     */
    suspend fun summarize(range: DayRange): TaxSummary = withContext(Dispatchers.IO) {
        val tripList = db.trips().listBetween(range.fromMillis, range.toMillis)
        val purposeMap = db.purposes().allNow().associateBy { it.id }
        val rateTable = db.rates().allNow()

        fun rateOn(day: Long): Pair<MileageRate, Boolean> {
            rateTable.firstOrNull { day in it.fromEpochDay..it.toEpochDay }?.let { return it to false }
            val nearest = rateTable.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
                ?: rateTable.minByOrNull { it.fromEpochDay }
                ?: return MileageRate(
                    label = "unset", fromEpochDay = day, toEpochDay = day,
                    businessCents = 0.0, medicalCents = 0.0, charityCents = 0.0, movingCents = 0.0
                ) to true
            return nearest to true
        }

        var business = 0.0
        var personal = 0.0
        var unclassified = 0.0
        var other = 0.0
        var estimated = false

        // Keyed by rate label + deduction class so a mid-year rate change shows as two lines.
        val buckets = LinkedHashMap<Pair<String, DeductionClass>, Triple<Double, Double, DeductionClass>>()

        for (t in tripList) {
            val purpose = t.purposeId?.let { purposeMap[it] }
            val cls = purpose?.deductionClass
            when {
                cls == null -> unclassified += t.miles
                cls == DeductionClass.BUSINESS -> business += t.miles
                cls == DeductionClass.PERSONAL -> personal += t.miles
                else -> other += t.miles
            }
            if (cls == null || cls == DeductionClass.PERSONAL) continue

            val day = Fmt.epochDayOf(t.startEpoch)
            val (rate, wasEstimated) = rateOn(day)
            if (wasEstimated) estimated = true
            val cents = rate.centsFor(cls)
            val key = rate.label to cls
            val prev = buckets[key]
            buckets[key] = Triple((prev?.first ?: 0.0) + t.miles, cents, cls)
        }

        val slices = buckets.map { (key, v) ->
            RateSlice(label = key.first, deductionClass = v.third, miles = v.first, centsPerMile = v.second)
        }.sortedWith(compareBy({ it.label }, { it.deductionClass.name }))

        val txnList = db.txns().listBetween(range.fromDay, range.toDay)
        val categoryMap = db.categories().allNow().associateBy { it.id }
        val expenseByCat = txnList.filter { it.type == TxnType.EXPENSE }
            .groupBy { it.categoryId }
            .map { (catId, rows) ->
                CategoryTotal(categoryMap[catId]?.name ?: "Uncategorized", rows.sumOf { it.amountCents })
            }
            .sortedByDescending { it.cents }

        TaxSummary(
            range = range,
            totalMiles = tripList.sumOf { it.miles },
            businessMiles = business,
            personalMiles = personal,
            unclassifiedMiles = unclassified,
            otherMiles = other,
            milesByPurpose = tripList.groupBy { it.purposeId }.map { (pid, rows) ->
                val p = pid?.let { purposeMap[it] }
                PurposeMiles(pid, p?.name, p?.deductionClass, rows.sumOf { it.miles })
            }.sortedByDescending { it.miles },
            slices = slices,
            revenueCents = txnList.filter { it.type == TxnType.REVENUE }.sumOf { it.amountCents },
            expenseCents = txnList.filter { it.type == TxnType.EXPENSE }.sumOf { it.amountCents },
            expensesByCategory = expenseByCat,
            tripCount = tripList.size,
            ratesEstimated = estimated
        )
    }

    /** Years that have any trip or transaction in them, newest first. */
    suspend fun yearsWithData(): List<Int> = withContext(Dispatchers.IO) {
        val years = sortedSetOf<Int>()
        db.trips().all().forEach {
            years += LocalDate.ofEpochDay(Fmt.epochDayOf(it.startEpoch)).year
        }
        db.txns().all().forEach { years += LocalDate.ofEpochDay(it.dateEpochDay).year }
        years += LocalDate.now().year
        years.sortedDescending()
    }

    /**
     * Best guess at what the odometer reads now: the highest figure ever written down
     * for the vehicle, plus every tracked mile since it was written. Recorded trips only
     * cover driving the app saw, so this reads low rather than high — which is the safe
     * direction for a service interval.
     */
    suspend fun estimatedOdometer(vehicleId: Long?): Pair<Double, Long?> = withContext(Dispatchers.IO) {
        val vehicle = vehicleId?.let { id -> db.vehicles().allNow().firstOrNull { it.id == id } }
        val fromService = vehicleId?.let { db.service().highestOdometer(it) } ?: 0.0
        val baseline = maxOf(vehicle?.odometer ?: 0.0, fromService)
        if (baseline <= 0.0) return@withContext 0.0 to null

        // Miles driven since whichever reading the baseline came from.
        val since = db.service().allLogsNow()
            .filter { it.vehicleId == vehicleId && it.odometer >= baseline - 0.5 }
            .minOfOrNull { it.dateEpochDay }

        val driven = if (since != null) {
            val from = DayRange(since, LocalDate.now().toEpochDay())
            db.trips().listBetween(from.fromMillis, from.toMillis)
                .filter { vehicleId == null || it.vehicleId == vehicleId }
                .sumOf { it.miles }
        } else 0.0
        (baseline + driven) to since
    }

    /** Every job being watched, with how close each is to being due. */
    suspend fun serviceStatuses(): List<ServiceStatus> = withContext(Dispatchers.IO) {
        val vehicles = db.vehicles().allNow().associateBy { it.id }
        val logs = db.service().allLogsNow()
        val today = LocalDate.now().toEpochDay()
        val odometerCache = mutableMapOf<Long?, Double>()

        db.schedule().enabledReminders().map { reminder ->
            val last = logs.firstOrNull { it.reminderId == reminder.id }
                ?: logs.firstOrNull { it.title.equals(reminder.title, true) && it.vehicleId == reminder.vehicleId }

            val odometer = odometerCache.getOrPut(reminder.vehicleId) {
                estimatedOdometer(reminder.vehicleId).first
            }

            val milesLeft = reminder.intervalMiles?.let { interval ->
                last?.odometer?.let { done -> done + interval - odometer }
            }
            val daysLeft = reminder.intervalDays?.let { interval ->
                last?.dateEpochDay?.let { done -> (done + interval - today).toInt() }
            }

            val state = when {
                last == null -> ServiceState.NEVER_DONE
                (milesLeft != null && milesLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> ServiceState.DUE
                (milesLeft != null && milesLeft <= DUE_SOON_MILES) ||
                    (daysLeft != null && daysLeft <= DUE_SOON_DAYS) -> ServiceState.DUE_SOON
                else -> ServiceState.OK
            }

            ServiceStatus(
                reminder = reminder,
                vehicleName = reminder.vehicleId?.let { vehicles[it]?.name },
                lastDone = last,
                state = state,
                milesRemaining = milesLeft,
                daysRemaining = daysLeft
            )
        }.sortedBy { it.state.ordinal }
    }

    suspend fun defaultVehicleId(): Long? {
        val saved = prefs.defaultVehicleId
        if (saved != 0L) return saved
        return db.vehicles().defaultVehicle()?.id ?: db.vehicles().allNow().firstOrNull()?.id
    }

    companion object {
        /** Close enough to warn about. */
        const val DUE_SOON_MILES = 500.0
        const val DUE_SOON_DAYS = 14

        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo = instance ?: synchronized(this) {
            instance ?: Repo(context.applicationContext).also { instance = it }
        }
        fun reset() = synchronized(this) { instance = null }
    }
}
