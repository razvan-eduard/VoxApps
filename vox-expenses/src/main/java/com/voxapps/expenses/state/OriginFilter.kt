package com.voxapps.expenses.state

import androidx.compose.runtime.Immutable
import com.voxapps.expenses.data.Expense

/**
 * The provenance narrowing: which device's records the list — and every total computed from the
 * same rows — shows. [deviceName] null means records made on THIS device (no origin stamp, see
 * [Expense.originDeviceId]); a name means records that arrived via device sync from the device of
 * that name. No [OriginFilter] at all (a null selection) means everything, whatever its origin.
 */
@Immutable
data class OriginFilter(val deviceName: String?) {

    fun matches(expense: Expense): Boolean =
        if (deviceName == null) {
            expense.originDeviceId == null
        } else {
            expense.originDeviceName?.equals(deviceName, ignoreCase = true) == true
        }
}
