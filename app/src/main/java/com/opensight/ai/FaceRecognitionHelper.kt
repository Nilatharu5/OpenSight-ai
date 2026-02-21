package com.opensight.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class FaceRecognitionHelper(val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelName = "mobile_face_net.tflite"
    private val inputSize = 112 
    private val outputSize = 192 
    private val saveFileName = "faces_database.dat"

    companion object {
        var registeredFaces = HashMap<String, FloatArray>()
    }

    init {
        setupInterpreter()
        if (registeredFaces.isEmpty()) {
            loadRegisteredFaces()
        }
    }

    private fun setupInterpreter() {
        try {
            val options = Interpreter.Options()
            options.setNumThreads(4) 
            interpreter = Interpreter(FileUtil.loadMappedFile(context, modelName), options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f)) 
            .build()

        var tensorImage = TensorImage.fromBitmap(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
        outputBuffer.order(ByteOrder.nativeOrder())
        interpreter?.run(tensorImage.buffer, outputBuffer)

        val embeddings = FloatArray(outputSize)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(embeddings)
        
        return l2Normalize(embeddings)
    }

    fun recognizeImage(bitmap: Bitmap): String {
        val newEmbedding = getFaceEmbedding(bitmap)
        var bestMatchName = "Unknown"
        var bestScore = 0.65f 

        for ((name, savedEmbedding) in registeredFaces) {
            val score = calculateCosineSimilarity(newEmbedding, savedEmbedding)
            if (score > bestScore) {
                bestScore = score
                bestMatchName = name
            }
        }
        return bestMatchName
    }

    fun registerFace(name: String, bitmap: Bitmap) {
        val embedding = getFaceEmbedding(bitmap)
        registeredFaces[name] = embedding 
        saveRegisteredFaces() 
        Log.d("OpenSight", "Singleton Database Updated: ")
    }

    fun removeFace(name: String): Boolean {
        if (registeredFaces.containsKey(name)) {
            registeredFaces.remove(name)
            saveRegisteredFaces()
            return true
        }
        return false
    }

    fun getRegisteredFaceList(): List<String> {
        return registeredFaces.keys.toList()
    }

    private fun l2Normalize(embeddings: FloatArray): FloatArray {
        var sum = 0f
        for (value in embeddings) {
            sum += value * value
        }
        val magnitude = sqrt(sum)
        if (magnitude > 0) {
            for (i in embeddings.indices) {
                embeddings[i] /= magnitude
            }
        }
        return embeddings
    }

    private fun calculateCosineSimilarity(e1: FloatArray, e2: FloatArray): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in e1.indices) {
            dotProduct += e1[i] * e2[i]
            normA += e1[i] * e1[i]
            normB += e2[i] * e2[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }

    private fun saveRegisteredFaces() {
        try {
            val file = File(context.filesDir, saveFileName)
            val fos = FileOutputStream(file)
            val oos = ObjectOutputStream(fos)
            oos.writeObject(registeredFaces)
            oos.close()
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadRegisteredFaces() {
        try {
            val file = File(context.filesDir, saveFileName)
            if (file.exists()) {
                val fis = FileInputStream(file)
                val ois = ObjectInputStream(fis)
                registeredFaces = ois.readObject() as HashMap<String, FloatArray>
                ois.close()
                fis.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}