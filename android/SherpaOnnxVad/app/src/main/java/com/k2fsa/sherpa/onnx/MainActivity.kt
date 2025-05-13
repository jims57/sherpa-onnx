package com.k2fsa.sherpa.onnx.vad

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread


private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var circle: View
    private lateinit var timestampsTextView: TextView

    private lateinit var vad: Vad

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    @Volatile
    private var isRecording: Boolean = false
    
    private var recordingStartTime: Long = 0
    private var isSpeaking: Boolean = false
    private var speechStartTime: Long = 0

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        Log.i(TAG, "Start to initialize model")
        initVadModel()
        Log.i(TAG, "Finished initializing model")

        circle= findViewById(R.id.powerCircle)
        timestampsTextView = findViewById(R.id.timestamps_text)
        timestampsTextView.movementMethod = ScrollingMovementMethod()

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            timestampsTextView.text = ""
            isSpeaking = false

            vad.reset()
            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
            onVad(false)

        } else {
            isRecording = false

            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null

            recordButton.setText(R.string.start)
            onVad(false)
            Log.i(TAG, "Stopped recording")
        }
    }

    private fun onVad(isSpeech: Boolean) {
        if(isSpeech) {
            circle.background = resources.getDrawable(R.drawable.red_circle)
        } else {
            circle.background = resources.getDrawable(R.drawable.black_circle)
        }
        
        // Track speech segments for timestamps
        val currentTime = System.currentTimeMillis()
        if (isSpeech && !isSpeaking) {
            // Speech started
            isSpeaking = true
            speechStartTime = currentTime
        } else if (!isSpeech && isSpeaking) {
            // Speech ended
            isSpeaking = false
            val elapsedFromStart = (speechStartTime - recordingStartTime) / 1000.0f
            val duration = (currentTime - speechStartTime) / 1000.0f
            
            // Format similar to silero timestamps
            val timestamp = "{'start': $elapsedFromStart, 'end': ${elapsedFromStart + duration}}"
            
            runOnUiThread {
                timestampsTextView.append("$timestamp\n")
                // Auto-scroll to bottom
                val scrollAmount = timestampsTextView.layout.getLineTop(timestampsTextView.lineCount) - timestampsTextView.height
                if (scrollAmount > 0) {
                    timestampsTextView.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    private  fun initVadModel() {
        val type = 0
        Log.i(TAG, "Select VAD model type ${type}")
        val config = getVadModelConfig(type)

        vad = Vad(
            assetManager = application.assets,
            config = config!!,
        )
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )
        return true
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")

        val bufferSize = 512 // in samples
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                // Convert 16-bit PCM audio samples (range -32768 to 32767) to floating point
                // by dividing by 32768.0f to normalize them to the range -1.0 to 0.999...
                // This is standard practice when processing audio for ML models
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }

                vad.acceptWaveform(samples)

                val isSpeechDetected = vad.isSpeechDetected()
                vad.clear()

                runOnUiThread {
                    onVad(isSpeechDetected)
                }
            }
        }
    }
}
