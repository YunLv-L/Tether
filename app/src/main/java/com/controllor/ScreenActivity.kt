package com.tether.controller

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ScreenActivity : ComponentActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvResolution: TextView
    private lateinit var tvFps: TextView
    private lateinit var btnRotate: ImageButton
    private lateinit var btnClose: ImageButton

    private var socket: Socket? = null
    private val isRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var currentBitmap: Bitmap? = null
    
    private var currentOrientation = 0

    private var scaleFactor = 1.0f
    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private var frameCount = 0
    private var lastFpsUpdate = 0L

    private var qualityLevel = 1
    
    private var renderThread: Thread? = null

    companion object {
        private const val TAG = "ScreenActivity"
        private const val STREAM_PORT = 5557
        private const val TIMEOUT = 5000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)

        surfaceView = findViewById(R.id.surfaceView)
        tvResolution = findViewById(R.id.tvResolution)
        tvFps = findViewById(R.id.tvFps)
        btnRotate = findViewById(R.id.btnRotate)
        btnClose = findViewById(R.id.btnClose)

        qualityLevel = intent.getIntExtra("quality", 1)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.3f, 3.0f)
                return true
            }
        })

        surfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        offsetX += dx
                        offsetY += dy
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            scaleDetector?.onTouchEvent(event)
            true
        }

        btnRotate.setOnClickListener {
            currentOrientation = if (currentOrientation == 0) 1 else 0
            requestedOrientation = if (currentOrientation == 0) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        btnClose.setOnClickListener {
            finish()
        }

        val ip = intent.getStringExtra("ip") ?: run {
            finish()
            return
        }

        tvResolution.text = "连接中..."
        tvFps.text = "0 fps"

        startRenderThread()

        lifecycleScope.launch {
            connectToStream(ip)
        }
    }

    private fun startRenderThread() {
        renderThread = Thread {
            while (isRunning.get()) {
                val bitmap = currentBitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    renderBitmap(bitmap)
                }
                Thread.sleep(16)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun renderBitmap(bitmap: Bitmap) {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            try {
                val canvasWidth = canvas.width
                val canvasHeight = canvas.height
                val bmpWidth = bitmap.width
                val bmpHeight = bitmap.height

                if (canvasWidth > 0 && canvasHeight > 0 && bmpWidth > 0 && bmpHeight > 0) {
                    val scaleX = canvasWidth.toFloat() / bmpWidth.toFloat()
                    val scaleY = canvasHeight.toFloat() / bmpHeight.toFloat()
                    val baseScale = maxOf(scaleX, scaleY)
                    val finalScale = baseScale * scaleFactor

                    val dstWidth = (bmpWidth * finalScale).toInt()
                    val dstHeight = (bmpHeight * finalScale).toInt()

                    val limitX = dstWidth * 0.5f
                    val limitY = dstHeight * 0.5f
                    
                    val currentOffsetX = offsetX
                    val currentOffsetY = offsetY
                    val clampedOffsetX = currentOffsetX.coerceIn(-limitX, limitX)
                    val clampedOffsetY = currentOffsetY.coerceIn(-limitY, limitY)

                    val centerOffsetX = (canvasWidth - dstWidth) / 2f + clampedOffsetX
                    val centerOffsetY = (canvasHeight - dstHeight) / 2f + clampedOffsetY

                    val left = centerOffsetX.toInt()
                    val top = centerOffsetY.toInt()

                    canvas.drawColor(android.graphics.Color.BLACK)

                    val rectLeft = maxOf(left, 0)
                    val rectTop = maxOf(top, 0)
                    val rectRight = minOf(left + dstWidth, canvasWidth)
                    val rectBottom = minOf(top + dstHeight, canvasHeight)

                    if (rectRight > rectLeft && rectBottom > rectTop) {
                        val srcLeft = if (left < 0) (-left.toFloat() / finalScale).toInt() else 0
                        val srcTop = if (top < 0) (-top.toFloat() / finalScale).toInt() else 0
                        val srcRight = srcLeft + ((rectRight - rectLeft) / finalScale).toInt()
                        val srcBottom = srcTop + ((rectBottom - rectTop) / finalScale).toInt()

                        val srcRect = android.graphics.Rect(
                            srcLeft.coerceAtLeast(0),
                            srcTop.coerceAtLeast(0),
                            srcRight.coerceAtMost(bmpWidth),
                            srcBottom.coerceAtMost(bmpHeight)
                        )
                        val dstRect = android.graphics.Rect(rectLeft, rectTop, rectRight, rectBottom)

                        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "渲染异常", e)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private suspend fun connectToStream(ip: String) {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket(ip, STREAM_PORT).apply {
                    soTimeout = TIMEOUT
                    tcpNoDelay = true
                    receiveBufferSize = 8192
                    sendBufferSize = 8192
                }
                val inputStream = DataInputStream(socket?.getInputStream())

                val sizeInfo = StringBuilder()
                var ch: Char
                while (true) {
                    ch = inputStream.read().toChar()
                    if (ch == '\n') break
                    sizeInfo.append(ch)
                }
                val parts = sizeInfo.toString().split("|")
                var screenWidth = 0
                var screenHeight = 0
                if (parts.size == 2) {
                    screenWidth = parts[0].toInt()
                    screenHeight = parts[1].toInt()
                    val finalWidth = screenWidth
                    val finalHeight = screenHeight
                    handler.post {
                        tvResolution.text = "${finalWidth}x${finalHeight}"
                    }
                }

                isRunning.set(true)

                while (isRunning.get()) {
                    try {
                        val lengthBytes = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            read += inputStream.read(lengthBytes, read, 4 - read)
                        }
                        val imageLength = lengthBytes[0].toInt() and 0xFF or
                                (lengthBytes[1].toInt() and 0xFF shl 8) or
                                (lengthBytes[2].toInt() and 0xFF shl 16) or
                                (lengthBytes[3].toInt() and 0xFF shl 24)

                        if (imageLength <= 0 || imageLength > 5 * 1024 * 1024) continue

                        val imageData = ByteArray(imageLength)
                        read = 0
                        while (read < imageLength) {
                            read += inputStream.read(imageData, read, imageLength - read)
                        }

                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        if (bitmap != null) {
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsUpdate >= 1000) {
                                val fps = frameCount
                                handler.post {
                                    tvFps.text = "${fps} fps"
                                }
                                frameCount = 0
                                lastFpsUpdate = now
                            }
                            
                            val oldBitmap = currentBitmap
                            currentBitmap = bitmap
                            oldBitmap?.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "接收异常", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                handler.post {
                    Toast.makeText(this@ScreenActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } finally {
                socket?.close()
                isRunning.set(false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        socket?.close()
        renderThread?.interrupt()
        currentBitmap?.recycle()
        currentBitmap = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}