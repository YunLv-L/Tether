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
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.Socket

class ScreenActivity : ComponentActivity() {
    private lateinit var imageView: ImageView
    private lateinit var tvResolution: TextView
    private lateinit var tvFps: TextView
    private lateinit var btnRotate: ImageButton
    private lateinit var btnClose: ImageButton

    private var socket: Socket? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    // 缩放相关
    private var scaleFactor = 1.0f
    private var scaleDetector: ScaleGestureDetector? = null

    // FPS 统计
    private var frameCount = 0
    private var lastFpsUpdate = 0L

    companion object {
        private const val TAG = "ScreenActivity"
        private const val STREAM_PORT = 5557
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)

        imageView = findViewById(R.id.imageView)
        tvResolution = findViewById(R.id.tvResolution)
        tvFps = findViewById(R.id.tvFps)
        btnRotate = findViewById(R.id.btnRotate)
        btnClose = findViewById(R.id.btnClose)

        // 双指缩放
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.3f, 3.0f)
                imageView.scaleX = scaleFactor
                imageView.scaleY = scaleFactor
                return true
            }
        })

        // 触摸事件（缩放 + 拖拽）
        var lastTouchX = 0f
        var lastTouchY = 0f
        imageView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        imageView.translationX += dx
                        imageView.translationY += dy
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            scaleDetector?.onTouchEvent(event)
            true
        }

        // 横竖屏切换
        var currentOrientation = 0
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

        lifecycleScope.launch {
            connectToStream(ip)
        }
    }

    private suspend fun connectToStream(ip: String) {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket(ip, STREAM_PORT).apply {
                    tcpNoDelay = true
                    receiveBufferSize = 8192
                    sendBufferSize = 8192
                }
                val inputStream = DataInputStream(socket!!.getInputStream())

                // 读取分辨率
                val sizeInfo = StringBuilder()
                var ch: Char
                while (true) {
                    ch = inputStream.read().toChar()
                    if (ch == '\n') break
                    sizeInfo.append(ch)
                }
                val parts = sizeInfo.toString().split("|")
                if (parts.size == 2) {
                    val finalWidth = parts[0]
                    val finalHeight = parts[1]
                    handler.post {
                        tvResolution.text = "${finalWidth}x${finalHeight}"
                    }
                }

                isRunning = true

                while (isRunning) {
                    try {
                        // 读取长度
                        val lengthBytes = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            read += inputStream.read(lengthBytes, read, 4 - read)
                        }
                        val imageLength = (lengthBytes[0].toInt() and 0xFF) or
                                (lengthBytes[1].toInt() and 0xFF shl 8) or
                                (lengthBytes[2].toInt() and 0xFF shl 16) or
                                (lengthBytes[3].toInt() and 0xFF shl 24)

                        if (imageLength <= 0 || imageLength > 5 * 1024 * 1024) continue

                        // 读取图像数据
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
                            handler.post {
                                imageView.setImageBitmap(bitmap)
                                // 维持缩放比例
                                imageView.scaleX = scaleFactor
                                imageView.scaleY = scaleFactor
                            }
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
                isRunning = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        socket?.close()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转后重置缩放
        imageView.scaleX = scaleFactor
        imageView.scaleY = scaleFactor
    }
}