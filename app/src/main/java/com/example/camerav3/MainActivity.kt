package com.example.camerav3

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

enum class Look(
    val accent: Color,
    val saturation: Float,
    val contrast: Float,
    val warm: Float,
    val lift: Float
) {
    S(
        accent = Color(0xFF1688FF),
        saturation = 1.20f,
        contrast = 1.06f,
        warm = 0.02f,
        lift = 3f
    ),
    G(
        accent = Color(0xFF8AB4F8),
        saturation = 1.02f,
        contrast = 1.10f,
        warm = -0.01f,
        lift = 0f
    ),
    IPHONE(
        accent = Color(0xFFFFD60A),
        saturation = 1.07f,
        contrast = 1.02f,
        warm = 0.035f,
        lift = 5f
    )
}

enum class CaptureMode {
    PHOTO,
    VIDEO,
    PRO,
    NIGHT,
    PORTRAIT,
    PANORAMA,
    SLOMO
}

data class UiSpec(
    val modes: List<CaptureMode>,
    val selected: CaptureMode,
    val zooms: List<Float>,
    val extra: String
)

fun uiSpec(look: Look, mode: CaptureMode): UiSpec {
    return when (look) {
        Look.S -> UiSpec(
            modes = listOf(
                CaptureMode.PHOTO,
                CaptureMode.VIDEO,
                CaptureMode.PRO,
                CaptureMode.NIGHT
            ),
            selected = when (mode) {
                CaptureMode.PHOTO,
                CaptureMode.VIDEO,
                CaptureMode.PRO,
                CaptureMode.NIGHT -> mode
                else -> CaptureMode.PHOTO
            },
            zooms = listOf(0.5f, 1f, 2f),
            extra = "3:4"
        )

        Look.G -> UiSpec(
            modes = listOf(
                CaptureMode.PHOTO,
                CaptureMode.PORTRAIT,
                CaptureMode.NIGHT
            ),
            selected = when (mode) {
                CaptureMode.PHOTO,
                CaptureMode.PORTRAIT,
                CaptureMode.NIGHT -> mode
                else -> CaptureMode.PHOTO
            },
            zooms = listOf(0.5f, 1f, 2f, 5f),
            extra = "HDR+"
        )

        Look.IPHONE -> UiSpec(
            modes = listOf(
                CaptureMode.SLOMO,
                CaptureMode.VIDEO,
                CaptureMode.PHOTO,
                CaptureMode.PORTRAIT,
                CaptureMode.PANORAMA
            ),
            selected = when (mode) {
                CaptureMode.SLOMO,
                CaptureMode.VIDEO,
                CaptureMode.PHOTO,
                CaptureMode.PORTRAIT,
                CaptureMode.PANORAMA -> mode
                else -> CaptureMode.PHOTO
            },
            zooms = listOf(0.5f, 1f, 2f),
            extra = "PHOTO"
        )
    }
}

fun lookMatrix(look: Look, night: Boolean): ColorMatrix {
    val matrix = ColorMatrix()
    matrix.setSaturation(look.saturation)

    val c = look.contrast
    val offset =
        (1f - c) * 128f +
        look.lift +
        if (night) 7f else 0f

    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                c * (1f + look.warm), 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c * (1f - look.warm), 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    return matrix
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CameraV3App()
        }
    }
}

@Composable
fun CameraV3App() {

    val context = LocalContext.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            cameraGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            audioGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
        }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    if (cameraGranted) {
        CameraScreen(
            context = context,
            audioGranted = audioGranted
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Camera permission required",
                color = Color.White
            )
        }
    }
}

@Composable
fun CameraScreen(
    context: Context,
    audioGranted: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    var look by remember { mutableStateOf(Look.S) }
    var mode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var front by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var night by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordingRef by remember { mutableStateOf<Recording?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(
                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            )
            .setJpegQuality(95)
            .build()
    }

    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HIGHEST)
            )
            .build()
    }

    val videoCapture = remember {
        VideoCapture.withOutput(recorder)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    val spec = uiSpec(look, mode)

    fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({

            val provider = future.get()

            val preview =
                Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            previewView.surfaceProvider
                        )
                    }

            val selector =
                if (front) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            try {
                provider.unbindAll()

                camera = if (
                    mode == CaptureMode.VIDEO ||
                    mode == CaptureMode.SLOMO
                ) {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        videoCapture
                    )
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageCapture
                    )
                }

                camera?.cameraControl?.setZoomRatio(
                    zoom
                )

                camera?.cameraControl?.enableTorch(
                    flash
                )

            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Camera configuration failed",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(front, mode) {
        bindCamera()
    }

    LaunchedEffect(zoom, camera) {
        camera?.cameraControl?.setZoomRatio(
            zoom.coerceIn(0.5f, 10f)
        )
    }

    LaunchedEffect(flash, camera) {
        camera?.cameraControl?.enableTorch(flash)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        AndroidView(
            factory = { previewView },

            update = { view ->

                val paint = Paint()

                paint.colorFilter =
                    ColorMatrixColorFilter(
                        lookMatrix(
                            look,
                            night || mode == CaptureMode.NIGHT
                        )
                    )

                view.setLayerType(
                    android.view.View.LAYER_TYPE_HARDWARE,
                    paint
                )
            },

            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(
                    RoundedCornerShape(
                        if (look == Look.G) 24.dp else 0.dp
                    )
                )
                .pointerInput(Unit) {
                    detectTransformGestures {
                        _,
                        _,
                        gestureZoom,
                        _ ->
                            zoom =
                                (
                                    zoom * gestureZoom
                                ).coerceIn(0.5f, 10f)
                    }
                }
        )

        /*
         * Top brand selector
         */

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .clip(CircleShape)
                .background(Color(0xCC090909))
                .border(
                    1.dp,
                    Color(0x44555555),
                    CircleShape
                )
                .padding(3.dp)
        ) {

            BrandButton(
                text = "S",
                selected = look == Look.S,
                accent = Look.S.accent
            ) {
                look = Look.S
                mode = CaptureMode.PHOTO
                night = false
            }

            BrandDivider()

            BrandButton(
                text = "G",
                selected = look == Look.G,
                accent = Look.G.accent
            ) {
                look = Look.G
                mode = CaptureMode.PHOTO
                night = false
            }

            BrandDivider()

            BrandButton(
                text = "iPhone",
                selected = look == Look.IPHONE,
                accent = Look.IPHONE.accent
            ) {
                look = Look.IPHONE
                mode = CaptureMode.PHOTO
                night = false
            }
        }

        /*
         * Top controls
         */

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 82.dp)
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            TopButton("⚡") {
                flash = !flash
            }

            TopButton(
                when (look) {
                    Look.S -> "3:4"
                    Look.G -> "HDR+"
                    Look.IPHONE -> "⌃"
                }
            ) {}

            TopButton(
                when (look) {
                    Look.S -> "⚙"
                    Look.G -> "◉"
                    Look.IPHONE -> "◎"
                }
            ) {
                settingsOpen = !settingsOpen
            }

            TopButton("🌙") {
                night = !night
                if (look == Look.G) {
                    mode = CaptureMode.NIGHT
                }
            }
        }

        if (settingsOpen) {

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 126.dp)
                    .clip(
                        RoundedCornerShape(18.dp)
                    )
                    .background(Color(0xEE171717))
                    .padding(
                        horizontal = 16.dp,
                        vertical = 11.dp
                    ),
                horizontalArrangement =
                    Arrangement.spacedBy(18.dp)
            ) {

                Text(
                    "HDR",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Text(
                    "Timer",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Text(
                    "Ratio",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Text(
                    "Grid",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }

        /*
         * Zoom buttons
         */

        Row(
            Modifier
                .align(Alignment.Center)
                .padding(top = 220.dp)
                .clip(CircleShape)
                .background(Color(0xAA111111))
                .padding(
                    horizontal = 8.dp,
                    vertical = 5.dp
                ),
            horizontalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {

            spec.zooms.forEach { value ->

                val selected =
                    abs(zoom - value) < 0.05f

                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                look.accent
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable {
                            zoom = value
                        },
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text =
                            if (value == 0.5f) {
                                "0.5x"
                            } else {
                                "${value.toInt()}x"
                            },
                        color =
                            if (selected) {
                                Color.Black
                            } else {
                                Color.White
                            },
                        fontSize = 12.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }

        /*
         * Bottom controls
         */

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xEE050505))
                .padding(
                    top = 16.dp,
                    bottom = 18.dp
                ),
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(
                    Modifier
                        .size(52.dp)
                        .clip(
                            RoundedCornerShape(12.dp)
                        )
                        .background(
                            Color(0xFF333333)
                        ),
                    contentAlignment =
                        Alignment.Center
                ) {
                    Text(
                        "▧",
                        color = Color.White,
                        fontSize = 22.sp
                    )
                }

                Box(
                    Modifier
                        .size(
                            if (recording) {
                                74.dp
                            } else {
                                78.dp
                            }
                        )
                        .border(
                            4.dp,
                            if (recording) {
                                Color.Red
                            } else {
                                Color.White
                            },
                            CircleShape
                        )
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (recording) {
                                Color.Red
                            } else {
                                Color.White
                            }
                        )
                        .clickable {

                            if (
                                mode == CaptureMode.VIDEO ||
                                mode == CaptureMode.SLOMO
                            ) {

                                if (recording) {

                                    recordingRef?.stop()
                                    recordingRef = null
                                    recording = false

                                } else {

                                    if (!audioGranted) {

                                        Toast.makeText(
                                            context,
                                            "Microphone permission is required for video",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                    } else {

                                        val values =
                                            ContentValues().apply {
                                                put(
                                                    MediaStore.Video.Media.DISPLAY_NAME,
                                                    "CameraV3_${System.currentTimeMillis()}.mp4"
                                                )
                                                put(
                                                    MediaStore.Video.Media.MIME_TYPE,
                                                    "video/mp4"
                                                )
                                                put(
                                                    MediaStore.Video.Media.RELATIVE_PATH,
                                                    "DCIM/CameraV3"
                                                )
                                            }

                                        val output =
                                            MediaStoreOutputOptions
                                                .Builder(
                                                    context.contentResolver,
                                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                                )
                                                .setContentValues(values)
                                                .build()

                                        recordingRef =
                                            videoCapture.output
                                                .prepareRecording(
                                                    context,
                                                    output
                                                )
                                                .withAudioEnabled()
                                                .start(
                                                    ContextCompat.getMainExecutor(
                                                        context
                                                    )
                                                ) { event ->

                                                    when (event) {

                                                        is VideoRecordEvent.Start -> {
                                                            recording = true
                                                        }

                                                        is VideoRecordEvent.Finalize -> {
                                                            recording = false

                                                            Toast.makeText(
                                                                context,
                                                                "Video saved",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                    }
                                }

                            } else {

                                takePhoto(
                                    context,
                                    imageCapture,
                                    look,
                                    night ||
                                        mode == CaptureMode.NIGHT
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {}

                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0x66222222))
                        .clickable {
                            front = !front
                        },
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        "↻",
                        color = Color.White,
                        fontSize = 28.sp
                    )
                }
            }

            Spacer(
                Modifier.height(18.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(
                        rememberScrollState()
                    )
                    .padding(horizontal = 16.dp),
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                spec.modes.forEach { item ->

                    val active =
                        spec.selected == item

                    Text(
                        text = item.label(),
                        color =
                            if (active) {
                                look.accent
                            } else {
                                Color(0xCCFFFFFF)
                            },
                        fontSize = 13.sp,
                        fontWeight =
                            if (active) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        modifier = Modifier.clickable {

                            mode = item

                            if (
                                item ==
                                CaptureMode.NIGHT
                            ) {
                                night = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BrandButton(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) {
                    accent
                } else {
                    Color.Transparent
                }
            )
            .clickable {
                onClick()
            }
            .padding(
                horizontal = 18.dp,
                vertical = 8.dp
            ),
        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text,
            color =
                if (selected) {
                    Color.Black
                } else {
                    Color.White
                },
            fontSize = 14.sp,
            fontWeight =
                FontWeight.Bold
        )
    }
}

@Composable
fun BrandDivider() {
    Text(
        "|",
        color = Color(0x66777777),
        fontSize = 14.sp
    )
}

@Composable
fun TopButton(
    text: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 20.sp,
        modifier = Modifier.clickable {
            onClick()
        }
    )
}

fun CaptureMode.label(): String {
    return when (this) {
        CaptureMode.PHOTO -> "PHOTO"
        CaptureMode.VIDEO -> "VIDEO"
        CaptureMode.PRO -> "PRO"
        CaptureMode.NIGHT -> "NIGHT"
        CaptureMode.PORTRAIT -> "PORTRAIT"
        CaptureMode.PANORAMA -> "PANO"
        CaptureMode.SLOMO -> "SLO-MO"
    }
}

fun takePhoto(
    context: Context,
    capture: ImageCapture,
    look: Look,
    night: Boolean
) {

    val executor =
        Executors.newSingleThreadExecutor()

    capture.takePicture(
        executor,
        object :
            ImageCapture.OnImageCapturedCallback() {

            override fun onCaptureSuccess(
                image:
                androidx.camera.core.ImageProxy
            ) {

                try {

                    val bitmap =
                        image.toBitmap()

                    val rotation =
                        image.imageInfo.rotationDegrees

                    image.close()

                    val swap =
                        rotation == 90 ||
                            rotation == 270

                    val outputWidth =
                        if (swap) {
                            bitmap.height
                        } else {
                            bitmap.width
                        }

                    val outputHeight =
                        if (swap) {
                            bitmap.width
                        } else {
                            bitmap.height
                        }

                    val output =
                        Bitmap.createBitmap(
                            outputWidth,
                            outputHeight,
                            Bitmap.Config.ARGB_8888
                        )

                    val matrix =
                        Matrix().apply {
                            postTranslate(
                                -bitmap.width / 2f,
                                -bitmap.height / 2f
                            )
                            postRotate(
                                rotation.toFloat()
                            )
                            postTranslate(
                                outputWidth / 2f,
                                outputHeight / 2f
                            )
                        }

                    val paint =
                        Paint(
                            Paint.FILTER_BITMAP_FLAG
                        ).apply {

                            colorFilter =
                                ColorMatrixColorFilter(
                                    lookMatrix(
                                        look,
                                        night
                                    )
                                )
                        }

                    Canvas(output)
                        .drawBitmap(
                            bitmap,
                            matrix,
                            paint
                        )

                    val values =
                        ContentValues().apply {

                            put(
                                MediaStore.Images.Media.DISPLAY_NAME,
                                "CameraV3_${System.currentTimeMillis()}.jpg"
                            )

                            put(
                                MediaStore.Images.Media.MIME_TYPE,
                                "image/jpeg"
                            )

                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                "DCIM/CameraV3"
                            )
                        }

                    val uri =
                        context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values
                        )

                    if (uri != null) {

                        context.contentResolver
                            .openOutputStream(uri)
                            ?.use {

                                output.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    95,
                                    it
                                )
                            }

                        Handler(
                            Looper.getMainLooper()
                        ).post {

                            Toast.makeText(
                                context,
                                "Saved to DCIM/CameraV3",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    bitmap.recycle()
                    output.recycle()

                } catch (e: Exception) {

                    Handler(
                        Looper.getMainLooper()
                    ).post {

                        Toast.makeText(
                            context,
                            "Capture error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                executor.shutdown()
            }

            override fun onError(
                exception: ImageCaptureException
            ) {

                Handler(
                    Looper.getMainLooper()
                ).post {

                    Toast.makeText(
                        context,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                executor.shutdown()
            }
        }
    )
}
