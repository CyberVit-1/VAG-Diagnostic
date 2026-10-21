package com.vagdiagnostic.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vagdiagnostic.app.core.ControlUnit
import com.vagdiagnostic.app.core.Dtc
import com.vagdiagnostic.app.core.DtcKind
import com.vagdiagnostic.app.core.ReadState
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "scans")
data class ScanEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val started: Long, val finished: Long, val interrupted: Boolean, val completedModes: Int, val json: String)

@Entity(tableName = "journal")
data class JournalEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val timestamp: Long, val connection: String, val command: String, val raw: String, val elapsedMs: Long, val outcome: String)

@Dao interface ScanDao {
    @Insert suspend fun insert(scan: ScanEntity): Long
    @Update suspend fun update(scan: ScanEntity)
    @Query("SELECT * FROM scans ORDER BY started DESC") fun observe(): Flow<List<ScanEntity>>
}
@Dao interface JournalDao {
    @Insert suspend fun insert(entry: JournalEntity)
    @Query("SELECT * FROM journal ORDER BY id DESC LIMIT :limit") suspend fun latest(limit: Int = 10000): List<JournalEntity>
}

@Database(entities = [ScanEntity::class, JournalEntity::class], version = 1, exportSchema = false)
abstract class DiagnosticDatabase : RoomDatabase() {
    abstract fun scans(): ScanDao
    abstract fun journal(): JournalDao
    companion object {
        @Volatile private var instance: DiagnosticDatabase? = null
        fun get(context: Context): DiagnosticDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DiagnosticDatabase::class.java, "vag_diagnostic.db")
                // Deliberately no destructive fallback. Logs survive app upgrades.
                .build().also { instance = it }
        }
    }
}

object ScanCodec {
    fun encode(units: List<ControlUnit>): String = JSONArray().apply {
        units.forEach { unit -> put(JSONObject().apply {
            put("address", unit.address); put("name", unit.name); put("state", unit.state.name); put("note", unit.note)
            put("answered", JSONArray(unit.answeredModes.map { it.name }))
            put("dtcs", JSONArray().apply { unit.dtcs.forEach { fault -> put(JSONObject().apply {
                put("raw", fault.raw); put("code", fault.code); put("kind", fault.kind.name)
                put("description", fault.description ?: JSONObject.NULL)
            }) } })
        }) }
    }.toString()
    fun decode(data: String): List<ControlUnit> {
        val array = JSONArray(data)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val answered = obj.optJSONArray("answered") ?: JSONArray()
            val faults = obj.optJSONArray("dtcs") ?: JSONArray()
            ControlUnit(
                address = obj.getString("address"), name = obj.getString("name"),
                state = ReadState.valueOf(obj.getString("state")), note = obj.optString("note"),
                answeredModes = (0 until answered.length()).map { DtcKind.valueOf(answered.getString(it)) }.toSet(),
                dtcs = (0 until faults.length()).map { j ->
                    val f = faults.getJSONObject(j)
                    Dtc(f.getString("raw"), f.getString("code"), DtcKind.valueOf(f.getString("kind")),
                        f.optString("description").takeUnless { it == "null" || it.isBlank() })
                }
            )
        }
    }
}