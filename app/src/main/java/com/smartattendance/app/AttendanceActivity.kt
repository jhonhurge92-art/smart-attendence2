package com.smartattendance.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.smartattendance.app.data.Db
import com.smartattendance.app.data.ImageUtil
import com.smartattendance.app.data.Student
import com.smartattendance.app.databinding.ActivityAttendanceBinding
import com.smartattendance.app.face.FaceEngine
import com.smartattendance.app.face.FaceMatcher
import java.util.concurrent.Executors

/**
 * Live camera screen. Every few hundred milliseconds a frame is analysed; when the same registered
 * student is recognised in [CONFIRM_FRAMES] consecutive frames they are marked present. No button
 * press is needed.
 */
class AttendanceActivity : AppCompatActivity() {

    private lateinit var b: ActivityAttendanceBinding
    private lateinit var db: Db
    private lateinit var engine: FaceEngine

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var students: List<Student> = emptyList()
    @Volatile private var ready = false
    @Volatile private var threshold = 0.9f

    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var lastRun = 0L

    // Touched only on the analysis thread.
    private val streaks = HashMap<Long, Int>()
    private val marked = HashSet<Long>()

    // Touched only on the UI thread.
    private val recent = ArrayList<String>()

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) prepare()
            else {
                Toast.makeText(this, "Camera permission is required for automatic attendance", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAttendanceBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db(this)
        engine = FaceEngine(this)

        setupThresholdControl()
        b.btnDone.setOnClickListener { finish() }
        b.btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            bindCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) prepare() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------ sensitivity

    private fun setupThresholdControl() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val key = "thr_${engine.id}"
        threshold = prefs.getFloat(key, engine.thresholdDefault)
        val span = engine.thresholdMax - engine.thresholdMin
        b.seekThreshold.progress = (((threshold - engine.thresholdMin) / span) * 100).toInt().coerceIn(0, 100)
        showThreshold()
        b.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                threshold = engine.thresholdMin + span * progress / 100f
                showThreshold()
                if (fromUser) prefs.edit().putFloat(key, threshold).apply()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun showThreshold() {
        val kind = if (engine.usingModel) "neural model" else "built-in LBP"
        b.tvThreshold.text = "Match threshold: %.3f  (higher = stricter · %s)".format(threshold, kind)
    }

    // ------------------------------------------------------------ preparation

    /** Loads students (rebuilding embeddings made by another engine), then starts the camera. */
    private fun prepare() {
        b.tvStatus.text = "Loading students…"
        analysisExecutor.execute {
            try {
                val loaded = db.students().map { s ->
                    if (s.engine == engine.id) s else rebuild(s)
                }
                marked.clear()
                marked.addAll(db.presentToday(Db.today()).keys)
                students = loaded
                ready = true
                runOnUiThread {
                    b.tvStatus.text = "Look at the camera — you'll be marked automatically"
                    bindCamera()
                }
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed", e)
                runOnUiThread { b.tvStatus.text = "Could not load students: ${e.message}" }
            }
        }
    }

    private fun rebuild(s: Student): Student {
        val bmp = ImageUtil.decodeFile(this, s.photoPath, 1024) ?: return s
        val face = engine.detect(bmp, true).maxByOrNull { it.boundingBox.width() } ?: return s
        val emb = engine.embedPair(bmp, face) ?: return s
        db.updateEmbedding(s.id, engine.id, emb)
        return s.copy(engine = engine.id, embedding = emb)
    }

    // ----------------------------------------------------------------- camera

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.previewView.surfaceProvider)
            }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                b.tvStatus.text = "Camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // --------------------------------------------------------------- analysis

    private fun analyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!ready || now - lastRun < MIN_INTERVAL_MS) return
            lastRun = now

            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            val frame: Bitmap = if (rotation != 0) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true
                )
            } else raw

            val faces = engine.detect(frame, false)
                .sortedByDescending { it.boundingBox.width() }
                .take(MAX_FACES)

            if (faces.isEmpty()) {
                streaks.clear()
                postStatus("No face in view")
                return
            }

            val snapshot = students
            val seen = HashSet<Long>()
            val lines = ArrayList<String>()

            for (face in faces) {
                val emb = engine.embed(frame, face)
                if (emb == null) {
                    lines.add("Face too small – move closer")
                    continue
                }
                val result = FaceMatcher.match(emb, snapshot, threshold, engine.margin)
                val closest = result.closest
                val accepted = result.accepted

                if (accepted == null) {
                    lines.add(
                        if (closest != null) "Unsure (closest: ${closest.name}, score ${String.format("%.3f", result.score)})"
                        else "Unknown face"
                    )
                    continue
                }

                seen.add(accepted.id)
                val count = (streaks[accepted.id] ?: 0) + 1
                streaks[accepted.id] = count

                if (marked.contains(accepted.id)) {
                    lines.add("${accepted.name} – already marked")
                } else if (count >= CONFIRM_FRAMES) {
                    val newlyMarked = db.markPresent(accepted.id, Db.today(), Db.nowTime())
                    marked.add(accepted.id)
                    if (newlyMarked) {
                        lines.add("✓ ${accepted.name} marked present")
                        postRecent("${accepted.name} (${accepted.roll})")
                    } else {
                        lines.add("${accepted.name} – already marked")
                    }
                } else {
                    lines.add("Recognising ${accepted.name}… $count/$CONFIRM_FRAMES")
                }
            }
            streaks.keys.retainAll(seen)
            postStatus(lines.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "analysis error", e)
        } finally {
            proxy.close()
        }
    }

    private fun postStatus(text: String) {
        runOnUiThread { b.tvStatus.text = text }
    }

    private fun postRecent(entry: String) {
        runOnUiThread {
            recent.add(0, "${Db.nowTime().substring(0, 5)}  $entry")
            b.tvRecent.text = recent.take(4).joinToString("\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ready = false
        analysisExecutor.shutdown()
        engine.close()
    }

    companion object {
        private const val TAG = "Attendance"
        private const val MIN_INTERVAL_MS = 300L
        private const val CONFIRM_FRAMES = 3
        private const val MAX_FACES = 4
    }
}
