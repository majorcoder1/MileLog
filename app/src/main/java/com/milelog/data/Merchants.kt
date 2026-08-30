package com.milelog.data

/**
 * Names that show up the first time you type, before there is any history to learn
 * from. Each one carries the category it almost always belongs to, so picking the
 * name fills the rest of the form in.
 */
object Merchants {

    data class Known(val name: String, val category: String)

    val seeds: List<Known> = listOf(
        // Fuel
        Known("Murphy USA", "Gas"),
        Known("Pilot", "Gas"),
        Known("Flying J", "Gas"),
        Known("Love's Travel Stop", "Gas"),
        Known("Circle K", "Gas"),
        Known("Shell", "Gas"),
        Known("Exxon", "Gas"),
        Known("BP", "Gas"),
        Known("Marathon", "Gas"),
        Known("Speedway", "Gas"),
        Known("Weigel's", "Gas"),
        Known("Twice Daily", "Gas"),
        Known("Casey's", "Gas"),
        Known("Sam's Club", "Gas"),
        Known("Kroger Fuel", "Gas"),

        // Oil, parts, tires
        Known("Jiffy Lube", "Oil change"),
        Known("Valvoline Instant Oil Change", "Oil change"),
        Known("Take 5 Oil Change", "Oil change"),
        Known("AutoZone", "Repairs"),
        Known("O'Reilly Auto Parts", "Repairs"),
        Known("Advance Auto Parts", "Repairs"),
        Known("NAPA Auto Parts", "Repairs"),
        Known("Firestone", "Tires"),
        Known("Discount Tire", "Tires"),
        Known("Goodyear", "Tires"),
        Known("Zips Car Wash", "Car wash"),

        // Food
        Known("McDonald's", "Restaurants & meals"),
        Known("Hardee's", "Restaurants & meals"),
        Known("Wendy's", "Restaurants & meals"),
        Known("Taco Bell", "Restaurants & meals"),
        Known("Chick-fil-A", "Restaurants & meals"),
        Known("Subway", "Restaurants & meals"),
        Known("Cracker Barrel", "Restaurants & meals"),
        Known("Waffle House", "Restaurants & meals"),
        Known("Sonic", "Restaurants & meals"),
        Known("Arby's", "Restaurants & meals"),
        Known("KFC", "Restaurants & meals"),
        Known("Zaxby's", "Restaurants & meals"),
        Known("Dairy Queen", "Restaurants & meals"),
        Known("Starbucks", "Restaurants & meals"),
        Known("Dunkin'", "Restaurants & meals"),
        Known("Huddle House", "Restaurants & meals"),

        // Supplies and overhead
        Known("Walmart", "Supplies"),
        Known("Dollar General", "Supplies"),
        Known("Dollar Tree", "Supplies"),
        Known("Lowe's", "Supplies"),
        Known("Verizon", "Phone"),
        Known("AT&T", "Phone"),
        Known("T-Mobile", "Phone"),
        Known("Progressive", "Insurance"),
        Known("State Farm", "Insurance"),
        Known("Geico", "Insurance"),
        Known("Allstate", "Insurance"),
        Known("County Clerk", "Registration & tags")
    )

    private val byName = seeds.associateBy { it.name.lowercase() }

    fun defaultCategoryFor(name: String): String? = byName[name.trim().lowercase()]?.category

    /**
     * Names matching what has been typed so far. What he has used before comes first,
     * then the built-in list. A match on the start of the name outranks one in the middle.
     */
    fun suggest(typed: String, history: List<MerchantUse>, limit: Int = 6): List<String> {
        val q = typed.trim()
        if (q.isEmpty()) return history.take(limit).map { it.name }

        fun rank(name: String): Int = when {
            name.equals(q, true) -> 0
            name.startsWith(q, true) -> 1
            name.split(' ', '-').any { it.startsWith(q, true) } -> 2
            name.contains(q, true) -> 3
            else -> Int.MAX_VALUE
        }

        val used = history
            .map { it.name }
            .filter { rank(it) != Int.MAX_VALUE }
            .sortedBy { rank(it) }

        val seeded = seeds
            .map { it.name }
            .filter { rank(it) != Int.MAX_VALUE }
            .filterNot { seed -> used.any { it.equals(seed, true) } }
            .sortedBy { rank(it) }

        return (used + seeded)
            .distinctBy { it.lowercase() }
            .filterNot { it.equals(q, true) }
            .take(limit)
    }
}
