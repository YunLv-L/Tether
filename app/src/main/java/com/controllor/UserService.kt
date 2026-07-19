package com.tether.controller

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class UserService : IUserService.Stub() {

    companion object {
        private const val TAG = "UserService"
    }

    override fun executeCommand(command: String): String {
        Log.d(TAG, "执行命令: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            process.waitFor()
            reader.close()
            errorReader.close()
            if (error.isNotEmpty()) {
                Log.e(TAG, "命令错误: $error")
            }
            output
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败", e)
            ""
        }
    }

    override fun ping() {
        Log.d(TAG, "UserService ping 成功")
    }
}