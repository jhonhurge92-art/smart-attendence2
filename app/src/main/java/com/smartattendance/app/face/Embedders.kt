package com.smartattendance.app.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Turns an upright, square face crop into a unit-length feature vector. */
interface Embedder {
    /** Stored with each student so embeddings from a different engine can be rebuilt. */
    val id: String
    val thresholdMin: Float
    val thresholdMax: Float
    val thresholdDefault: Float

    /** Best match must beat the second best by at least this much (when 2+ students exist). */
    val margin: Float

    fun embed(face: Bitmap): FloatArray
}

fun l2normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += (x * x).toDouble()
    val n = sqrt(s).toFloat()
    if (n > 0f) for (i in v.indices) v[i] = v[i] / n
    return v
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * Built-in engine that needs no model file: histogram-of-Local-Binary-Patterns on a 6x6 grid
 * (the classic "LBPH" face descriptor) after histogram equalisation.
 * Works offline but is much less robust than a neural embedding model.
 */
class LbpEmbedder : Embedder {
    override val id = "lbp-v1"
    override val thresholdMin = 0.80f
    override val thresholdMax = 0.995f
    override val thresholdDefault = 0.93f
    override val margin = 0.006f

    override fun embed(face: Bitmap): FloatArray {
        val s = SIZE
        val scaled = Bitmap.createScaledBitmap(face, s, s, true)
        val px = IntArray(s * s)
        scaled.getPixels(px, 0, s, 0, 0, s, s)
        val g = IntArray(s * s) {
            val p = px[it]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()
        }
        equalize(g)

        val hist = FloatArray(GRID * GRID * BINS)
        val inner = s - 2
        for (y in 1 until s - 1) {
            for (x in 1 until s - 1) {
                val c = g[y * s + x]
                var code = 0
                for (k in 0 until 8) {
                    if (g[(y + DY[k]) * s + (x + DX[k])] >= c) code = code or (1 shl k)
                }
                val cy = (y - 1) * GRID / inner
                val cx = (x - 1) * GRID / inner
                hist[(cy * GRID + cx) * BINS + UNIFORM[code]] += 1f
            }
        }
        // Per cell: L1 normalise then square-root (Hellinger), so cosine similarity behaves well.
        for (cell in 0 until GRID * GRID) {
            val off = cell * BINS
            var sum = 0f
            for (i in 0 until BINS) sum += hist[off + i]
            if (sum > 0f) for (i in 0 until BINS) hist[off + i] = sqrt(hist[off + i] / sum)
        }
        return l2normalize(hist)
    }

    private fun equalize(g: IntArray) {
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val cdf = IntArray(256)
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            cdf[i] = acc
        }
        val cdfMin = cdf.first { it > 0 }
        val denom = max(1, g.size - cdfMin)
        for (i in g.indices) {
            g[i] = ((cdf[g[i]] - cdfMin) * 255f / denom).toInt().coerceIn(0, 255)
        }
    }

    companion object {
        private const val SIZE = 96
        private const val GRID = 6
        private const val BINS = 59
        private val DX = intArrayOf(-1, 0, 1, 1, 1, 0, -1, -1)
        private val DY = intArrayOf(-1, -1, -1, 0, 1, 1, 1, 0)

        /** Maps the 256 LBP codes onto 58 "uniform" patterns + 1 bin for everything else. */
        private val UNIFORM: IntArray = IntArray(256).also { map ->
            var next = 0
            for (code in 0..255) {
                var transitions = 0
                for (i in 0 until 8) {
                    val a = (code shr i) and 1
                    val b = (code shr ((i + 1) % 8)) and 1
                    if (a != b) transitions++
                }
                map[code] = if (transitions <= 2) next++ else -1
            }
            for (code in 0..255) if (map[code] == -1) map[code] = next
        }
    }
}

/**
 * Neural face-embedding engine. Loads any float32 TFLite face-embedding model (MobileFaceNet,
 * FaceNet, ...) placed at app/src/main/assets/face_model.tflite. Input size and output length are
 * read from the model itself.
 */
class TfliteEmbedder(context: Context, assetName: String) : Embedder {
    override val id = "tflite:$assetName"
    override val thresholdMin = 0.40f
    override val thresholdMax = 0.90f
    override val thresholdDefault = 0.65f
    override val margin = 0.04f

    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int
    private val outDim: Int

    init {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        val inShape = interpreter.getInputTensor(0).shape() // [1, h, w, 3]
        inH = inShape[1]
        inW = inShape[2]
        outDim = interpreter.getOutputTensor(0).shape().last()
    }

    @Synchronized
    override fun embed(face: Bitmap): FloatArray {
        val bmp = Bitmap.createScaledBitmap(face, inW, inH, true)
        val px = IntArray(inW * inH)
        bmp.getPixels(px, 0, inW, 0, 0, inW, inH)
        val input = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(outDim) }
        interpreter.run(input, out)
        return l2normalize(out[0])
    }

    fun close() = interpreter.close()
}
