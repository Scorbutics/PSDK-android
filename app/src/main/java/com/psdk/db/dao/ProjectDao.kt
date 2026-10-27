package com.psdk.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.psdk.db.entities.Project
import java.util.UUID

@Dao
interface ProjectDao {
    @Query("SELECT * FROM project where rootDirectoryId = :rootDirectoryId")
    fun getAllByDirectory(rootDirectoryId: String): List<Project>

    @Query("SELECT * FROM project WHERE id = :projectId")
    fun findById(projectId: UUID): Project

    @Query("SELECT * FROM project WHERE directory = :directory LIMIT 1")
    fun findByDirectory(directory: String): Project?

    @Insert
    fun insertAll(project: Project)

    @Delete
    fun delete(project: Project)
}
