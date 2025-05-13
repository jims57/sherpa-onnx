package com.k2fsa.sherpa.onnx.vad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private lateinit var audioSegmentsList: ListView

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
    
    private val audioSegments = mutableListOf<AudioSegment>()
    private val audioAdapter by lazy { AudioSegmentAdapter(this, audioSegments) }
    
    private val pcmSamples = mutableListOf<Short>()
    private var mediaPlayer: MediaPlayer? = null

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
        
        audioSegmentsList = findViewById(R.id.audio_segments_list)
        audioSegmentsList.adapter = audioAdapter

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
            pcmSamples.clear()
            audioSegments.clear()
            audioAdapter.notifyDataSetChanged()

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
            val endTime = elapsedFromStart + duration
            
            // Format similar to silero timestamps
            val timestamp = "{'start': $elapsedFromStart, 'end': $endTime}"
            
            runOnUiThread {
                timestampsTextView.append("$timestamp\n")
                // Auto-scroll to bottom
                val scrollAmount = timestampsTextView.layout.getLineTop(timestampsTextView.lineCount) - timestampsTextView.height
                if (scrollAmount > 0) {
                    timestampsTextView.scrollTo(0, scrollAmount)
                }
                
                // Split the audio and create WAV file
                splitAudio(elapsedFromStart, endTime)
            }
        }
    }
    
    private fun splitAudio(startSec: Float, endSec: Float) {
        val startSample = (startSec * sampleRateInHz).toInt()
        val endSample = (endSec * sampleRateInHz).toInt()
        
        if (startSample >= pcmSamples.size || startSample < 0) {
            Log.e(TAG, "Invalid start sample: $startSample, total samples: ${pcmSamples.size}")
            return
        }
        
        val endIdx = if (endSample >= pcmSamples.size) pcmSamples.size - 1 else endSample
        
        if (endIdx <= startSample) {
            Log.e(TAG, "Invalid sample range: $startSample to $endIdx")
            return
        }
        
        val segmentSamples = pcmSamples.subList(startSample, endIdx).toShortArray()
        
        // Create WAV file
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "segment_$timestamp.wav"
        val file = File(filesDir, filename)
        
        // Write WAV file
        try {
            writeWavFile(file, segmentSamples)
            
            // Add to UI list
            val audioSegment = AudioSegment(
                filename,
                "Audio ${audioSegments.size + 1}: ${String.format("%.2f", startSec)}s - ${String.format("%.2f", endSec)}s",
                file.absolutePath
            )
            
            audioSegments.add(audioSegment)
            runOnUiThread {
                audioAdapter.notifyDataSetChanged()
            }
            
            Log.i(TAG, "Created audio segment: $filename, from ${startSec}s to ${endSec}s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file", e)
        }
    }
    
    private fun writeWavFile(file: File, samples: ShortArray) {
        FileOutputStream(file).use { out ->
            // WAV header
            val headerSize = 44
            val dataSize = samples.size * 2  // 2 bytes per sample (16-bit)
            val totalSize = headerSize + dataSize
            
            val header = ByteBuffer.allocate(headerSize)
            header.order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalSize - 8)  // Total size - 8 bytes
            header.put("WAVE".toByteArray())
            
            // Format chunk
            header.put("fmt ".toByteArray())
            header.putInt(16)  // Subchunk1Size (16 for PCM)
            header.putShort(1)  // AudioFormat (1 for PCM)
            header.putShort(1)  // NumChannels (1 for mono)
            header.putInt(sampleRateInHz)  // SampleRate
            header.putInt(sampleRateInHz * 2)  // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
            header.putShort(2)  // BlockAlign (NumChannels * BitsPerSample/8)
            header.putShort(16)  // BitsPerSample
            
            // Data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)
            
            out.write(header.array())
            
            // Write PCM data
            val dataBuffer = ByteBuffer.allocate(dataSize)
            dataBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                dataBuffer.putShort(sample)
            }
            out.write(dataBuffer.array())
        }
    }

    private fun initVadModel() {
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
                // Store PCM samples for later use
                synchronized(pcmSamples) {
                    for (i in 0 until ret) {
                        pcmSamples.add(buffer[i])
                    }
                }
                
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
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
    
    data class AudioSegment(
        val filename: String,
        val displayName: String,
        val filePath: String
    )
    
    inner class AudioSegmentAdapter(
        context: Context, 
        private val segments: List<AudioSegment>
    ) : ArrayAdapter<AudioSegment>(context, 0, segments) {
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.audio_item, parent, false)
            
            val segment = segments[position]
            val nameTextView = view.findViewById<TextView>(R.id.audio_segment_name)
            val playButton = view.findViewById<Button>(R.id.play_button)
            
            nameTextView.text = segment.displayName
            
            playButton.setOnClickListener {
                playAudio(segment.filePath)
            }
            
            return view
        }
    }
    
    private fun playAudio(filePath: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(filePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }
}
