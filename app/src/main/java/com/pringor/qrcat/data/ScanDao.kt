package com.pringor.qrcat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Transaction
    @Query("SELECT * FROM scans")
    fun getAllScansWithOccurrences(): Flow<List<ScanWithOccurrences>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScan(scan: ScanEntity)

    @Insert
    suspend fun insertOccurrence(occurrence: ScanOccurrenceEntity)

    @Delete
    suspend fun deleteScan(scan: ScanEntity)

    @Query("DELETE FROM scans")
    suspend fun deleteAllScans()
}
