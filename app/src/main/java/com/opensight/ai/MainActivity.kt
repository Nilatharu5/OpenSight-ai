package com.opensight.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.*
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var vibrator: Vibrator
    private var isAnalyzing = false
    private val apiKey = "AIzaSyBhXWtEytc92xsArfJhH7Au11C1-Hp8aaM"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (allPermissionsGranted()) {
            startObjectDetection()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    private fun startObjectDetection() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                // මෙතනදී තමයි අපි බාධක හඳුනාගන්නේ
                if (!isAnalyzing) {
                    analyzeFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        isAnalyzing = true
        // මෙතනදී අපි Gemini එකට Frame එක යවනවා (පොඩි delay එකකින්)
        val model = GenerativeModel(modelName = "gemini-2.0-flash", apiKey = apiKey)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // පින්තූරය ගත්තා කියලා හිතමු (මෙහිදී bitmap එකකට හරවා යැවිය යුතුයි)
            // බාධකයක් ඇත්නම් Vibrate කිරීම:
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(200)
            }
            
            // TTS මගින් අනතුරු ඇඟවීම
            withContext(Dispatchers.Main) {
                // Gemini response එක අනුව මෙතන "Steps ahead" වැනි දේ කියවනු ඇත
                tts.speak("Obstacle ahead", TextToSpeech.QUEUE_FLUSH, null, null)
                delay(2000) // තත්පර 2ක් ඉන්නවා ඊළඟ scan එකට කලින් battery බේරගන්න
                isAnalyzing = false
                imageProxy.close()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
