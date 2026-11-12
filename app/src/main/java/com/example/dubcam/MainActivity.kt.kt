package com.example.dubcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

enum class AppState {
    IDLE,
    RECORDING_AUDIO,
    WAITING_VIDEO,
    RECORDING_VIDEO,
    PROCESSING,
    PREVIEW
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DubCamScreen()
            }
        }
    }
}

@Composable
fun DubCamScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (!hasPermissions) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Permissions Caméra et Micro requises", color = Color.White)
        }
        return
    }

    var appState by remember { mutableStateOf(AppState.IDLE) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var targetDurationMs by remember { mutableLongStateOf(0L) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    val audioFile = remember { File(context.cacheDir, "temp_audio.m4a") }
    val videoFile = remember { File(context.cacheDir, "temp_video.mp4") }
    val finalFile = remember { File(context.cacheDir, "dubcam_final.mp4") }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }

    LaunchedEffect(lensFacing, appState == AppState.PREVIEW) {
        if (appState != AppState.PREVIEW) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, vc)
                } catch (e: Exception) {
                    Toast.makeText(context, "Erreur caméra : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    LaunchedEffect(appState) {
        if (appState == AppState.RECORDING_AUDIO) {
            val start = System.currentTimeMillis()
            while (appState == AppState.RECORDING_AUDIO) {
                elapsedMs = System.currentTimeMillis() - start
                delay(50)
            }
        } else if (appState == AppState.RECORDING_VIDEO) {
            val start = System.currentTimeMillis()
            while (appState == AppState.RECORDING_VIDEO) {
                elapsedMs = System.currentTimeMillis() - start
                if (elapsedMs >= targetDurationMs) {
                    activeRecording?.stop()
                    activeRecording = null
                    break
                }
                delay(30)
            }
        }
    }

    LaunchedEffect(appState) {
        if (appState == AppState.PROCESSING) {
            val success = withContext(Dispatchers.IO) {
                try {
                    if (!videoFile.exists() || videoFile.length() == 0L) return@withContext false
                    if (!audioFile.exists() || audioFile.length() == 0L) return@withContext false
                    muxVideoAndAudio(videoFile, audioFile, finalFile)
                    saveToGallery(context, finalFile)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            if (success && finalFile.exists() && finalFile.length() > 0L) {
                Toast.makeText(context, "Vidéo enregistrée dans la galerie !", Toast.LENGTH_SHORT).show()
                appState = AppState.PREVIEW
            } else {
                Toast.makeText(context, "Erreur lors de l'assemblage", Toast.LENGTH_LONG).show()
                elapsedMs = 0L
                targetDurationMs = 0L
                appState = AppState.IDLE
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (appState == AppState.PREVIEW) {
            VideoPlayerView(
                file = finalFile,
                onRestart = {
                    elapsedMs = 0L
                    targetDurationMs = 0L
                    appState = AppState.IDLE
                }
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusText = when (appState) {
                    AppState.IDLE -> "PHASE 1 : PRÊT (SON)"
                    AppState.RECORDING_AUDIO -> "SON : ${formatTime(elapsedMs)}"
                    AppState.WAITING_VIDEO -> "PHASE 2 : PRÊT (VIDÉO ${formatTime(targetDurationMs)})"
                    AppState.RECORDING_VIDEO -> "VIDÉO : ${formatTime(elapsedMs)} / ${formatTime(targetDurationMs)}"
                    AppState.PROCESSING -> "ASSEMBLAGE..."
                    else -> ""
                }
                val badgeColor = when (appState) {
                    AppState.RECORDING_AUDIO -> Color(0xFF1E88E5)
                    AppState.WAITING_VIDEO -> Color(0xFFFFB300)
                    AppState.RECORDING_VIDEO -> Color(0xFFE53935)
                    else -> Color.Black.copy(alpha = 0.6f)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(badgeColor)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(statusText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            if (appState == AppState.RECORDING_VIDEO && targetDurationMs > 0) {
                val progress = (elapsedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 140.dp),
                    color = Color(0xFFE53935)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appState == AppState.WAITING_VIDEO) {
                    TextButton(onClick = {
                        elapsedMs = 0L
                        targetDurationMs = 0L
                        appState = AppState.IDLE
                    }) {
                        Text("Annuler", color = Color.White, fontSize = 16.sp)
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }

                val buttonColor = when (appState) {
                    AppState.IDLE, AppState.RECORDING_AUDIO -> Color(0xFF1E88E5)
                    AppState.WAITING_VIDEO, AppState.RECORDING_VIDEO -> Color(0xFFE53935)
                    else -> Color.Gray
                }
                val isRecording = appState == AppState.RECORDING_AUDIO || appState == AppState.RECORDING_VIDEO

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable(enabled = appState != AppState.PROCESSING) {
                            when (appState) {
                                AppState.IDLE -> {
                                    if (audioFile.exists()) audioFile.delete()
                                    try {
                                        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            MediaRecorder(context)
                                        } else {
                                            @Suppress("DEPRECATION")
                                            MediaRecorder()
                                        }
                                        recorder.apply {
                                            setAudioSource(MediaRecorder.AudioSource.MIC)
                                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                            setAudioSamplingRate(44100)
                                            setAudioEncodingBitRate(128000)
                                            setOutputFile(audioFile.absolutePath)
                                            prepare()
                                            start()
                                        }
                                        mediaRecorder = recorder
                                        elapsedMs = 0L
                                        appState = AppState.RECORDING_AUDIO
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Erreur micro : ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                AppState.RECORDING_AUDIO -> {
                                    if (elapsedMs < 500L) {
                                        Toast.makeText(context, "Enregistre au moins 1 seconde de son", Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    targetDurationMs = elapsedMs
                                    try {
                                        mediaRecorder?.stop()
                                        mediaRecorder?.release()
                                    } catch (_: Exception) {}
                                    mediaRecorder = null
                                    appState = AppState.WAITING_VIDEO
                                }
                                AppState.WAITING_VIDEO -> {
                                    val vc = videoCapture ?: return@clickable
                                    if (videoFile.exists()) videoFile.delete()
                                    elapsedMs = 0L
                                    val outputOptions = FileOutputOptions.Builder(videoFile).build()
                                    activeRecording = vc.output
                                        .prepareRecording(context, outputOptions)
                                        .start(ContextCompat.getMainExecutor(context)) { event ->
                                            if (event is VideoRecordEvent.Finalize) {
                                                if (!event.hasError()) {
                                                    appState = AppState.PROCESSING
                                                } else {
                                                    Toast.makeText(context, "Erreur vidéo : ${event.error}", Toast.LENGTH_SHORT).show()
                                                    appState = AppState.WAITING_VIDEO
                                                }
                                            }
                                        }
                                    appState = AppState.RECORDING_VIDEO
                                }
                                AppState.RECORDING_VIDEO -> {
                                    activeRecording?.stop()
                                    activeRecording = null
                                }
                                else -> {}
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isRecording) 32.dp else 60.dp)
                            .clip(if (isRecording) RoundedCornerShape(8.dp) else CircleShape)
                            .background(buttonColor)
                    )
                }

                if (appState == AppState.IDLE || appState == AppState.WAITING_VIDEO) {
                    TextButton(onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }) {
                        Text("Pivoter", color = Color.White, fontSize = 15.sp)
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }
            }
        }
    }
}

@Composable
fun VideoPlayerView(file: File, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(Uri.fromFile(file))
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                }
            }
        )
        Button(
            onClick = onRestart,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
        ) {
            Text("Nouvelle vidéo", color = Color.White)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return String.format("%02d:%02d.%d", minutes, seconds, tenths)
}

private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
    if (outputFile.exists()) outputFile.delete()
    val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
    val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
    val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    val videoTrackIndex = (0 until videoExtractor.trackCount).firstOrNull {
        videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    } ?: throw IllegalStateException("Piste vidéo introuvable")
    videoExtractor.selectTrack(videoTrackIndex)
    val muxerVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))

    val audioTrackIndex = (0 until audioExtractor.trackCount).firstOrNull {
        audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: throw IllegalStateException("Piste audio introuvable")
    audioExtractor.selectTrack(audioTrackIndex)
    val muxerAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))

    muxer.start()

    val buffer = ByteBuffer.allocate(1024 * 1024)
    val bufferInfo = MediaCodec.BufferInfo()

    copySamples(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)
    copySamples(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)

    muxer.stop()
    muxer.release()
    videoExtractor.release()
    audioExtractor.release()
}

private fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    trackIndex: Int,
    buffer: ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo
) {
    while (true) {
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break
        bufferInfo.offset = 0
        bufferInfo.size = sampleSize
        bufferInfo.presentationTimeUs = extractor.sampleTime
        bufferInfo.flags = extractor.sampleFlags
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
        extractor.advance()
    }
}

private fun saveToGallery(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "DubCam_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DubCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(it, values, null, null)
        }
    }
}