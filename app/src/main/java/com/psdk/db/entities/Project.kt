package com.psdk.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity
data class Project(
    val name: String,
    @PrimaryKey val id: UUID)