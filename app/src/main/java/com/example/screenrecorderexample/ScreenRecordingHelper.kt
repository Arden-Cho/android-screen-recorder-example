package com.example.screenrecorderexample

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService

object ScreenRecordingHelper {
    var state by mutableStateOf(RecordingStates.IDLE)

    fun getScreenCaptureIntent(context: Context): Intent {
        val projectionManager = context.getSystemService<MediaProjectionManager>()!!
        return projectionManager.createScreenCaptureIntent()
    }

    fun startRecording(resultCode: Int, data: Intent, context: Context) {
        context.startForegroundService(Intent(context, ScreenRecordingService::class.java).apply {
            action = "start"
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        })
    }

    fun stopRecording(context: Context) {
        context.startService(Intent(context, ScreenRecordingService::class.java).apply {
            action = "stop"
        })
    }
}