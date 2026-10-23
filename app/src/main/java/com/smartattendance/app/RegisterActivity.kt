package com.smartattendance.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.smartattendance.app.data.Db
import com.smartattendance.app.data.ImageUtil
import com.smartattendance.app.databinding.ActivityRegisterBinding
import com.smartattendance.app.face.FaceEngine
import java.io.File
import java.util.concurrent.Executors

/** Register a student: name + roll number + one clear reference photo containing one face. */
class RegisterActivity : AppCompatActivity() {

    private lateinit var b: ActivityRegisterBinding
    private lateinit var db: Db
    private lateinit var engine: FaceEngine
    private val io = Executors.newSingleThreadExecutor()

    private var photo: Bitmap? = null
    private var embedding: FloatArray? = null
    private var cameraUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraUri?.let { handleImage(it) }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleImage(uri)
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(this, "Camera permission is needed to take a photo", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db(this)
        engine = FaceEngine(this)

        b.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        b.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        b.btnSave.setOnClickListener { save() }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "capture.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        takePicture.launch(uri)
    }

    /** Decode, then verify that the photo contains exactly one usable face. */
    private fun handleImage(uri: Uri) {
        photo = null
        embedding = null
        b.tvFaceStatus.text = "Checking photo…"
        io.execute {
            val outcome = analysePhoto(uri)
            runOnUiThread {
                b.tvFaceStatus.text = outcome.message
                val bmp = outcome.bitmap
                if (bmp != null) {
                    b.ivPhoto.setPadding(0, 0, 0, 0)
                    b.ivPhoto.setImageBitmap(bmp)
                }
                if (bmp != null && outcome.embedding != null) {
                    photo = bmp
                    embedding = outcome.embedding
                }
            }
        }
    }

    private class Outcome(val message: String, val bitmap: Bitmap?, val embedding: FloatArray?)

    private fun analysePhoto(uri: Uri): Outcome {
        return try {
            val bmp = ImageUtil.decodeUri(this, uri, 1024)
                ?: return Outcome("Could not read that image. Try another one.", null, null)
            val faces = engine.detect(bmp, true)
            when {
                faces.isEmpty() ->
                    Outcome("No face found. Use a clear, front-facing photo.", bmp, null)
                faces.size > 1 ->
                    Outcome("More than one face found. Use a photo of just the student.", bmp, null)
                else -> {
                    val emb = engine.embedPair(bmp, faces[0])
                    if (emb == null) Outcome("Face is too small. Move closer and retry.", bmp, null)
                    else Outcome("Face detected ✓", bmp, emb)
                }
            }
        } catch (e: Exception) {
            Outcome("Could not analyse the photo: ${e.message}", null, null)
        }
    }

    private fun save() {
        val name = b.etName.text?.toString()?.trim().orEmpty()
        val roll = b.etRoll.text?.toString()?.trim().orEmpty()
        b.tilName.error = if (name.isEmpty()) "Enter the student's name" else null
        b.tilRoll.error = if (roll.isEmpty()) "Enter the roll number" else null
        if (name.isEmpty() || roll.isEmpty()) return

        val bmp = photo
        val emb = embedding
        if (bmp == null || emb == null) {
            Toast.makeText(this, "Add a photo with exactly one clear face first", Toast.LENGTH_LONG).show()
            return
        }

        b.btnSave.isEnabled = false
        io.execute {
            val file = File(File(filesDir, "photos"), "${System.currentTimeMillis()}.jpg")
            try {
                ImageUtil.saveJpeg(bmp, file)
                val id = db.addStudent(name, roll, file.absolutePath, engine.id, emb)
                runOnUiThread {
                    if (id == -1L) {
                        file.delete()
                        b.tilRoll.error = "This roll number is already registered"
                        b.btnSave.isEnabled = true
                    } else {
                        Toast.makeText(this, "$name registered", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
                    b.btnSave.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        engine.close()
    }
}
