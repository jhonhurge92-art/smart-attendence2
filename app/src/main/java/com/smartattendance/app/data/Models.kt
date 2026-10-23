package com.smartattendance.app.data

/** A registered student together with the face embedding used for recognition. */
data class Student(
    val id: Long,
    val name: String,
    val roll: String,
    val photoPath: String,
    val engine: String,
    val embedding: FloatArray
)

/** One line of the dashboard: a student and, if present today, the time they were marked. */
data class DayRow(
    val id: Long,
    val name: String,
    val roll: String,
    val photoPath: String,
    val time: String?
)

/** Overall numbers across all attendance days. */
data class OverallStats(
    val students: Int,
    val sessions: Int,
    val presentRecords: Int
) {
    val percent: Int
        get() {
            val possible = students * sessions
            return if (possible <= 0) 0 else Math.round(presentRecords * 100f / possible)
        }
}
