package com.opensight.ai

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.task.vision.detector.Detection
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, ObjectDetectorHelper.DetectorListener {

    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraControl: CameraControl? = null 
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private lateinit var resultTextView: TextView
    private lateinit var prefs: SharedPreferences
    
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var faceRecognitionHelper: FaceRecognitionHelper
    
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST) // Fast mode for Real-time Guidance
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    private val apiKeyDescribe = "AIzaSyDahzwEkYwChF3Z6hlOI6q87KBS00o4X1k"
    private val apiKeyExplore = "AIzaSyCXF8KZYucKbKJq1GTcFLDqz3AURDInTC4"
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
    
    private val developerName = "Tharindu Lakshan" 
    private val contactInfo = "\n\nContact Developer:\nWhatsApp: +94 743799814"
    private val dedicationText = "Dedicated to Nilakshi."

    private var isExploring = false
    private var isWalkingMode = false
    private var isFaceMode = false
    private var lastSpokenTime = 0L
    private val speechInterval = 3000L
    private var lastDetectedFaceBitmap: Bitmap? = null 
    private var isProUnlocked = false
    private val PRO_PIN = "tharU20@14"
    
    // Super Scanner Logic
    private var currentCandidateFace: Bitmap? = null
    private var originalBrightness = -1f
    
    // Guided Frame Logic
    private var lastGuidanceCommand = ""
    private var lastGuidanceTime = 0L

    private val exploreHandler = Handler(Looper.getMainLooper())
    private val exploreRunnable = object : Runnable {
        override fun run() {
            if (isExploring) {
                takePhoto(isExploreMode = true)
                exploreHandler.postDelayed(this, 6000) 
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("OpenSightPrefs", Context.MODE_PRIVATE)
        isProUnlocked = prefs.getBoolean("pro_unlocked", false)

        cameraExecutor = Executors.newSingleThreadExecutor()
        resultTextView = findViewById(R.id.tv_result)

        try {
            objectDetectorHelper = ObjectDetectorHelper(context = this, objectDetectorListener = this)
            faceRecognitionHelper = FaceRecognitionHelper(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Init Error", Toast.LENGTH_SHORT).show()
        }

        initializeTTS()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        setupButtons()
    }

    private fun setHighBrightness(enable: Boolean) {
        val layout = window.attributes
        if (enable) {
            originalBrightness = layout.screenBrightness
            layout.screenBrightness = 1.0f 
            findViewById<View>(android.R.id.content).setBackgroundColor(Color.WHITE) 
        } else {
            layout.screenBrightness = originalBrightness 
            findViewById<View>(android.R.id.content).setBackgroundColor(Color.TRANSPARENT)
        }
        window.attributes = layout
    }

    private fun toggleTorch(enable: Boolean) {
        if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            cameraControl?.enableTorch(enable)
        }
    }

    private fun setupButtons() {
        val btnAddFace = findViewById<Button>(R.id.btn_add_face)
        val btnImportFace = findViewById<Button>(R.id.btn_import_face)
        val btnSwitchCam = findViewById<Button>(R.id.btn_switch_camera)
        val btnIdentify = findViewById<Button>(R.id.btn_identify_face)

        btnSwitchCam.setOnClickListener {
            currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                announce("Switched to Front Camera")
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                announce("Switched to Back Camera")
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera() 
        }

        findViewById<Button>(R.id.btn_capture).setOnClickListener {
            stopAllModes()
            if (checkProAccess()) {
                takePhoto(isExploreMode = false)
            }
        }

        // --- SUPER SCANNER BUTTON (With Flash Delay) ---
        btnIdentify.setOnClickListener {
            if (currentCandidateFace != null) {
                setHighBrightness(true)
                toggleTorch(true)
                speakImmediate("Scanning")
                
                // Wait 400ms for Exposure Adjustment
                Handler(Looper.getMainLooper()).postDelayed({
                    val illuminatedFace = currentCandidateFace 
                    if (illuminatedFace != null) {
                         cameraExecutor.execute {
                            try {
                                val name = faceRecognitionHelper.recognizeImage(illuminatedFace)
                                runOnUiThread {
                                    if (name == "Unknown") {
                                        announce("Unknown Person")
                                    } else {
                                        announce("It is $name")
                                    }
                                    setHighBrightness(false)
                                    toggleTorch(false)
                                }
                            } catch (e: Exception) {
                                runOnUiThread { 
                                    announce("Error")
                                    setHighBrightness(false)
                                    toggleTorch(false)
                                }
                            }
                        }
                    } else {
                        setHighBrightness(false)
                        toggleTorch(false)
                    }
                }, 400) 
            } else {
                speakImmediate("Center face first")
            }
        }

        findViewById<Button>(R.id.btn_walking).setOnClickListener {
            val btn = findViewById<Button>(R.id.btn_walking)
            if (isWalkingMode) {
                stopAllModes()
                announce("Walking mode stopped")
            } else {
                stopAllModes()
                isWalkingMode = true
                btn.text = "Stop Walking"
                announce("Walking mode started")
            }
        }

        findViewById<Button>(R.id.btn_face_mode).setOnClickListener {
            val btn = findViewById<Button>(R.id.btn_face_mode)
            if (isFaceMode) {
                stopAllModes()
                announce("Face mode stopped")
            } else {
                stopAllModes()
                isFaceMode = true
                btnAddFace.visibility = View.VISIBLE
                btnImportFace.visibility = View.VISIBLE
                btnIdentify.visibility = View.VISIBLE 
                btn.text = "Stop Face Mode"
                announce("Face mode started.")
            }
        }

        btnAddFace.setOnClickListener {
            if (lastDetectedFaceBitmap != null) {
                showAddFaceDialog(lastDetectedFaceBitmap!!)
            } else {
                announce("No clear face detected.")
            }
        }

        btnImportFace.setOnClickListener {
            if (checkProAccess()) {
                registerGalleryLauncher.launch("image/*")
            }
        }

        findViewById<Button>(R.id.btn_manage_faces).setOnClickListener {
            showManageFacesDialog()
        }

        findViewById<Button>(R.id.btn_explore).setOnClickListener {
            val btn = findViewById<Button>(R.id.btn_explore)
            if (isExploring) {
                stopAllModes()
                announce("Explore stopped")
            } else {
                stopAllModes()
                if (checkProAccess()) {
                    isExploring = true
                    exploreHandler.post(exploreRunnable)
                    btn.text = "Stop Explore"
                    announce("Explore started")
                }
            }
        }

        findViewById<Button>(R.id.btn_gallery).setOnClickListener {
            stopAllModes()
            if (checkProAccess()) {
                galleryLauncher.launch("image/*")
            }
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btn_about).setOnClickListener { showAboutDialog() }
    }

    private fun checkProAccess(): Boolean {
        if (isProUnlocked) return true
        showProLockDialog()
        return false
    }

    private fun showProLockDialog() {
        val input = EditText(this)
        input.hint = "Enter Security Code"
        AlertDialog.Builder(this)
            .setTitle("Security Check")
            .setMessage("Enter developer code to unlock:")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == PRO_PIN) {
                    isProUnlocked = true
                    prefs.edit().putBoolean("pro_unlocked", true).apply()
                    announce("Unlocked Successfully")
                    Toast.makeText(this, "Welcome Tharindu", Toast.LENGTH_SHORT).show()
                } else {
                    announce("Access Denied")
                    Toast.makeText(this, "Wrong Code", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopAllModes() {
        isExploring = false
        exploreHandler.removeCallbacks(exploreRunnable)
        findViewById<Button>(R.id.btn_explore).text = "Explore"

        isWalkingMode = false
        findViewById<Button>(R.id.btn_walking).text = "Walking Mode"

        isFaceMode = false
        setHighBrightness(false)
        toggleTorch(false)
        
        findViewById<Button>(R.id.btn_face_mode).text = "Face Mode"
        findViewById<Button>(R.id.btn_add_face).visibility = View.GONE
        findViewById<Button>(R.id.btn_import_face).visibility = View.GONE
        findViewById<Button>(R.id.btn_identify_face).visibility = View.GONE
        lastDetectedFaceBitmap = null
        currentCandidateFace = null
    }

    // Standard announce (queues)
    private fun announce(text: String) {
        val volume = prefs.getFloat("tts_volume", 1.0f)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, null)
        runOnUiThread { resultTextView.text = text }
    }

    // FAST announce (Flushes queue - No Lag)
    private fun speakImmediate(text: String) {
        val volume = prefs.getFloat("tts_volume", 1.0f)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        // QUEUE_FLUSH drops previous text instantly
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, null)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        val bitmapBuffer = Bitmap.createBitmap(
                            image.width, image.height, Bitmap.Config.ARGB_8888
                        )
                        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
                        val rotation = image.imageInfo.rotationDegrees

                        if (isWalkingMode) {
                            objectDetectorHelper.detect(bitmapBuffer, rotation)
                        } else if (isFaceMode) {
                            processFaceDetection(bitmapBuffer, rotation)
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture, imageAnalyzer)
                cameraControl = camera.cameraControl 
            } catch (exc: Exception) {
                Log.e("OpenSight", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class) 
    private fun processFaceDetection(bitmap: Bitmap, rotation: Int) {
        val inputImage = InputImage.fromBitmap(bitmap, rotation)
        
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0] 
                    val bounds = face.boundingBox
                    
                    val centerX = bitmap.width / 2
                    val centerY = bitmap.height / 2
                    val faceCenterX = bounds.centerX()
                    val faceCenterY = bounds.centerY()
                    
                    val diffX = faceCenterX - centerX
                    val diffY = faceCenterY - centerY
                    
                    // GUIDED FRAME LOGIC (Android Style)
                    val thresholdX = 150
                    val thresholdY = 200
                    
                    var command = ""
                    
                    if (abs(diffX) < thresholdX && abs(diffY) < thresholdY) {
                         command = "Perfect"
                    } else {
                        // Priority to X axis (Left/Right)
                        if (diffX < -thresholdX) command = "Move Right"
                        else if (diffX > thresholdX) command = "Move Left"
                        
                        // Secondary priority Y axis (Up/Down) - only if X is okayish
                        else if (diffY < -thresholdY) command = "Move Down"
                        else if (diffY > thresholdY) command = "Move Up"
                    }

                    // Flush Speak logic (Don't repeat too fast unless changed)
                    val currentTime = System.currentTimeMillis()
                    if (command != lastGuidanceCommand || (currentTime - lastGuidanceTime > 2000)) {
                        if (command == "Perfect") {
                            if (lastGuidanceCommand != "Perfect") {
                                speakImmediate("Face Centered") // Announce once
                            }
                        } else {
                            speakImmediate(command)
                        }
                        lastGuidanceCommand = command
                        lastGuidanceTime = currentTime
                    }

                    // Prepare Bitmap for Flash Scan
                    val paddingX = (bounds.width() * 0.3).toInt() 
                    val paddingY = (bounds.height() * 0.3).toInt()
                    val cropRect = Rect(
                        (bounds.left - paddingX).coerceAtLeast(0),
                        (bounds.top - paddingY).coerceAtLeast(0),
                        (bounds.right + paddingX).coerceAtMost(bitmap.width),
                        (bounds.bottom + paddingY).coerceAtMost(bitmap.height)
                    )
                    if (cropRect.width() > 0 && cropRect.height() > 0) {
                        lastDetectedFaceBitmap = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                        currentCandidateFace = lastDetectedFaceBitmap 
                    }

                } else {
                    currentCandidateFace = null
                    lastGuidanceCommand = ""
                }
            }
            .addOnFailureListener {}
    }

    private fun showAddFaceDialog(faceBitmap: Bitmap) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Name this person")
        val input = EditText(this)
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val name = input.text.toString()
            if (name.isNotEmpty()) {
                faceRecognitionHelper.registerFace(name, faceBitmap)
                speakImmediate("Saved $name")
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun showManageFacesDialog() {
        val faces = faceRecognitionHelper.getRegisteredFaceList()
        if (faces.isEmpty()) {
            announce("No saved faces found.")
            return
        }
        val faceArray = faces.map { it as CharSequence }.toTypedArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Manage Faces")
        builder.setItems(faceArray) { _, which ->
            val nameToDelete = faces[which]
            showDeleteConfirmDialog(nameToDelete)
        }
        builder.show()
    }

    private fun showDeleteConfirmDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setMessage("Delete $name's face data?")
            .setPositiveButton("Yes") { _, _ ->
                if (faceRecognitionHelper.removeFace(name)) {
                    announce("Deleted $name")
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private val registerGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                announce("Processing photo...")
                
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val face = faces[0]
                            val faceBitmap = Bitmap.createBitmap(bitmap, face.boundingBox.left, face.boundingBox.top, face.boundingBox.width(), face.boundingBox.height())
                            showAddFaceDialog(faceBitmap)
                        } else {
                            announce("No face found")
                        }
                    }
            } catch (e: Exception) { announce("Error loading image") }
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                announce("Analyzing...")
                analyzeImageWithGemini(bitmap, false)
            } catch (e: Exception) { announce("Error loading image") }
        }
    }

    private fun analyzeImageWithGemini(originalBitmap: Bitmap, useExploreKey: Boolean) {
        cameraExecutor.execute {
            try {
                val currentKey = if (useExploreKey) apiKeyExplore else apiKeyDescribe
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 640, (640 / (originalBitmap.width.toFloat() / originalBitmap.height)).toInt(), true)
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 25, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", "Describe briefly.")).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", base64Image))))
                    }))
                }
                val url = URL(baseUrl + currentKey)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }
                if (conn.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val text = JSONObject(response).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    runOnUiThread { announce(text) }
                } else { runOnUiThread { announce("API Error") } }
            } catch (e: Exception) { runOnUiThread { announce("Connection Error") } }
        }
    }

    private fun takePhoto(isExploreMode: Boolean) {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                image.close()
                if (!isExploreMode) { announce("Processing...") }
                analyzeImageWithGemini(bitmap, isExploreMode)
            }
            override fun onError(exc: ImageCaptureException) { announce("Camera Error") }
        })
    }
    
    private fun initializeTTS() {
        val savedEngine = prefs.getString("tts_engine", null)
        try {
            tts = if (savedEngine != null) TextToSpeech(this, this, savedEngine) else TextToSpeech(this, this)
        } catch (e: Exception) { tts = TextToSpeech(this, this) }
    }
    
    private fun showSettingsDialog() {
        val options = arrayOf("Change Voice Engine", "Change Speech Rate", "Change Volume")
        AlertDialog.Builder(this).setTitle("Settings").setItems(options) { _, which ->
            when (which) {
                0 -> showEngineSelectionDialog()
                1 -> showSpeedSelectionDialog()
                2 -> showVolumeSelectionDialog()
            }
        }.show()
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About OpenSight")
            .setMessage(dedicationText)
            .setPositiveButton("Close") { _, _ -> }
            .show()
    }

    private fun showEngineSelectionDialog() {
        val engines = tts.engines
        val engineNames = ArrayList<String>()
        val enginePackages = ArrayList<String>()
        for (engine in engines) { engineNames.add(engine.label); enginePackages.add(engine.name) }
        val espeakPackage = "com.dedzoc.ramees.tts.espeak"
        if (!enginePackages.contains(espeakPackage)) {
            try {
                packageManager.getPackageInfo(espeakPackage, 0)
                engineNames.add("eSpeak NG (Manual)")
                enginePackages.add(espeakPackage)
            } catch (e: Exception) {}
        }
        AlertDialog.Builder(this).setTitle("Voice Engine").setItems(engineNames.toTypedArray()) { _, which ->
            prefs.edit().putString("tts_engine", enginePackages[which]).apply()
            initializeTTS()
        }.show()
    }

    private fun showSpeedSelectionDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
        AlertDialog.Builder(this).setTitle("Speed").setItems(speeds) { _, which ->
            prefs.edit().putFloat("tts_speed", values[which]).apply()
            tts.setSpeechRate(values[which])
            announce("Speed updated")
        }.show()
    }

    private fun showVolumeSelectionDialog() {
        val volumes = arrayOf("Low", "Medium", "High")
        val values = floatArrayOf(0.3f, 0.6f, 1.0f)
        AlertDialog.Builder(this).setTitle("Volume").setItems(volumes) { _, which ->
            prefs.edit().putFloat("tts_volume", values[which]).apply()
            announce("Volume updated")
        }.show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) { tts.setSpeechRate(prefs.getFloat("tts_speed", 1.0f)) } }
    
    override fun onResults(results: MutableList<Detection>?, inferenceTime: Long, imageHeight: Int, imageWidth: Int) {
        if (!isWalkingMode || results == null) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpokenTime < speechInterval) return

        val detectedObjects = results.mapNotNull {
            val category = it.categories.firstOrNull()
            if (category != null && category.score > 0.55) category.label else null
        }.distinct()

        if (detectedObjects.isNotEmpty()) {
            announce("Detected: $text") 
            lastSpokenTime = currentTime
        }
    }
    
    override fun onError(error: String) {}
    
    override fun onDestroy() { 
        super.onDestroy()
        stopAllModes()
        cameraExecutor.shutdown() 
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() } 
    }
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (allPermissionsGranted()) startCamera() else finish() }
    companion object { private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA) }
}
