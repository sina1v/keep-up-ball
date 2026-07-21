package com.sina1v.keepup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_ANKLE
import com.google.mlkit.vision.pose.PoseLandmark.LEFT_FOOT_INDEX
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_ANKLE
import com.google.mlkit.vision.pose.PoseLandmark.RIGHT_FOOT_INDEX
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.hypot
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var gameView: GameView
    private lateinit var scoreView: TextView
    private lateinit var messageView: TextView

    private val detector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            messageView.text = "Camera permission is needed to play."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        gameView = GameView(this) { score, gameOver ->
            scoreView.text = "Score: $score"
            messageView.text = if (gameOver) "Game over — tap to restart" else "Keep the ball up with your feet"
        }
        scoreView = TextView(this).apply {
            text = "Score: 0"
            textSize = 24f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(28, 28, 28, 12)
        }
        messageView = TextView(this).apply {
            text = "Keep the ball up with your feet"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(28, 12, 28, 28)
        }

        val root = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(gameView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(scoreView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            addView(messageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        analyzePose(imageProxy)
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzePose(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> gameView.updatePose(pose, image.width, image.height) }
            .addOnCompleteListener { imageProxy.close() }
    }
}

private class GameView(
    context: android.content.Context,
    private val onScoreChanged: (score: Int, gameOver: Boolean) -> Unit
) : View(context) {
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.WHITE
    }
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) }

    private var ballX = 0f
    private var ballY = 0f
    private var ballRadius = 42f
    private var velocityY = 12f
    private var velocityX = 0f
    private var score = 0
    private var gameOver = false
    private var lastTick = 0L
    private var footX: Float? = null
    private var footY: Float? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        resetGame()
    }

    fun updatePose(pose: Pose, imageWidth: Int, imageHeight: Int) {
        val feet = listOfNotNull(
            pose.getPoseLandmark(LEFT_FOOT_INDEX),
            pose.getPoseLandmark(RIGHT_FOOT_INDEX),
            pose.getPoseLandmark(LEFT_ANKLE),
            pose.getPoseLandmark(RIGHT_ANKLE)
        )
        if (feet.isEmpty() || width == 0 || height == 0) return

        val bestFoot = feet.maxBy { it.position.y }
        val mirroredX = width - (bestFoot.position.x / imageWidth.toFloat()) * width
        val mappedY = (bestFoot.position.y / imageHeight.toFloat()) * height
        footX = mirroredX
        footY = mappedY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        tick()

        footX?.let { x ->
            footY?.let { y ->
                canvas.drawCircle(x, y, 26f, footPaint)
            }
        }

        canvas.drawCircle(ballX, ballY, ballRadius, ballPaint)
        canvas.drawCircle(ballX, ballY, ballRadius, ringPaint)

        if (!gameOver) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && gameOver) {
            resetGame()
            invalidate()
            return true
        }
        return true
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (lastTick == 0L) lastTick = now
        val dt = ((now - lastTick).coerceAtMost(32L)) / 16f
        lastTick = now

        if (gameOver || width == 0 || height == 0) return

        ballX += velocityX * dt
        ballY += velocityY * dt
        velocityY += 0.65f * dt

        if (ballX < ballRadius || ballX > width - ballRadius) velocityX = -velocityX
        ballX = ballX.coerceIn(ballRadius, width - ballRadius)

        val fx = footX
        val fy = footY
        if (fx != null && fy != null) {
            val distance = hypot(ballX - fx, ballY - fy)
            val movingDown = velocityY > 0
            if (movingDown && distance < ballRadius + 52f) {
                velocityY = -22f - score.coerceAtMost(20) * 0.35f
                velocityX = ((ballX - fx) / 9f).coerceIn(-12f, 12f)
                ballY = fy - ballRadius - 52f
                score += 1
                onScoreChanged(score, false)
            }
        }

        if (ballY > height - ballRadius) {
            gameOver = true
            ballY = height - ballRadius
            onScoreChanged(score, true)
        }
    }

    private fun resetGame() {
        ballRadius = (width.coerceAtLeast(1) * 0.055f).coerceIn(34f, 58f)
        ballX = if (width > 0) width / 2f else 0f
        ballY = if (height > 0) height * 0.25f else 0f
        velocityY = 10f
        velocityX = Random.nextFloat() * 8f - 4f
        score = 0
        gameOver = false
        lastTick = 0L
        onScoreChanged(score, false)
    }
}
