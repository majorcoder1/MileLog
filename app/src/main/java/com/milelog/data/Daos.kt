package com.milelog.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val TRIP_JOIN = """
    SELECT t.id, t.startEpoch, t.endEpoch, t.miles, t.purposeId,
           p.name AS purposeName, p.deductionClass AS deductionClass,
           v.name AS vehicleName, t.startAddress, t.endAddress,
           t.notes, t.tags, t.pathCsv, t.source
    FROM trips t
    LEFT JOIN purposes p ON p.id = t.purposeId
    LEFT JOIN vehicles v ON v.id = t.vehicleId
"""

private const val TXN_JOIN = """
    SELECT x.id, x.type, x.amountCents, x.dateEpochDay, x.purposeId,
           p.name AS purposeName, c.name AS categoryName,
           x.merchant, x.notes, x.tags, x.receiptPath
    FROM txns x
    LEFT JOIN purposes p ON p.id = x.purposeId
    LEFT JOIN categories c ON c.id = x.categoryId
"""

@Dao
interface TripDao {
    @Insert suspend fun insert(trip: Trip): Long
    @Update suspend fun update(trip: Trip)
    @Delete suspend fun delete(trip: Trip)

    @Query("DELETE FROM trips WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): Trip?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Trip?>

    @Query("$TRIP_JOIN WHERE t.startEpoch BETWEEN :from AND :to ORDER BY t.startEpoch DESC")
    fun rowsBetween(from: Long, to: Long): Flow<List<TripRow>>

    @Query("$TRIP_JOIN ORDER BY t.startEpoch DESC LIMIT :limit")
    fun recentRows(limit: Int): Flow<List<TripRow>>

    @Query("$TRIP_JOIN WHERE t.purposeId IS NULL ORDER BY t.startEpoch DESC")
    fun unclassifiedRows(): Flow<List<TripRow>>

    @Query("SELECT COUNT(*) FROM trips WHERE purposeId IS NULL")
    fun unclassifiedCount(): Flow<Int>

    @Query("UPDATE trips SET purposeId = :purposeId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun classify(ids: List<Long>, purposeId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE trips SET tags = :tags, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setTags(ids: List<Long>, tags: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM trips WHERE startEpoch BETWEEN :from AND :to ORDER BY startEpoch ASC")
    suspend fun listBetween(from: Long, to: Long): List<Trip>

    @Query("SELECT * FROM trips ORDER BY startEpoch ASC")
    suspend fun all(): List<Trip>

    @Query(
        """SELECT t.purposeId AS purposeId, p.name AS name, p.deductionClass AS deductionClass,
                  SUM(t.miles) AS miles
           FROM trips t LEFT JOIN purposes p ON p.id = t.purposeId
           WHERE t.startEpoch BETWEEN :from AND :to
           GROUP BY t.purposeId"""
    )
    fun milesByPurpose(from: Long, to: Long): Flow<List<PurposeMiles>>

    @Query("SELECT COALESCE(SUM(miles), 0) FROM trips WHERE startEpoch BETWEEN :from AND :to")
    fun totalMiles(from: Long, to: Long): Flow<Double>

    @Query(
        """SELECT COUNT(*) FROM trips
           WHERE startEpoch BETWEEN :from AND :to AND ABS(miles - :miles) < 0.05"""
    )
    suspend fun countMatching(from: Long, to: Long, miles: Double): Int
}

@Dao
interface TxnDao {
    @Insert suspend fun insert(txn: Txn): Long
    @Update suspend fun update(txn: Txn)
    @Delete suspend fun delete(txn: Txn)

    @Query("DELETE FROM txns WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT * FROM txns WHERE id = :id")
    suspend fun byId(id: Long): Txn?

    @Query("$TXN_JOIN WHERE x.dateEpochDay BETWEEN :from AND :to ORDER BY x.dateEpochDay DESC, x.id DESC")
    fun rowsBetween(from: Long, to: Long): Flow<List<TxnRow>>

    @Query("SELECT * FROM txns WHERE dateEpochDay BETWEEN :from AND :to ORDER BY dateEpochDay ASC")
    suspend fun listBetween(from: Long, to: Long): List<Txn>

    @Query("SELECT * FROM txns ORDER BY dateEpochDay ASC")
    suspend fun all(): List<Txn>

    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM txns
           WHERE type = 'EXPENSE' AND dateEpochDay BETWEEN :from AND :to"""
    )
    fun expenseTotal(from: Long, to: Long): Flow<Long>

    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM txns
           WHERE type = 'REVENUE' AND dateEpochDay BETWEEN :from AND :to"""
    )
    fun revenueTotal(from: Long, to: Long): Flow<Long>

    @Query(
        """SELECT COALESCE(c.name, 'Uncategorized') AS name, SUM(x.amountCents) AS cents
           FROM txns x LEFT JOIN categories c ON c.id = x.categoryId
           WHERE x.type = :type AND x.dateEpochDay BETWEEN :from AND :to
           GROUP BY x.categoryId ORDER BY cents DESC"""
    )
    fun totalsByCategory(type: String, from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query("UPDATE txns SET purposeId = :purposeId WHERE id IN (:ids)")
    suspend fun classify(ids: List<Long>, purposeId: Long?)

    @Query(
        """SELECT merchant AS name, COUNT(*) AS uses, MAX(createdAt) AS lastUsed
           FROM txns WHERE merchant <> ''
           GROUP BY merchant COLLATE NOCASE
           ORDER BY uses DESC, lastUsed DESC"""
    )
    fun merchantHistory(): Flow<List<MerchantUse>>

    /** How this merchant was filed the last time, so the rest of the form fills itself. */
    @Query(
        """SELECT categoryId, purposeId FROM txns
           WHERE merchant = :name COLLATE NOCASE
           ORDER BY createdAt DESC LIMIT 1"""
    )
    suspend fun lastFiledAs(name: String): MerchantDefaults?

    @Query("SELECT COUNT(*) FROM txns WHERE dateEpochDay = :day AND merchant = :merchant AND amountCents = :cents")
    suspend fun countMatching(day: Long, merchant: String, cents: Long): Int
}

@Dao
interface PurposeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(p: Purpose): Long
    @Update suspend fun update(p: Purpose)
    @Delete suspend fun delete(p: Purpose)
    @Query("SELECT * FROM purposes WHERE archived = 0 ORDER BY sortOrder, name")
    fun all(): Flow<List<Purpose>>
    @Query("SELECT * FROM purposes ORDER BY sortOrder, name")
    suspend fun allNow(): List<Purpose>
    @Query("SELECT COUNT(*) FROM purposes") suspend fun count(): Int
    @Query("SELECT * FROM purposes WHERE id = :id") suspend fun byId(id: Long): Purpose?
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(c: Category): Long
    @Update suspend fun update(c: Category)
    @Delete suspend fun delete(c: Category)
    @Query("SELECT * FROM categories WHERE archived = 0 AND (kind = :kind OR kind = 'BOTH') ORDER BY sortOrder, name")
    fun ofKind(kind: String): Flow<List<Category>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") suspend fun allNow(): List<Category>
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
}

@Dao
interface VehicleDao {
    @Insert suspend fun insert(v: Vehicle): Long
    @Update suspend fun update(v: Vehicle)
    @Delete suspend fun delete(v: Vehicle)
    @Query("SELECT * FROM vehicles WHERE archived = 0 ORDER BY isDefault DESC, name")
    fun all(): Flow<List<Vehicle>>
    @Query("SELECT * FROM vehicles ORDER BY isDefault DESC, name") suspend fun allNow(): List<Vehicle>
    @Query("SELECT COUNT(*) FROM vehicles") suspend fun count(): Int
    @Query("UPDATE vehicles SET isDefault = 0") suspend fun clearDefault()
    @Query("SELECT * FROM vehicles WHERE isDefault = 1 LIMIT 1") suspend fun defaultVehicle(): Vehicle?
}

@Dao
interface PlaceDao {
    @Insert suspend fun insert(p: FavoritePlace): Long
    @Update suspend fun update(p: FavoritePlace)
    @Delete suspend fun delete(p: FavoritePlace)
    @Query("SELECT * FROM favorite_places ORDER BY name") fun all(): Flow<List<FavoritePlace>>
    @Query("SELECT * FROM favorite_places") suspend fun allNow(): List<FavoritePlace>
}

@Dao
interface RateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: MileageRate): Long
    @Update suspend fun update(r: MileageRate)
    @Delete suspend fun delete(r: MileageRate)
    @Query("SELECT * FROM mileage_rates ORDER BY fromEpochDay") fun all(): Flow<List<MileageRate>>
    @Query("SELECT * FROM mileage_rates ORDER BY fromEpochDay") suspend fun allNow(): List<MileageRate>
    @Query("SELECT COUNT(*) FROM mileage_rates") suspend fun count(): Int
    @Query("SELECT * FROM mileage_rates WHERE :day BETWEEN fromEpochDay AND toEpochDay LIMIT 1")
    suspend fun forDay(day: Long): MileageRate?
}

@Dao
interface ScheduleDao {
    @Insert suspend fun insertWindow(w: WorkWindow): Long
    @Update suspend fun updateWindow(w: WorkWindow)
    @Delete suspend fun deleteWindow(w: WorkWindow)
    @Query("SELECT * FROM work_windows ORDER BY dayOfWeek, startMinute") fun windows(): Flow<List<WorkWindow>>
    @Query("SELECT * FROM work_windows WHERE enabled = 1") suspend fun enabledWindows(): List<WorkWindow>

    @Insert suspend fun insertShift(s: Shift): Long
    @Update suspend fun updateShift(s: Shift)
    @Delete suspend fun deleteShift(s: Shift)
    @Query("SELECT * FROM shifts WHERE startEpoch BETWEEN :from AND :to ORDER BY startEpoch")
    fun shiftsBetween(from: Long, to: Long): Flow<List<Shift>>
    @Query("SELECT * FROM shifts WHERE endEpoch >= :now ORDER BY startEpoch") suspend fun upcomingShifts(now: Long): List<Shift>

    @Insert suspend fun insertReminder(r: ServiceReminder): Long
    @Update suspend fun updateReminder(r: ServiceReminder)
    @Delete suspend fun deleteReminder(r: ServiceReminder)
    @Query("SELECT * FROM service_reminders ORDER BY title") fun reminders(): Flow<List<ServiceReminder>>
    @Query("SELECT * FROM service_reminders WHERE enabled = 1") suspend fun enabledReminders(): List<ServiceReminder>
}
