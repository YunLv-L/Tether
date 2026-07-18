package com.tether.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private var socket: Socket? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ScreenActivity"
        private const val STREAM_PORT = 5557
        private const val TIMEOUT = 5000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)

        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        val ip = intent.getStringExtra("ip") ?: run {
            finish()
            return
        }

        tvStatus.text = "连接 $ip ..."

        lifecycleScope.launch {
            connectToStream(ip)
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
                    handler.post {
                        tvStatus.text = "📺 ${screenWidth}x${screenHeight}"
                    }
                }

                isRunning = true

                // 初始化 SurfaceView
                handler.post {
                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // 尺寸由接收到的图像决定
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            isRunning = false
                        }
                    })
                }

                // 接收图像循环
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

                        // 解码并显示
                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        if (bitmap != null) {
                            handler.post {
                                displayBitmap(bitmap)
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

    private fun displayBitmap(bitmap: Bitmap) {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            // 按比例缩放填满屏幕
            val canvasWidth = canvas.width
            val canvasHeight = canvas.height
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            val scaleX = canvasWidth.toFloat() / bmpWidth.toFloat()
            val scaleY = canvasHeight.toFloat() / bmpHeight.toFloat()
            val scale = minOf(scaleX, scaleY)

            val dstWidth = (bmpWidth * scale).toInt()
            val dstHeight = (bmpHeight * scale).toInt()
            val left = (canvasWidth - dstWidth) / 2
            val top = (canvasHeight - dstHeight) / 2

            canvas.drawBitmap(bitmap, null, android.graphics.Rect(left, top, left + dstWidth, top + dstHeight), null)
            holder.unlockCanvasAndPost(canvas)
        }
        bitmap.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        socket?.close()
    }
}