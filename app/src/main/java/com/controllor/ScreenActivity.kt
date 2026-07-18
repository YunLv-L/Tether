package com.tether.controller

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.Socket

class ScreenActivity : ComponentActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvFps: TextView
    private lateinit var seekZoom: SeekBar
    private lateinit var spinnerQuality: Spinner
    private lateinit var bottomControls: View
    private lateinit var btnRotate: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton

    private var socket: Socket? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var currentBitmap: Bitmap? = null
    private var zoomLevel = 1.0f
    private var currentOrientation = 0 // 0=竖屏, 1=横屏
    private var frameCount = 0
    private var lastFpsUpdate = 0L

    companion object {
        private const val TAG = "ScreenActivity"
        private const val STREAM_PORT = 5557
        private const val TIMEOUT = 5000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)

        initViews()
        setupControls()

        val ip = intent.getStringExtra("ip") ?: run {
            finish()
            return
        }

        tvStatus.text = "连接 $ip ..."
        lifecycleScope.launch {
            connectToStream(ip)
        }
    }

    private fun initViews() {
        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        tvResolution = findViewById(R.id.tvResolution)
        tvFps = findViewById(R.id.tvFps)
        seekZoom = findViewById(R.id.seekZoom)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        bottomControls = findViewById(R.id.bottomControls)
        btnRotate = findViewById(R.id.btnRotate)
        btnSettings = findViewById(R.id.btnSettings)
        btnClose = findViewById(R.id.btnClose)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)

        // 质量选项适配器
        val qualityAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.quality_options,
            android.R.layout.simple_spinner_item
        )
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerQuality.adapter = qualityAdapter
    }

    private fun setupControls() {
        // 旋转
        btnRotate.setOnClickListener {
            currentOrientation = if (currentOrientation == 0) 1 else 0
            requestedOrientation = if (currentOrientation == 0) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        // 设置（显示/隐藏底部控制栏）
        btnSettings.setOnClickListener {
            bottomControls.visibility = if (bottomControls.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        // 关闭
        btnClose.setOnClickListener {
            finish()
        }

        // 缩放 SeekBar
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    zoomLevel = progress.toFloat() / 100f
                    if (zoomLevel < 0.1f) zoomLevel = 0.1f
                    updateDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 放大
        btnZoomIn.setOnClickListener {
            zoomLevel = (zoomLevel + 0.1f).coerceAtMost(3.0f)
            seekZoom.progress = (zoomLevel * 100).toInt()
            updateDisplay()
        }

        // 缩小
        btnZoomOut.setOnClickListener {
            zoomLevel = (zoomLevel - 0.1f).coerceAtLeast(0.1f)
            seekZoom.progress = (zoomLevel * 100).toInt()
            updateDisplay()
        }

        // 画质切换
        spinnerQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 画质切换需要重新连接，这里只做提示
                Toast.makeText(this@ScreenActivity, "画质将在下次连接生效", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private suspend fun connectToStream(ip: String) {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket(ip, STREAM_PORT)
                socket?.soTimeout = TIMEOUT
                val inputStream = DataInputStream(socket?.getInputStream())

                // 读取屏幕尺寸
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
                        tvStatus.text = "📺 接收中"
                    }
                }

                isRunning = true
                var frameCount = 0
                var lastFpsTime = System.currentTimeMillis()

                while (isRunning) {
                    try {
                        // 读取长度 (4字节)
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
                            if (now - lastFpsTime >= 1000) {
                                val fps = frameCount
                                handler.post {
                                    tvFps.text = "${fps}fps"
                                }
                                frameCount = 0
                                lastFpsTime = now
                            }
                            handler.post {
                                currentBitmap = bitmap
                                updateDisplay()
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
            }
        }
    }

    private fun updateDisplay() {
        val bitmap = currentBitmap ?: return
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            val canvasWidth = canvas.width
            val canvasHeight = canvas.height
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            if (canvasWidth <= 0 || canvasHeight <= 0 || bmpWidth <= 0 || bmpHeight <= 0) {
                holder.unlockCanvasAndPost(canvas)
                return
            }

            // 计算缩放
            val scaleX = canvasWidth.toFloat() / bmpWidth.toFloat()
            val scaleY = canvasHeight.toFloat() / bmpHeight.toFloat()
            val baseScale = maxOf(scaleX, scaleY)
            val finalScale = baseScale * zoomLevel

            val dstWidth = (bmpWidth * finalScale).toInt()
            val dstHeight = (bmpHeight * finalScale).toInt()
            val left = (canvasWidth - dstWidth) / 2
            val top = (canvasHeight - dstHeight) / 2

            // 清空画布
            canvas.drawColor(android.graphics.Color.BLACK)

            // 裁剪防止越界
            val rectLeft = maxOf(left, 0)
            val rectTop = maxOf(top, 0)
            val rectRight = minOf(left + dstWidth, canvasWidth)
            val rectBottom = minOf(top + dstHeight, canvasHeight)

            if (rectRight > rectLeft && rectBottom > rectTop) {
                // 计算源图对应的裁剪区域
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

            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        socket?.close()
        currentBitmap?.recycle()
        currentBitmap = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转后刷新画面
        handler.postDelayed({ updateDisplay() }, 100)
    }
}