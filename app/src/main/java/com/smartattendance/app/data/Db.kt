package com.smartattendance.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local SQLite storage: students (with face embeddings) and attendance records. */
class Db(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "attendance.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE students (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                roll TEXT NOT NULL UNIQUE COLLATE NOCASE,
                photo TEXT NOT NULL,
                engine TEXT NOT NULL,
                embedding BLOB NOT NULL,
                created INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE attendance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                student_id INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,
                day TEXT NOT NULL,
                time TEXT NOT NULL,
                UNIQUE(student_id, day)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only.
    }

    // ---------------------------------------------------------------- students

    /** Returns the new student id, or -1 if the roll number is already registered. */
    fun addStudent(
        name: String,
        roll: String,
        photoPath: String,
        engine: String,
        embedding: FloatArray
    ): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("roll", roll)
            put("photo", photoPath)
            put("engine", engine)
            put("embedding", toBytes(embedding))
            put("created", System.currentTimeMillis())
        }
        return try {
            writableDatabase.insertOrThrow("students", null, cv)
        } catch (e: SQLiteConstraintException) {
            -1L
        }
    }

    /** All students including embeddings (used by the live recognizer). */
    fun students(): List<Student> {
        val out = ArrayList<Student>()
        readableDatabase.rawQuery(
            "SELECT id, name, roll, photo, engine, embedding FROM students ORDER BY roll COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Student(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), toFloats(c.getBlob(5))
                    )
                )
            }
        }
        return out
    }

    fun studentCount(): Int = scalar("SELECT COUNT(*) FROM students", null)

    fun updateEmbedding(id: Long, engine: String, embedding: FloatArray) {
        val cv = ContentValues().apply {
            put("engine", engine)
            put("embedding", toBytes(embedding))
        }
        writableDatabase.update("students", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteStudent(id: Long) {
        writableDatabase.delete("students", "id = ?", arrayOf(id.toString()))
    }

    // -------------------------------------------------------------- attendance

    /** True if newly marked, false if the student was already marked for that day. */
    fun markPresent(studentId: Long, day: String, time: String): Boolean {
        val cv = ContentValues().apply {
            put("student_id", studentId)
            put("day", day)
            put("time", time)
        }
        return writableDatabase.insertWithOnConflict(
            "attendance", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /** student id -> time marked, for the given day. */
    fun presentToday(day: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        readableDatabase.rawQuery(
            "SELECT student_id, time FROM attendance WHERE day = ?", arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
        }
        return out
    }

    /** Every student with their status for the given day (no embeddings loaded). */
    fun rows(day: String): List<DayRow> {
        val out = ArrayList<DayRow>()
        readableDatabase.rawQuery(
            """SELECT s.id, s.name, s.roll, s.photo, a.time
               FROM students s
               LEFT JOIN attendance a ON a.student_id = s.id AND a.day = ?
               ORDER BY s.roll COLLATE NOCASE""",
            arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DayRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        if (c.isNull(4)) null else c.getString(4)
                    )
                )
            }
        }
        return out
    }

    fun overall(): OverallStats = OverallStats(
        students = studentCount(),
        sessions = scalar("SELECT COUNT(DISTINCT day) FROM attendance", null),
        presentRecords = scalar("SELECT COUNT(*) FROM attendance", null)
    )

    private fun scalar(sql: String, args: Array<String>?): Int =
        readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        fun toBytes(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in v) bb.putFloat(f)
            return bb.array()
        }

        fun toFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }
    }
}
