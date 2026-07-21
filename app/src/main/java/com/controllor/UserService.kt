package com.tether.controller

import android.os.Binder
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class UserService : Binder() {

    companion object {
        private const val TAG = "UserService"
        private const val DESCRIPTOR = "com.tether.controller.IUserService"
        private const val TRANSACTION_executeCommand = 1
        private const val TRANSACTION_ping = 2
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_executeCommand -> {
                data.enforceInterface(DESCRIPTOR)
                val command = data.readString() ?: ""
                val result = executeCommand(command)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
            TRANSACTION_ping -> {
                data.enforceInterface(DESCRIPTOR)
                ping()
                reply?.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun executeCommand(command: String): String {
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

    private fun ping() {
        Log.d(TAG, "UserService ping 成功")
    }
}