package com.smartattendance.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartattendance.app.data.Db
import com.smartattendance.app.data.DayRow
import com.smartattendance.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dashboard: present / absent / attendance percentage, plus today's student list. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: Db
    private val adapter = StudentAdapter { row -> confirmDelete(row) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        b.btnStart.setOnClickListener {
            if (db.studentCount() == 0) {
                Toast.makeText(this, "Register at least one student first", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, AttendanceActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rows = db.rows(Db.today())
        val total = rows.size
        val present = rows.count { it.time != null }
        val absent = total - present
        val percent = if (total == 0) 0 else Math.round(present * 100f / total)

        b.tvDate.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
        b.tvPresent.text = present.toString()
        b.tvAbsent.text = absent.toString()
        b.tvPercent.text = "$percent%"

        val overall = db.overall()
        b.tvOverall.text = if (overall.sessions > 0) {
            "Overall: ${overall.percent}% across ${overall.sessions} attendance day(s)"
        } else {
            "Long-press a student to remove them"
        }

        adapter.submit(rows)
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(row: DayRow) {
        AlertDialog.Builder(this)
            .setTitle("Remove student?")
            .setMessage("${row.name} (${row.roll}) and all their attendance records will be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                db.deleteStudent(row.id)
                File(row.photoPath).delete()
                refresh()
            }
            .show()
    }
}
