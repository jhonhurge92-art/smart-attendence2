package com.smartattendance.app

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.util.LruCache
import com.smartattendance.app.data.DayRow
import com.smartattendance.app.data.ImageUtil
import com.smartattendance.app.databinding.ItemStudentBinding

class StudentAdapter(
    private val onLongPress: (DayRow) -> Unit
) : RecyclerView.Adapter<StudentAdapter.VH>() {

    class VH(val b: ItemStudentBinding) : RecyclerView.ViewHolder(b.root)

    private var rows: List<DayRow> = emptyList()
    private val thumbs = LruCache<String, Bitmap>(48)

    fun submit(newRows: List<DayRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val b = holder.b
        val ctx = b.root.context

        b.tvName.text = row.name
        b.tvRoll.text = "Roll no. ${row.roll}"

        var bmp = thumbs.get(row.photoPath)
        if (bmp == null) {
            bmp = ImageUtil.thumbnail(row.photoPath, 160)
            if (bmp != null) thumbs.put(row.photoPath, bmp)
        }
        b.ivPhoto.setImageBitmap(bmp)

        if (row.time != null) {
            b.tvStatus.text = "Present · ${row.time.substring(0, 5)}"
            b.tvStatus.setBackgroundResource(R.drawable.bg_chip_present)
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.white))
        } else {
            b.tvStatus.text = "Absent"
            b.tvStatus.setBackgroundResource(R.drawable.bg_chip_absent)
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.blue_700))
        }

        b.root.setOnLongClickListener {
            onLongPress(row)
            true
        }
    }
}
