package com.psdk.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.psdk.db.entities.Project
import java.util.UUID

@Dao
interface ProjectDao {
    @Query("SELECT * FROM project")
    fun getAll(): List<Project>

    @Query("SELECT * FROM project WHERE id = :projectId")
    fun findById(projectId: UUID): Project

    @Insert
    fun insertAll(vararg projects: Project)

    @Delete
    fun delete(project: Project)
}
