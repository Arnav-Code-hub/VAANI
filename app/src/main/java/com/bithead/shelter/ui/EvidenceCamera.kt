package com.bithead.shelter.ui

import com.bithead.shelter.i18n.str
import com.bithead.shelter.R

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun EvidenceCamera(
    pendingDir: File,
    incidentId: String?,
    onPhoto: (File) -> Unit,
    onVideo: (File) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var videoMode by remember { mutableStateOf(false) }
    var photoCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(permission, videoMode, lifecycle) {
        var provider: ProcessCameraProvider? = null
        if (permission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    provider?.unbindAll()
                    val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                    if (videoMode) {
                        val capture = VideoCapture.withOutput(Recorder.Builder().build())
                        provider?.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        videoCapture = capture
                        photoCapture = null
                    } else {
                        val capture = ImageCapture.Builder().build()
                        provider?.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        photoCapture = capture
                        videoCapture = null
                    }
                    problem = null
                } catch (error: Exception) {
                    problem = str(R.string.camera_unavailable, error.message ?: str(R.string.cannot_open_camera))
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            recording?.stop()
            provider?.unbindAll()
            photoCapture = null
            videoCapture = null
        }
    }
    LaunchedEffect(recording) {
        if (recording != null) {
            delay(60_000L)
            recording?.stop()
        }
    }
    BackHandler { if (recording != null) recording?.stop() else onClose() }

    Column(Modifier.fillMaxSize().background(Color(0xFF121214))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(str(R.string.private_evidence_camera), color = Color.White, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { if (recording != null) recording?.stop() else onClose() }) { Text(str(R.string.close)) }
        }
        if (!permission) {
            Text(str(R.string.camera_access_is_required_for_private_captur), Modifier.padding(20.dp), color = Color.White)
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, Modifier.padding(20.dp)) { Text(str(R.string.allow_camera)) }
        } else {
            AndroidView({ previewView }, Modifier.fillMaxWidth().weight(1f))
            problem?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { videoMode = false }, enabled = recording == null, modifier = Modifier.weight(1f)) { Text(str(R.string.photo)) }
                OutlinedButton(onClick = { videoMode = true }, enabled = recording == null, modifier = Modifier.weight(1f)) { Text(str(R.string.silent_video)) }
            }
            Button(
                onClick = {
                    if (!pendingDir.exists() && !pendingDir.mkdirs()) {
                        problem = str(R.string.cannot_create_private_evidence_storage)
                        return@Button
                    }
                    val type = if (videoMode) "video" else "image"
                    val extension = if (videoMode) "mp4" else "jpg"
                    val session = incidentId ?: System.currentTimeMillis().toString()
                    var timestamp = System.currentTimeMillis()
                    var file = File(pendingDir, "evidence_${timestamp}_${type}_${session}.raw.$extension")
                    while (file.exists()) {
                        timestamp++
                        file = File(pendingDir, "evidence_${timestamp}_${type}_${session}.raw.$extension")
                    }
                    if (videoMode) {
                        if (recording != null) {
                            recording?.stop()
                        } else {
                            val capture = videoCapture ?: return@Button
                            val target = file
                            recording = capture.output.prepareRecording(context, FileOutputOptions.Builder(target).build())
                                .start(ContextCompat.getMainExecutor(context)) { event ->
                                    if (event is VideoRecordEvent.Finalize) {
                                        recording = null
                                        if (event.hasError() || target.length() == 0L) {
                                            target.delete()
                                            problem = str(R.string.video_could_not_be_saved)
                                        } else onVideo(target)
                                    }
                                }
                        }
                    } else {
                        val capture = photoCapture ?: return@Button
                        val target = file
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(target).build(),
                            ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) = onPhoto(target)
                                override fun onError(error: androidx.camera.core.ImageCaptureException) {
                                    target.delete()
                                    problem = str(R.string.photo_not_saved, error.message.orEmpty())
                                }
                            })
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            ) { Text(if (recording != null) str(R.string.stop_and_seal_video) else if (videoMode) str(R.string.record_up_to_60_seconds) else str(R.string.capture_and_seal_photo)) }
        }
    }
}
