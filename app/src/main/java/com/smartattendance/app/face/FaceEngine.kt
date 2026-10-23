package com.smartattendance.app.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.smartattendance.app.data.Student
import kotlin.math.max

/**
 * Detection (ML Kit, on-device) + alignment + embedding + matching.
 * All methods block, so call them from a background thread.
 */
class FaceEngine(context: Context) {

    private val fastDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.12f)
            .build()
    )
    private val accurateDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.10f)
            .build()
    )

    private val embedder: Embedder

    /** True when a neural model (assets/face_model.tflite) is in use instead of the built-in LBP. */
    val usingModel: Boolean

    val id: String get() = embedder.id
    val thresholdMin: Float get() = embedder.thresholdMin
    val thresholdMax: Float get() = embedder.thresholdMax
    val thresholdDefault: Float get() = embedder.thresholdDefault
    val margin: Float get() = embedder.margin

    init {
        var e: Embedder? = null
        try {
            if (context.assets.list("")?.contains(MODEL_ASSET) == true) {
                e = TfliteEmbedder(context, MODEL_ASSET)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load $MODEL_ASSET, falling back to LBP", t)
        }
        embedder = e ?: LbpEmbedder()
        usingModel = e != null
    }

    fun detect(bitmap: Bitmap, accurate: Boolean): List<Face> {
        val detector = if (accurate) accurateDetector else fastDetector
        return Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
    }

    /** Embedding of one detected face, or null if the face is too small to use. */
    fun embed(frame: Bitmap, face: Face): FloatArray? {
        val crop = alignedCrop(frame, face) ?: return null
        return try {
            embedder.embed(crop)
        } finally {
            crop.recycle()
        }
    }

    /**
     * Reference embedding for registration: the normal face followed by its mirror image
     * (front-camera photos are sometimes saved mirrored). Length is 2 x the live embedding length.
     */
    fun embedPair(frame: Bitmap, face: Face): FloatArray? {
        val crop = alignedCrop(frame, face) ?: return null
        val flipped = Bitmap.createBitmap(
            crop, 0, 0, crop.width, crop.height,
            Matrix().apply { postScale(-1f, 1f) }, true
        )
        return try {
            val a = embedder.embed(crop)
            val b = embedder.embed(flipped)
            a + b
        } finally {
            crop.recycle()
            if (flipped !== crop) flipped.recycle()
        }
    }

    /** Square crop around the face, rotated upright using the detector's roll angle. */
    private fun alignedCrop(src: Bitmap, face: Face): Bitmap? {
        val box = face.boundingBox
        val side = (max(box.width(), box.height()) * 1.3f).toInt()
        if (side < 40) return null
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(-box.centerX().toFloat(), -box.centerY().toFloat())
        m.postRotate(face.headEulerAngleZ)
        m.postTranslate(side / 2f, side / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun close() {
        fastDetector.close()
        accurateDetector.close()
        (embedder as? TfliteEmbedder)?.close()
    }

    companion object {
        const val MODEL_ASSET = "face_model.tflite"
        private const val TAG = "FaceEngine"
    }
}

/** Result of comparing one face against all registered students. */
data class FaceMatch(
    /** Non-null only when the score and margin both pass. */
    val accepted: Student?,
    /** Closest student regardless of the threshold (for on-screen feedback). */
    val closest: Student?,
    val score: Float
)

object FaceMatcher {
    /**
     * [embedding] is a live (single) embedding of length n; each student stores 2n values
     * (normal + mirrored reference). A student's score is the better of the two.
     */
    fun match(embedding: FloatArray, students: List<Student>, threshold: Float, margin: Float): FaceMatch {
        val n = embedding.size
        var best: Student? = null
        var bestScore = -1f
        var second = -1f
        var comparable = 0
        for (s in students) {
            if (s.embedding.size != 2 * n) continue
            comparable++
            var score = -1f
            for (half in 0..1) {
                var d = 0f
                val off = half * n
                for (i in 0 until n) d += embedding[i] * s.embedding[off + i]
                if (d > score) score = d
            }
            if (score > bestScore) {
                second = bestScore
                bestScore = score
                best = s
            } else if (score > second) {
                second = score
            }
        }
        val ok = best != null && bestScore >= threshold &&
            (comparable < 2 || bestScore - second >= margin)
        return FaceMatch(if (ok) best else null, best, bestScore)
    }
}
