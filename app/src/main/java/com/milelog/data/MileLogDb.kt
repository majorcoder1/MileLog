package com.milelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import java.time.LocalDate

@Database(
    entities = [
        Trip::class, Txn::class, Purpose::class, Category::class, Vehicle::class,
        FavoritePlace::class, MileageRate::class, WorkWindow::class, Shift::class,
        ServiceReminder::class
    ],
    version = 1,
    // Exported so a future version can be migrated onto rather than dropped.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MileLogDb : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun txns(): TxnDao
    abstract fun purposes(): PurposeDao
    abstract fun categories(): CategoryDao
    abstract fun vehicles(): VehicleDao
    abstract fun places(): PlaceDao
    abstract fun rates(): RateDao
    abstract fun schedule(): ScheduleDao

    companion object {
        @Volatile private var instance: MileLogDb? = null

        fun get(context: Context): MileLogDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, MileLogDb::class.java, "milelog.db"
            ).build().also { instance = it }
        }

        /** Close and drop the cached handle, so a restore can swap the file underneath. */
        fun reset() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

/** First-run data: the purposes, categories and IRS rates the app ships with. */
object Seed {

    suspend fun runIfNeeded(db: MileLogDb) {
        if (db.purposes().count() == 0) seedPurposes(db)
        if (db.categories().count() == 0) seedCategories(db)
        if (db.rates().count() == 0) seedRates(db)
        if (db.vehicles().count() == 0) {
            db.vehicles().insert(Vehicle(name = "My car", isDefault = true))
        }
    }

    private suspend fun seedPurposes(db: MileLogDb) {
        val rows = listOf(
            Triple("DoorDash", DeductionClass.BUSINESS, "#EF4444"),
            Triple("Uber", DeductionClass.BUSINESS, "#E5E7EB"),
            Triple("Instacart", DeductionClass.BUSINESS, "#F97316"),
            Triple("Spark", DeductionClass.BUSINESS, "#38BDF8"),
            Triple("Work", DeductionClass.BUSINESS, "#3B82F6"),
            Triple("Personal", DeductionClass.PERSONAL, "#94A3B8"),
            Triple("Medical", DeductionClass.MEDICAL, "#F472B6"),
            Triple("Charity", DeductionClass.CHARITY, "#A78BFA"),
            Triple("Moving", DeductionClass.MOVING, "#FBBF24"),
            Triple("Commute", DeductionClass.PERSONAL, "#64748B"),
            Triple("Other", DeductionClass.PERSONAL, "#78716C")
        )
        rows.forEachIndexed { i, (name, cls, color) ->
            db.purposes().insert(
                Purpose(name = name, deductionClass = cls, isBuiltIn = true, sortOrder = i, colorHex = color)
            )
        }
    }

    private suspend fun seedCategories(db: MileLogDb) {
        val expense = listOf(
            "Gas", "Oil change", "Restaurants & meals", "Repairs", "Tires", "Car wash",
            "Registration & tags", "Tolls", "Parking", "Lodging", "Insurance", "Phone",
            "Supplies", "Licenses & fees", "Other"
        )
        expense.forEachIndexed { i, name ->
            db.categories().insert(
                Category(name = name, kind = CategoryKind.EXPENSE, isBuiltIn = true, sortOrder = i)
            )
        }
        val revenue = listOf("Delivery pay", "Tips", "Bonus / promotion", "Reimbursement", "Other income")
        revenue.forEachIndexed { i, name ->
            db.categories().insert(
                Category(name = name, kind = CategoryKind.REVENUE, isBuiltIn = true, sortOrder = 100 + i)
            )
        }
    }

    /**
     * IRS standard mileage rates. 2026 is split because the business rate went from
     * 72.5 to 76 cents on July 1st.
     */
    private suspend fun seedRates(db: MileLogDb) {
        suspend fun add(label: String, from: LocalDate, to: LocalDate, biz: Double, med: Double, chr: Double, mov: Double) {
            db.rates().insert(
                MileageRate(
                    label = label,
                    fromEpochDay = from.toEpochDay(),
                    toEpochDay = to.toEpochDay(),
                    businessCents = biz, medicalCents = med, charityCents = chr, movingCents = mov
                )
            )
        }
        add("2024", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 67.0, 21.0, 14.0, 21.0)
        add("2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 70.0, 21.0, 14.0, 21.0)
        add("2026 Jan–Jun", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 72.5, 20.5, 14.0, 20.5)
        add("2026 Jul–Dec", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 76.0, 23.5, 14.0, 23.5)
    }
}
