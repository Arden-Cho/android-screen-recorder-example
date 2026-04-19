package com.example.screenrecorderexample

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.screenrecorderexample.RecordingStates.ENCODING
import com.example.screenrecorderexample.RecordingStates.IDLE
import com.example.screenrecorderexample.RecordingStates.RECORDING

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.let { data ->
                ScreenRecordingHelper.startRecording(it.resultCode, data, context)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            screenCaptureLauncher.launch(ScreenRecordingHelper.getScreenCaptureIntent(context))
        } else {
            Toast.makeText(context, "Permission Denied.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            Button({
                if (ScreenRecordingHelper.state == IDLE) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                } else if (ScreenRecordingHelper.state == RECORDING) {
                    ScreenRecordingHelper.stopRecording(context)
                }
            }, enabled = ScreenRecordingHelper.state != ENCODING) {
                Text(
                    when (ScreenRecordingHelper.state) {
                        IDLE -> "Start Recording"
                        RECORDING -> "Stop Recording"
                        ENCODING -> "Encoding..."
                    }
                )
            }
        }
    }
}