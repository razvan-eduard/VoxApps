package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks how consistently the user has manually assigned a given vendor to a category, so future
 * captures for the same vendor can auto-apply the learned category once [consecutiveCount] meets
 * the user's configured threshold (see `ExpensesSettings.merchantCategoryMemoryThreshold`). One row
 * per vendor — [vendorKey] (see [MerchantVendorKey]) is the natural, cross-device-stable identity,
 * keyed directly as the primary key rather than an autoGenerate Long id.
 *
 * [consecutiveCount] resets to 1 whenever the user picks a DIFFERENT category than the one currently
 * on record for this vendor — only a genuinely consistent pattern should accumulate toward the
 * threshold, not an arbitrary correction history.
 */
@Entity(tableName = "merchant_category_memory")
data class MerchantCategoryMemory(
    @PrimaryKey val vendorKey: String,
    val categoryId: Long,
    val consecutiveCount: Int,
    val updatedAt: Long
)
