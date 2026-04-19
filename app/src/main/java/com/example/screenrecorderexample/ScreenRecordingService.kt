package com.example.screenrecorderexample

import android.annotation.SuppressLint
import android.app.Activity.RESULT_CANCELED
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ScreenRecordingService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioThread: Thread? = null

    private lateinit var videoFile: File
    private lateinit var pcmFile: File
    private lateinit var m4aFile: File
    private lateinit var outputFile: File

    private val sampleRate = 44100

    override fun onCreate() {
        super.onCreate()
        videoFile = File(cacheDir, "video.mp4")
        pcmFile = File(cacheDir, "audio.pcm")
        m4aFile = File(cacheDir, "audio.m4a")
        outputFile = File(filesDir, "output.mp4")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action ?: return START_NOT_STICKY
        startForeground(
            1,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        when (intent.action) {
            "start" -> {
                val manager = getSystemService<MediaProjectionManager>()!!
                val resultCode = intent.getIntExtra("resultCode", RESULT_CANCELED)
                val data =
                    intent.getParcelableExtra("data", Intent::class.java) ?: return START_NOT_STICKY
                mediaProjection = manager.getMediaProjection(resultCode, data)!!.apply {
                    registerCallback(object : Callback() {}, null)
                }
                startScreenRecording()
                startAudioRecording()
            }

            "stop" -> {
                ScreenRecordingHelper.state = RecordingStates.ENCODING
                CoroutineScope(Dispatchers.IO).launch {
                    virtualDisplay?.release()
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                    mediaProjection?.stop()
                    audioThread?.join()
                    encodeAudio()
                    mergeVideoAndAudio()
                    cleanUpTemporaryFiles()
                    ScreenRecordingHelper.state = RecordingStates.IDLE
                    stopSelf()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @SuppressLint("MissingPermission")
    fun startAudioRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBufferSize, 1024))
            .build()
        audioRecord.startRecording()
        ScreenRecordingHelper.state = RecordingStates.RECORDING
        audioThread = thread {
            val buffer = ByteArray(1024)
            pcmFile.outputStream().use {
                while (ScreenRecordingHelper.state == RecordingStates.RECORDING) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) it.write(buffer, 0, read)
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private fun mergeVideoAndAudio() {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(m4aFile.absolutePath) }

        val mediaMuxer =
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoTrackIndex = (0 until videoExtractor.trackCount).first {
            videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        }.also {
            videoExtractor.selectTrack(it)
        }.let {
            mediaMuxer.addTrack(videoExtractor.getTrackFormat(it))
        }
        val audioTrackIndex = (0 until audioExtractor.trackCount).first {
            audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }.also {
            audioExtractor.selectTrack(it)
        }.let {
            mediaMuxer.addTrack(audioExtractor.getTrackFormat(it))
        }

        mediaMuxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        fun copyTrack(extractor: MediaExtractor, trackIndex: Int) {
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size >= 0) {
                    bufferInfo.flags =
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    mediaMuxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    extractor.advance()
                } else {
                    break
                }
            }
        }

        copyTrack(videoExtractor, videoTrackIndex)
        copyTrack(audioExtractor, audioTrackIndex)

        videoExtractor.release()
        audioExtractor.release()
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    private fun encodeAudio() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffer = ByteArray(2048)
        var trackIndex = -1
        var presentationTimeUs = 0L

        val mediaMuxer =
            MediaMuxer(m4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        pcmFile.inputStream().buffered().use {
            out@ while (true) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)!!
                    val read = it.read(inputBuffer)
                    if (read > 0) {
                        buffer.clear()
                        buffer.put(inputBuffer, 0, read)
                        codec.queueInputBuffer(inputIndex, 0, read, presentationTimeUs, 0)
                        presentationTimeUs += 1000000 * read / (sampleRate * 2 * 2)
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = mediaMuxer.addTrack(codec.outputFormat)
                        mediaMuxer.start()
                    } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outputIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@out
                }
            }
        }
        codec.stop()
        codec.release()
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    private fun startScreenRecording() {
        val width = resources.displayMetrics.widthPixels.let { if (it % 2 != 0) it - 1 else it }
        val height = resources.displayMetrics.heightPixels.let { if (it % 2 != 0) it - 1 else it }
        val dpi = resources.displayMetrics.densityDpi
        mediaRecorder = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFile(videoFile)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoSize(width, height)
            setVideoFrameRate(60)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(3500000)
            prepare()
            start()
        }
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "vt",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null,
            null
        )
    }

    private fun cleanUpTemporaryFiles() {
        pcmFile.delete()
        m4aFile.delete()
        videoFile.delete()
    }

    private fun createNotification(): Notification {
        val id = "id"
        val manager = getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(NotificationChannel(id, id, IMPORTANCE_LOW))
        return Notification.Builder(this, id)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording...")
            .setContentText("The screen is being recorded.")
            .build()
    }

    override fun onBind(intent: Intent) = null
}