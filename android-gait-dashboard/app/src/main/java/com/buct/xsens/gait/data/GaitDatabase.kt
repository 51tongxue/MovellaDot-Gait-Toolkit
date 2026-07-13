package com.buct.xsens.gait.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey
    @ColumnInfo(name = "athlete_id")
    val athleteId: String,
    @ColumnInfo(name = "athlete_code")
    val athleteCode: String,
    val name: String,
    val gender: String,
    @ColumnInfo(name = "birth_date")
    val birthDate: String,
    @ColumnInfo(name = "height_cm")
    val heightCm: Double,
    @ColumnInfo(name = "weight_kg")
    val weightKg: Double,
    @ColumnInfo(name = "group_name")
    val groupName: String,
    val extra: String,
)

@Entity(tableName = "organizations")
data class OrganizationEntity(
    @PrimaryKey
    @ColumnInfo(name = "organization_id")
    val organizationId: String,
    val name: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "generated_at")
    val generatedAt: String,
)

@Entity(tableName = "analysis_records")
data class AnalysisRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "athlete_id")
    val athleteId: String,
    @ColumnInfo(name = "attempt_no")
    val attemptNo: String,
    @ColumnInfo(name = "athlete_attempt_no")
    val athleteAttemptNo: Int,
    @ColumnInfo(name = "source_file_path")
    val sourceFilePath: String,
    @ColumnInfo(name = "manifest_path")
    val manifestPath: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "training_date")
    val trainingDate: String,
    @ColumnInfo(name = "analysis_mode")
    val analysisMode: String = "long_jump",
)

@Dao
interface GaitDataDao {
    @Query("SELECT * FROM athletes ORDER BY athlete_code ASC, name ASC")
    fun getAthletes(): List<AthleteEntity>

    @Query("SELECT * FROM athletes WHERE athlete_id = :athleteId LIMIT 1")
    fun getAthlete(athleteId: String): AthleteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAthletes(athletes: List<AthleteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertOrganization(organization: OrganizationEntity)

    @Query("SELECT * FROM organizations LIMIT 1")
    fun getOrganization(): OrganizationEntity?

    @Insert
    fun insertAnalysisRecord(record: AnalysisRecordEntity): Long

    @Query("SELECT * FROM analysis_records WHERE athlete_id = :athleteId ORDER BY training_date DESC, created_at DESC")
    fun getAnalysisRecords(athleteId: String): List<AnalysisRecordEntity>

    @Query("SELECT * FROM analysis_records ORDER BY created_at DESC")
    fun getAllAnalysisRecords(): List<AnalysisRecordEntity>

    @Query("DELETE FROM analysis_records WHERE id = :recordId")
    fun deleteAnalysisRecord(recordId: Long)
}

@Database(
    entities = [
        AthleteEntity::class,
        OrganizationEntity::class,
        AnalysisRecordEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class GaitDatabase : RoomDatabase() {
    abstract fun gaitDataDao(): GaitDataDao

    companion object {
        @Volatile
        private var instance: GaitDatabase? = null

        fun getInstance(context: Context): GaitDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GaitDatabase::class.java,
                    "longjump_gait.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE analysis_records ADD COLUMN analysis_mode TEXT NOT NULL DEFAULT 'long_jump'"
                )
            }
        }
    }
}
