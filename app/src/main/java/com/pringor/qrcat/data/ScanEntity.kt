package com.pringor.qrcat.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val content: String,
    val type: String,
    val title: String? = null
)

@Entity(
    tableName = "scan_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = ScanEntity::class,
            parentColumns = ["content"],
            childColumns = ["scanContent"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ScanOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanContent: String,
    val timestamp: Long
)

data class ScanWithOccurrences(
    @Embedded val scan: ScanEntity,
    @Relation(
        parentColumn = "content",
        entityColumn = "scanContent"
    )
    val occurrences: List<ScanOccurrenceEntity>
)