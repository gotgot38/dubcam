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
    IDLE,               // Prêt pour Phase 1 (Son)
    RECORDING_AUDIO,    // Phase 1 : Enregistrement du son seul
    WAITING_VIDEO,      // En attente de Phase 2 (Vidéo)
    RECORDING_VIDEO,    // Phase 2 : Enregistrement de la vidéo muette (même durée)
    PROCESSING,         // Fusion audio + vidéo
    PREVIEW             // Lecture du résultat
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

    // Initialisation de la caméra (uniquement au changement d'objectif ou sortie de preview)
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

    // Chronomètre Phase 1 (Son) et Phase 2 (Vidéo minutée)
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
                    // Arrêt automatique de la vidéo dès que la durée du son est atteinte
                    activeRecording?.stop()
                    activeRecording = null
                    break
                }
                delay(30)
            }
        }
    }

    // Assemblage audio + vidéo et sauvegarde galerie
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
                Toast.makeText(context, "Erreur lors de l'assemblage (durée trop courte ?)", Toast.LENGTH_LONG).show()
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