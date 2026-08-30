package com.milelog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milelog.data.Category
import com.milelog.data.CategoryKind
import com.milelog.data.DayRange
import com.milelog.data.DeductionClass
import com.milelog.data.Fmt
import com.milelog.data.MerchantUse
import com.milelog.data.Merchants
import com.milelog.data.MileageRate
import com.milelog.data.Period
import com.milelog.data.Purpose
import com.milelog.data.Repo
import com.milelog.data.ServiceReminder
import com.milelog.data.Shift
import com.milelog.data.TaxSummary
import com.milelog.data.Trip
import com.milelog.data.TripRow
import com.milelog.data.TripSource
import com.milelog.data.Txn
import com.milelog.data.TxnRow
import com.milelog.data.TxnType
import com.milelog.data.Vehicle
import com.milelog.data.WorkWindow
import com.milelog.tracking.TripTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Which period a screen is showing, plus the custom dates behind it. */
data class PeriodChoice(
    val period: Period = Period.TODAY,
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now()
) {
    val range: DayRange get() = DayRange.forPeriod(period, customFrom = from, customTo = to)
    val label: String
        get() = if (period == Period.CUSTOM) "${Fmt.dateTiny(from.toEpochDay())} – ${Fmt.dateTiny(to.toEpochDay())}"
        else period.label
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseVm(app: Application) : AndroidViewModel(app) {
    protected val repo = Repo.get(app)

    val purposes: StateFlow<List<Purpose>> = repo.purposesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vehicles: StateFlow<List<Vehicle>> = repo.vehiclesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Recomputes the totals whenever anything inside the period changes. */
    protected fun summaryFlow(choice: StateFlow<PeriodChoice>): StateFlow<TaxSummary> =
        choice.flatMapLatest { c ->
            val range = c.range
            combine(
                repo.trips.rowsBetween(range.fromMillis, range.toMillis),
                repo.txns.rowsBetween(range.fromDay, range.toDay),
                repo.purposesFlow()
            ) { _, _, _ -> range }
        }.mapLatest { range ->
            repo.summarize(range)
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            TaxSummary(DayRange.forPeriod(Period.TODAY))
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeVm(app: Application) : BaseVm(app) {

    private val _mileagePeriod = MutableStateFlow(PeriodChoice(Period.TODAY))
    val mileagePeriod: StateFlow<PeriodChoice> = _mileagePeriod

    private val _moneyPeriod = MutableStateFlow(PeriodChoice(Period.THIS_YEAR))
    val moneyPeriod: StateFlow<PeriodChoice> = _moneyPeriod

    private val _moneyPurposeId = MutableStateFlow<Long?>(null)
    val moneyPurposeId: StateFlow<Long?> = _moneyPurposeId

    private val _autoDetect = MutableStateFlow(repo.prefs.autoDetect)
    val autoDetect: StateFlow<Boolean> = _autoDetect

    val live = TripTracker.state

    val mileage: StateFlow<TaxSummary> = summaryFlow(_mileagePeriod)
    val money: StateFlow<TaxSummary> = summaryFlow(_moneyPeriod)

    val unclassifiedCount: StateFlow<Int> = repo.trips.unclassifiedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Revenue, expenses and profit narrowed to one company, when one is picked. */
    val moneyFiltered: StateFlow<Triple<Long, Long, Long>> =
        combine(_moneyPeriod, _moneyPurposeId) { p, id -> p.range to id }
            .flatMapLatest { (range, id) ->
                repo.txns.rowsBetween(range.fromDay, range.toDay).map { rows ->
                    val kept = rows.filter { id == null || it.purposeId == id }
                    val rev = kept.filter { it.type == TxnType.REVENUE }.sumOf { it.amountCents }
                    val exp = kept.filter { it.type == TxnType.EXPENSE }.sumOf { it.amountCents }
                    Triple(rev, exp, rev - exp)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0L, 0L, 0L))

    fun setMileagePeriod(c: PeriodChoice) { _mileagePeriod.value = c }
    fun setMoneyPeriod(c: PeriodChoice) { _moneyPeriod.value = c }
    fun setMoneyPurpose(id: Long?) { _moneyPurposeId.value = id }

    fun setAutoDetect(on: Boolean) {
        repo.prefs.autoDetect = on
        _autoDetect.value = on
    }

    fun refreshAutoDetect() { _autoDetect.value = repo.prefs.autoDetect }
}

enum class TripsTab(val label: String) { TRIPS("Trips"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/** null purposeId with unclassifiedOnly = false means "all trips". */
data class TripFilter(
    val purposeId: Long? = null,
    val unclassifiedOnly: Boolean = false,
    val period: PeriodChoice = PeriodChoice(Period.THIS_YEAR),
    val placeQuery: String = ""
) {
    val label: String
        get() = when {
            unclassifiedOnly -> "Unclassified"
            purposeId != null -> "Filtered"
            else -> "All trips"
        }
}

/** One row of a rolled-up Daily / Weekly / Monthly view. */
data class TripGroup(val label: String, val miles: Double, val deduction: Double, val count: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class TripsVm(app: Application) : BaseVm(app) {

    private val _tab = MutableStateFlow(TripsTab.TRIPS)
    val tab: StateFlow<TripsTab> = _tab

    private val _filter = MutableStateFlow(TripFilter())
    val filter: StateFlow<TripFilter> = _filter

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _undo = MutableStateFlow<List<Pair<Long, Long?>>>(emptyList())
    val undo: StateFlow<List<Pair<Long, Long?>>> = _undo

    val yearSummary: StateFlow<TaxSummary> =
        summaryFlow(MutableStateFlow(PeriodChoice(Period.THIS_YEAR)))

    val rows: StateFlow<List<TripRow>> = _filter.flatMapLatest { f ->
        val range = f.period.range
        repo.trips.rowsBetween(range.fromMillis, range.toMillis).map { list ->
            list.filter { row ->
                val purposeOk = when {
                    f.unclassifiedOnly -> row.purposeId == null
                    f.purposeId != null -> row.purposeId == f.purposeId
                    else -> true
                }
                val placeOk = f.placeQuery.isBlank() ||
                    row.startAddress.contains(f.placeQuery, true) ||
                    row.endAddress.contains(f.placeQuery, true)
                purposeOk && placeOk
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rateList: StateFlow<List<MileageRate>> = repo.rates.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Daily / weekly / monthly roll-ups of whatever the filter is showing. */
    val groups: StateFlow<List<TripGroup>> =
        combine(rows, _tab, purposes, rateList) { list, tab, purposeList, rates ->
        if (tab == TripsTab.TRIPS) return@combine emptyList()
        val classes = purposeList.associate { it.id to it.deductionClass }

        fun centsFor(day: Long, cls: DeductionClass): Double {
            val rate = rates.firstOrNull { day in it.fromEpochDay..it.toEpochDay }
                ?: rates.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
                ?: return 0.0
            return when (cls) {
                DeductionClass.BUSINESS -> rate.businessCents
                DeductionClass.MEDICAL -> rate.medicalCents
                DeductionClass.CHARITY -> rate.charityCents
                DeductionClass.MOVING -> rate.movingCents
                DeductionClass.PERSONAL -> 0.0
            }
        }

        val byKey = LinkedHashMap<String, MutableList<TripRow>>()
        list.sortedByDescending { it.startEpoch }.forEach { row ->
            val date = LocalDate.ofEpochDay(Fmt.epochDayOf(row.startEpoch))
            val key = when (tab) {
                TripsTab.DAILY -> Fmt.date(date.toEpochDay())
                TripsTab.WEEKLY -> {
                    val start = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
                    "Week of ${Fmt.dateTiny(start.toEpochDay())}"
                }
                else -> "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.year}"
            }
            byKey.getOrPut(key) { mutableListOf() } += row
        }
        byKey.map { (label, group) ->
            val deduction = group.sumOf { row ->
                val cls = row.purposeId?.let { id -> classes[id] } ?: return@sumOf 0.0
                val day = Fmt.epochDayOf(row.startEpoch)
                row.miles * centsFor(day, cls) / 100.0
            }
            TripGroup(
                label = label,
                miles = group.sumOf { it.miles },
                deduction = deduction,
                count = group.size
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTab(t: TripsTab) { _tab.value = t }
    fun setFilter(f: TripFilter) { _filter.value = f }

    fun toggleSelection(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun classify(ids: List<Long>, purposeId: Long?) = viewModelScope.launch {
        val before = ids.mapNotNull { id -> repo.trips.byId(id)?.let { it.id to it.purposeId } }
        repo.trips.classify(ids, purposeId)
        _undo.value = before
        _selection.value = emptySet()
    }

    fun undoLast() = viewModelScope.launch {
        _undo.value.forEach { (id, purposeId) -> repo.trips.classify(listOf(id), purposeId) }
        _undo.value = emptyList()
    }

    fun clearUndo() { _undo.value = emptyList() }

    fun delete(ids: List<Long>) = viewModelScope.launch {
        repo.trips.deleteIds(ids)
        _selection.value = emptySet()
    }

    fun addTags(ids: List<Long>, tags: String) = viewModelScope.launch {
        repo.trips.setTags(ids, tags)
        _selection.value = emptySet()
    }

    /** Rolls several trips into one, start of the first through end of the last. */
    fun merge(ids: List<Long>) = viewModelScope.launch {
        if (ids.size < 2) return@launch
        val trips = ids.mapNotNull { repo.trips.byId(it) }.sortedBy { it.startEpoch }
        if (trips.size < 2) return@launch
        val first = trips.first()
        val last = trips.last()
        repo.trips.update(
            first.copy(
                endEpoch = last.endEpoch,
                miles = trips.sumOf { it.miles },
                endAddress = last.endAddress,
                endLat = last.endLat,
                endLon = last.endLon,
                pathCsv = trips.joinToString(";") { it.pathCsv }.trim(';'),
                notes = trips.mapNotNull { it.notes.ifBlank { null } }.joinToString(" · "),
                updatedAt = System.currentTimeMillis()
            )
        )
        repo.trips.deleteIds(trips.drop(1).map { it.id })
        _selection.value = emptySet()
    }
}

data class TxnFilter(
    val type: TxnType? = null,
    val purposeId: Long? = null,
    val period: PeriodChoice = PeriodChoice(Period.THIS_YEAR)
)

@OptIn(ExperimentalCoroutinesApi::class)
class TxnVm(app: Application) : BaseVm(app) {

    private val _filter = MutableStateFlow(TxnFilter())
    val filter: StateFlow<TxnFilter> = _filter

    val rows: StateFlow<List<TxnRow>> = _filter.flatMapLatest { f ->
        val range = f.period.range
        repo.txns.rowsBetween(range.fromDay, range.toDay).map { list ->
            list.filter { row ->
                (f.type == null || row.type == f.type) &&
                    (f.purposeId == null || row.purposeId == f.purposeId)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totals: StateFlow<Triple<Long, Long, Long>> = rows.map { list ->
        val rev = list.filter { it.type == TxnType.REVENUE }.sumOf { it.amountCents }
        val exp = list.filter { it.type == TxnType.EXPENSE }.sumOf { it.amountCents }
        Triple(rev, exp, rev - exp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0L, 0L, 0L))

    fun setFilter(f: TxnFilter) { _filter.value = f }

    fun classify(id: Long, purposeId: Long?) = viewModelScope.launch {
        repo.txns.classify(listOf(id), purposeId)
    }

    fun delete(id: Long) = viewModelScope.launch {
        repo.txns.byId(id)?.let { repo.txns.delete(it) }
    }

    /** The purpose used when you swipe a transaction to the work side. */
    suspend fun firstBusinessPurposeId(): Long? =
        repo.purposes.allNow().firstOrNull { it.deductionClass == DeductionClass.BUSINESS }?.id

    suspend fun personalPurposeId(): Long? =
        repo.purposes.allNow().firstOrNull { it.name == "Personal" }?.id
}

class TaxesVm(app: Application) : BaseVm(app) {

    private val _year = MutableStateFlow(LocalDate.now().year)
    val year: StateFlow<Int> = _year

    private val _years = MutableStateFlow(listOf(LocalDate.now().year))
    val years: StateFlow<List<Int>> = _years

    private val _summary = MutableStateFlow(TaxSummary(DayRange.forYear(LocalDate.now().year)))
    val summary: StateFlow<TaxSummary> = _summary

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    val rates: StateFlow<List<MileageRate>> = repo.rates.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _years.value = repo.yearsWithData()
            reload()
        }
        // Recompute when trips or transactions change.
        viewModelScope.launch {
            repo.trips.recentRows(1).collect { reload() }
        }
    }

    fun setYear(y: Int) {
        _year.value = y
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _summary.value = repo.summarize(DayRange.forYear(_year.value))
    }

    fun setBusy(v: Boolean) { _busy.value = v }
}

class SettingsVm(app: Application) : BaseVm(app) {

    val prefs = repo.prefs

    val rates: StateFlow<List<MileageRate>> = repo.rates.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val windows: StateFlow<List<WorkWindow>> = repo.schedule.windows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ServiceReminder>> = repo.schedule.reminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expenseCategories: StateFlow<List<Category>> =
        repo.categories.ofKind(CategoryKind.EXPENSE.name)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveWindow(w: WorkWindow) = viewModelScope.launch {
        if (w.id == 0L) repo.schedule.insertWindow(w) else repo.schedule.updateWindow(w)
    }

    fun deleteWindow(w: WorkWindow) = viewModelScope.launch { repo.schedule.deleteWindow(w) }

    fun saveShift(s: Shift) = viewModelScope.launch {
        if (s.id == 0L) repo.schedule.insertShift(s) else repo.schedule.updateShift(s)
    }

    fun saveReminder(r: ServiceReminder) = viewModelScope.launch {
        if (r.id == 0L) repo.schedule.insertReminder(r) else repo.schedule.updateReminder(r)
    }

    fun deleteReminder(r: ServiceReminder) = viewModelScope.launch { repo.schedule.deleteReminder(r) }

    fun saveRate(r: MileageRate) = viewModelScope.launch { repo.rates.update(r) }

    fun savePurpose(p: Purpose) = viewModelScope.launch {
        if (p.id == 0L) repo.purposes.insert(p) else repo.purposes.update(p)
    }

    fun deletePurpose(p: Purpose) = viewModelScope.launch { repo.purposes.delete(p) }

    fun saveCategory(c: Category) = viewModelScope.launch {
        if (c.id == 0L) repo.categories.insert(c) else repo.categories.update(c)
    }

    fun deleteCategory(c: Category) = viewModelScope.launch { repo.categories.delete(c) }

    fun saveVehicle(v: Vehicle) = viewModelScope.launch {
        if (v.isDefault) repo.vehicles.clearDefault()
        if (v.id == 0L) repo.vehicles.insert(v) else repo.vehicles.update(v)
    }

    fun deleteVehicle(v: Vehicle) = viewModelScope.launch { repo.vehicles.delete(v) }
}

/** Backs both the Add trip and the Edit trip screens. */
class EditTripVm(app: Application) : BaseVm(app) {

    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip

    fun load(id: Long) = viewModelScope.launch {
        // Drop the previous record first. Without this the screen renders the last trip
        // you opened until the new one arrives, and the form seeds itself from it.
        _trip.value = null
        _trip.value = if (id > 0) repo.trips.byId(id) else Trip(
            startEpoch = System.currentTimeMillis(),
            endEpoch = System.currentTimeMillis(),
            miles = 0.0,
            vehicleId = repo.defaultVehicleId(),
            source = TripSource.MANUAL
        )
    }

    fun edit(block: (Trip) -> Trip) {
        _trip.value = _trip.value?.let(block)
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val t = _trip.value ?: return@launch
        // Odometer readings win when both are filled in.
        val miles = if (t.startOdometer != null && t.endOdometer != null && t.endOdometer > t.startOdometer) {
            t.endOdometer - t.startOdometer
        } else t.miles
        val fixed = t.copy(miles = miles, updatedAt = System.currentTimeMillis())
        if (fixed.id == 0L) repo.trips.insert(fixed) else repo.trips.update(fixed)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _trip.value?.takeIf { it.id != 0L }?.let { repo.trips.delete(it) }
        onDone()
    }
}

/** Backs Add expense, Add revenue and their edit screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTxnVm(app: Application) : BaseVm(app) {

    private val _txn = MutableStateFlow<Txn?>(null)
    val txn: StateFlow<Txn?> = _txn

    private val _kind = MutableStateFlow(CategoryKind.EXPENSE)

    /** Follows whichever kind the open transaction is, without stacking up collectors. */
    val categories: StateFlow<List<Category>> = _kind
        .flatMapLatest { kind -> repo.categories.ofKind(kind.name) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(id: Long, type: TxnType) = viewModelScope.launch {
        _txn.value = null
        val loaded = if (id > 0) repo.txns.byId(id) else Txn(
            type = type,
            amountCents = 0,
            dateEpochDay = LocalDate.now().toEpochDay(),
            vehicleId = repo.defaultVehicleId()
        )
        _txn.value = loaded
        _kind.value =
            if ((loaded?.type ?: type) == TxnType.REVENUE) CategoryKind.REVENUE else CategoryKind.EXPENSE
    }

    fun edit(block: (Txn) -> Txn) { _txn.value = _txn.value?.let(block) }

    /** Merchants he has typed before, most used first. */
    val merchantHistory: StateFlow<List<MerchantUse>> = repo.txns.merchantHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Takes a merchant from the suggestion list and fills in the rest of the form the
     * way it was filled in last time, or the way that merchant is normally filed.
     */
    fun pickMerchant(name: String) = viewModelScope.launch {
        val current = _txn.value ?: return@launch
        val prior = repo.txns.lastFiledAs(name)
        val seeded = if (current.type == TxnType.EXPENSE) {
            Merchants.defaultCategoryFor(name)?.let { wanted ->
                repo.categories.allNow().firstOrNull { it.name.equals(wanted, true) }?.id
            }
        } else null

        _txn.value = current.copy(
            merchant = name,
            categoryId = current.categoryId ?: prior?.categoryId ?: seeded,
            purposeId = current.purposeId ?: prior?.purposeId
        )
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val x = _txn.value ?: return@launch
        if (x.id == 0L) repo.txns.insert(x) else repo.txns.update(x)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _txn.value?.takeIf { it.id != 0L }?.let { repo.txns.delete(it) }
        onDone()
    }
}
