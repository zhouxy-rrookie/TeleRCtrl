package com.ek.blectr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

class UsbSerialController(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ek.blectr.USB_PERMISSION"
        const val DEFAULT_BAUD_RATE = 115200
    }

    private val usbManager: UsbManager =
        activity.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile
    private var serialPort: UsbSerialPort? = null
    private var connectedDevice: UsbDevice? = null

    private var permissionRequestInFlight: Boolean = false
    private var pendingDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionRequestInFlight = false
                    @Suppress("DEPRECATION")
                    val device =
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectDevice(it) }
                    } else {
                        onStatus("USB 权限被拒绝")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device =
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == connectedDevice) {
                        disconnect()
                        onStatus("USB 设备已拔出")
                    }
                }
            }
        }
    }

    fun onStart() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            activity, usbReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun onStop() {
        try {
            activity.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
    }

    fun onDestroy() {
        disconnect()
    }

    fun getAvailableSerialDevices(): List<UsbDevice> {
        val allDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return allDrivers
            .map { it.device }
            .filter { !isVideoDevice(it) }
    }

    fun requestPermissionAndConnect(device: UsbDevice) {
        if (permissionRequestInFlight) {
            onStatus("正在等待 USB 授权，请先处理系统弹窗")
            return
        }
        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val permissionIntent = PendingIntent.getBroadcast(
                activity, 0, Intent(ACTION_USB_PERMISSION), flags,
            )
            permissionRequestInFlight = true
            pendingDevice = device
            usbManager.requestPermission(device, permissionIntent)
            onStatus("正在请求 USB 授权，请在弹窗点击允许")
        } else {
            connectDevice(device)
        }
    }

    fun write(data: ByteArray): Boolean {
        val port = serialPort ?: return false
        return try {
            port.write(data, 500)
            true
        } catch (e: IOException) {
            onStatus("USB 发送失败: ${e.message ?: "未知错误"}")
            disconnect()
            false
        }
    }

    fun disconnect() {
        try {
            serialPort?.close()
        } catch (_: Exception) {
        }
        serialPort = null
        connectedDevice = null
        permissionRequestInFlight = false
        pendingDevice = null
    }

    fun isConnected(): Boolean = serialPort != null

    private fun connectDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IOException("无法打开 USB 设备")
            val driver = UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .firstOrNull { it.device == device }
                ?: throw IOException("不支持的 USB 串口设备")
            val port = driver.ports.firstOrNull()
                ?: throw IOException("没有可用端口")
            port.open(connection)
            port.setParameters(
                DEFAULT_BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            serialPort = port
            connectedDevice = device
            permissionRequestInFlight = false
            pendingDevice = null
            onStatus("已连接: ${device.deviceName}")
        } catch (e: Exception) {
            permissionRequestInFlight = false
            pendingDevice = null
            onStatus("USB 连接失败: ${e.message ?: "未知错误"}")
        }
    }

    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
