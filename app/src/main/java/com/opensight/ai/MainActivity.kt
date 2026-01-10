package com.opensight.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private val apiKey = "AIzaSyBhXWtEytc92xsArfJhH7Au11C1-Hp8aaM"

    private var isExploring = false
    private var imageCapture: ImageCapture? = null
    private val savedFaces = mutableMapOf<String, Bitmap>()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = uriToBitmap(it)
            askNameAndSave(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tts = TextToSpeech(this, this)
        loadSavedFaces()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        findViewById<Button>(R.id.btn_explore).setOnClickListener {
            toggleExploreMode(it as Button)
        }

        findViewById<Button>(R.id.btn_add_face).setOnClickListener {
            showImageSourceDialog()
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Add Face for Training")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> captureAndSaveFace()
                    1 -> galleryLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun toggleExploreMode(btn: Button) {
        isExploring = !isExploring
        if (isExploring) {
            btn.text = "Stop"
            btn.setBackgroundColor(0xFFFF0000.toInt())
            speak("Explore mode started")
            startAILoop()
        } else {
            btn.text = "Start Explore"
            btn.setBackgroundColor(0xFF4CAF50.toInt())
            speak("Explore mode stopped")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (exc: Exception) { Log.e("OpenSight", "Camera failed", exc) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAILoop() {
        CoroutineScope(Dispatchers.IO).launch {
            val model = GenerativeModel("gemini-2.0-flash", apiKey)
            while (isExploring) {
                val bitmap = captureImageSync()
                if (bitmap != null) {
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
                    try {
                        val response = model.generateContent(content {
                            image(scaledBitmap)
                            savedFaces.forEach { (name, faceBmp) ->
                                text("Known person: $name")
                                image(faceBmp)
                            }
                            text("Identify people and obstacles briefly.")
                        })
                        response.text?.let { speak(it) }
                    } catch (e: Exception) { Log.e("Gemini", "Error", e) }
                }
                delay(1500)
            }
        }
    }

    private fun captureAndSaveFace() {
        speak("Hold still...")
        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) { captureImageSync() }
            if (bitmap != null) askNameAndSave(bitmap)
        }
    }

    private fun askNameAndSave(bitmap: Bitmap) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Who is this?")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    saveFaceToStorage(name, bitmap)
                    speak("Saved $name.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFaceToStorage(name: String, bitmap: Bitmap) {
        val smallFace = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val file = File(filesDir, "$name.png")
        FileOutputStream(file).use { out -> smallFace.compress(Bitmap.CompressFormat.PNG, 100, out) }
        savedFaces[name] = smallFace
    }

    private fun loadSavedFaces() {
        filesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".png")) {
                val name = file.name.removeSuffix(".png")
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                savedFaces[name] = bitmap
            }
        }
    }

    private suspend fun captureImageSync(): Bitmap? = suspendCancellableCoroutine { cont ->
        imageCapture?.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                image.close()
                cont.resume(rotatedBitmap) { }
            }
            override fun onError(exception: ImageCaptureException) { cont.resume(null) { } }
        })
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    private fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale.US }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 10)
    }

    private fun allPermissionsGranted(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return camera && storage
    }
}
