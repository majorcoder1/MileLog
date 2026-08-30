package com.milelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** How a purpose is treated at tax time. */
enum class DeductionClass { BUSINESS, MEDICAL, CHARITY, MOVING, PERSONAL }

/** Where a trip's miles came from. */
enum class TripSource { GPS, MANUAL, ODOMETER }

enum class TxnType { EXPENSE, REVENUE }

enum class CategoryKind { EXPENSE, REVENUE, BOTH }

@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val year: String = "",
    val makeModel: String = "",
    val odometer: Double = 0.0,
    val isDefault: Boolean = false,
    val archived: Boolean = false
)

@Entity(tableName = "purposes")
data class Purpose(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val deductionClass: DeductionClass,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val colorHex: String = "#3B82F6",
    val archived: Boolean = false
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val archived: Boolean = false
)

@Entity(
    tableName = "trips",
    indices = [Index("startEpoch"), Index("purposeId"), Index("vehicleId")]
)
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpoch: Long,
    val endEpoch: Long,
    val miles: Double,
    val purposeId: Long? = null,
    val vehicleId: Long? = null,
    val startAddress: String = "",
    val endAddress: String = "",
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val notes: String = "",
    val tags: String = "",
    val photoPath: String? = null,
    val source: TripSource = TripSource.GPS,
    val startOdometer: Double? = null,
    val endOdometer: Double? = null,
    /** Simple "lat,lon;lat,lon" list of the recorded route. */
    val pathCsv: String = "",
    val autoDetected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "txns",
    indices = [Index("dateEpochDay"), Index("purposeId"), Index("categoryId")]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxnType,
    val amountCents: Long,
    val dateEpochDay: Long,
    val purposeId: Long? = null,
    val categoryId: Long? = null,
    val merchant: String = "",
    val notes: String = "",
    val tags: String = "",
    val receiptPath: String? = null,
    val vehicleId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorite_places")
data class FavoritePlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String = "",
    val lat: Double,
    val lon: Double,
    val radiusMeters: Int = 150
)

/**
 * IRS cents-per-mile, valid over a date range. 2026 has two rows because the
 * business rate changed on July 1st.
 */
@Entity(tableName = "mileage_rates", indices = [Index("fromEpochDay")])
data class MileageRate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val fromEpochDay: Long,
    val toEpochDay: Long,
    val businessCents: Double,
    val medicalCents: Double,
    val charityCents: Double,
    val movingCents: Double
)

/** One recurring block of work hours. dayOfWeek follows java.time (1 = Monday). */
@Entity(tableName = "work_windows")
data class WorkWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val purposeId: Long? = null,
    val enabled: Boolean = true
)

/** A one-off planned shift on the calendar. */
@Entity(tableName = "shifts")
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpoch: Long,
    val endEpoch: Long,
    val purposeId: Long? = null,
    val notes: String = "",
    val remindMinutesBefore: Int = 15,
    val autoTrack: Boolean = true
)

@Entity(tableName = "service_reminders")
data class ServiceReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long? = null,
    val title: String,
    val intervalMiles: Double? = null,
    val intervalDays: Int? = null,
    val lastDoneOdometer: Double? = null,
    val lastDoneEpochDay: Long? = null,
    val enabled: Boolean = true
)

class Converters {
    @TypeConverter fun deductionToString(v: DeductionClass): String = v.name
    @TypeConverter fun stringToDeduction(v: String): DeductionClass = DeductionClass.valueOf(v)
    @TypeConverter fun sourceToString(v: TripSource): String = v.name
    @TypeConverter fun stringToSource(v: String): TripSource = TripSource.valueOf(v)
    @TypeConverter fun txnTypeToString(v: TxnType): String = v.name
    @TypeConverter fun stringToTxnType(v: String): TxnType = TxnType.valueOf(v)
    @TypeConverter fun kindToString(v: CategoryKind): String = v.name
    @TypeConverter fun stringToKind(v: String): CategoryKind = CategoryKind.valueOf(v)
}

/** A trip joined with the names it displays. */
data class TripRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "startEpoch") val startEpoch: Long,
    @ColumnInfo(name = "endEpoch") val endEpoch: Long,
    @ColumnInfo(name = "miles") val miles: Double,
    @ColumnInfo(name = "purposeId") val purposeId: Long?,
    @ColumnInfo(name = "purposeName") val purposeName: String?,
    @ColumnInfo(name = "deductionClass") val deductionClass: DeductionClass?,
    @ColumnInfo(name = "vehicleName") val vehicleName: String?,
    @ColumnInfo(name = "startAddress") val startAddress: String,
    @ColumnInfo(name = "endAddress") val endAddress: String,
    @ColumnInfo(name = "notes") val notes: String,
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "pathCsv") val pathCsv: String,
    @ColumnInfo(name = "source") val source: TripSource
)

/** A transaction joined with the names it displays. */
data class TxnRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "type") val type: TxnType,
    @ColumnInfo(name = "amountCents") val amountCents: Long,
    @ColumnInfo(name = "dateEpochDay") val dateEpochDay: Long,
    @ColumnInfo(name = "purposeId") val purposeId: Long?,
    @ColumnInfo(name = "purposeName") val purposeName: String?,
    @ColumnInfo(name = "categoryName") val categoryName: String?,
    @ColumnInfo(name = "merchant") val merchant: String,
    @ColumnInfo(name = "notes") val notes: String,
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "receiptPath") val receiptPath: String?
)

data class CategoryTotal(val name: String, val cents: Long)
data class MerchantUse(val name: String, val uses: Int, val lastUsed: Long)
data class MerchantDefaults(val categoryId: Long?, val purposeId: Long?)
data class PurposeMiles(val purposeId: Long?, val name: String?, val deductionClass: DeductionClass?, val miles: Double)
