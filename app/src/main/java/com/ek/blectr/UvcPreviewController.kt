package com.ek.blectr

import android.annotation.SuppressLint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera

class UvcPreviewController(
    private val activity: AppCompatActivity,
    private val previewView: TextureView,
    private val onStatus: (String) -> Unit,
) {

    companion object {
    }

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var preferredVendorId: Int? = null
    private var preferredProductId: Int? = null
    private var preferredDeviceName: String? = null
    private var pendingDeviceName: String? = null
    private var autoConnectEnabled: Boolean = false
    private var permissionRequestInFlight: Boolean = false
    private val previewSizes = listOf(
        1280 to 720,
        1024 to 768,
        960 to 720,
        800 to 600,
        640 to 480,
        320 to 240,
    )

    private val onDeviceConnectListener = object : OnDeviceConnectListener {
        @SuppressLint("MissingPermission")
        override fun onAttach(device: UsbDevice) {
            if (!isLikelyUvcDevice(device)) {
                return
            }
            Toast.makeText(activity, "检测到 UVC 设备", Toast.LENGTH_SHORT).show()
            onStatus("检测到 UVC 设备")
        }

        @SuppressLint("MissingPermission")
        override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
            permissionRequestInFlight = false
            try {
                val camera = UVCCamera()
                camera.open(ctrlBlock)
                val selected = selectCompatiblePreview(camera)
                    ?: throw IllegalStateException("未找到兼容分辨率")
                uvcCamera?.destroy()
                uvcCamera = camera
                rememberPreferredDevice(device)
                onStatus("图传已连接: ${device.deviceName} ${selected.first}x${selected.second}")
            } catch (e: Exception) {
                onStatus("图传打开失败: ${e.message ?: "未知错误"}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
            permissionRequestInFlight = false
            uvcCamera?.destroy()
            uvcCamera = null
            pendingDeviceName = null
            onStatus("图传已断开")
        }

        @SuppressLint("MissingPermission")
        override fun onDettach(device: UsbDevice) {
            Toast.makeText(activity, "UVC 设备已移除", Toast.LENGTH_SHORT).show()
            permissionRequestInFlight = false
            pendingDeviceName = null
            onStatus("UVC 设备已移除")
        }

        override fun onCancel(device: UsbDevice) {
            permissionRequestInFlight = false
            pendingDeviceName = null
            onStatus("图传连接已取消（请在USB授权弹窗点允许）")
        }
    }

    init {
        usbMonitor = USBMonitor(activity, onDeviceConnectListener)
    }

    fun onStart() {
        usbMonitor?.register()
        if (autoConnectEnabled) {
            autoConnectPreferredOrFirst()
        }
    }

    fun onStop() {
        uvcCamera?.destroy()
        uvcCamera = null
        usbMonitor?.unregister()
    }

    fun onDestroy() {
        uvcCamera?.destroy()
        uvcCamera = null
        usbMonitor?.destroy()
        usbMonitor = null
    }

    fun connectFirstAvailableCamera() {
        val monitor = usbMonitor ?: return
        val device = getUvcDevices(monitor).firstOrNull()
        if (device == null) {
            onStatus("未检测到 UVC 设备（长按图传可看诊断）")
            return
        }
        requestPermissionForDevice(monitor, device)
    }

    fun getAvailableCameras(): List<UsbDevice> {
        val monitor = usbMonitor ?: return emptyList()
        return getUvcDevices(monitor)
    }

    fun getUsbDiagnosticsReport(): String {
        val monitor = usbMonitor ?: return "USBMonitor 未初始化"
        val devices = monitor.deviceList
        if (devices.isEmpty()) {
            return "未检测到任何 USB 设备"
        }

        val builder = StringBuilder()
        builder.append("USB 设备总数: ").append(devices.size).append('\n')
        devices.forEachIndexed { index, device ->
            builder.append("\n[").append(index + 1).append("] ")
                .append(device.deviceName)
                .append("  VID:")
                .append(toHex(device.vendorId))
                .append(" PID:")
                .append(toHex(device.productId))
                .append("  UVC:")
                .append(if (isLikelyUvcDevice(device)) "是" else "否")
                .append('\n')
            builder.append("  class=")
                .append(device.deviceClass)
                .append(" subclass=")
                .append(device.deviceSubclass)
                .append(" protocol=")
                .append(device.deviceProtocol)
                .append(" interfaces=")
                .append(device.interfaceCount)
                .append('\n')
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                builder.append("    - if#")
                    .append(i)
                    .append(" class=")
                    .append(intf.interfaceClass)
                    .append(" subclass=")
                    .append(intf.interfaceSubclass)
                    .append(" protocol=")
                    .append(intf.interfaceProtocol)
                    .append(" [")
                    .append(interfaceTypeName(intf))
                    .append("]\n")
            }
        }
        return builder.toString().trimEnd()
    }

    fun connectCamera(device: UsbDevice) {
        val monitor = usbMonitor ?: return
        if (!isLikelyUvcDevice(device)) {
            onStatus("当前设备不是UVC视频设备")
            return
        }
        requestPermissionForDevice(monitor, device)
    }

    private fun requestPermissionForDevice(monitor: USBMonitor, device: UsbDevice) {
        if (permissionRequestInFlight) {
            onStatus("正在等待USB授权，请先处理系统弹窗")
            return
        }
        pendingDeviceName = device.deviceName
        permissionRequestInFlight = true
        val requestFailed = monitor.requestPermission(device)
        if (requestFailed) {
            permissionRequestInFlight = false
            onStatus("图传权限请求失败")
        } else {
            onStatus("正在请求USB授权，请在弹窗点击允许")
        }
    }

    private fun autoConnectPreferredOrFirst() {
        if (!autoConnectEnabled || uvcCamera != null) {
            return
        }
        val monitor = usbMonitor ?: return
        val devices = getUvcDevices(monitor)
        if (devices.isEmpty()) {
            return
        }
        val preferred = devices.firstOrNull { isPreferredDevice(it) }
        val target = preferred ?: devices.first()
        pendingDeviceName = target.deviceName
        val requestFailed = monitor.requestPermission(target)
        if (!requestFailed) {
            onStatus("正在自动连接图传设备...")
        }
    }

    private fun shouldAutoConnect(device: UsbDevice): Boolean {
        if (pendingDeviceName != null) {
            return false
        }
        return isPreferredDevice(device) || preferredDeviceName == null
    }

    private fun isPreferredDevice(device: UsbDevice): Boolean {
        if (preferredVendorId == null || preferredProductId == null) {
            return false
        }
        return device.vendorId == preferredVendorId &&
            device.productId == preferredProductId &&
            (preferredDeviceName == null || preferredDeviceName == device.deviceName)
    }

    private fun rememberPreferredDevice(device: UsbDevice) {
        preferredVendorId = device.vendorId
        preferredProductId = device.productId
        preferredDeviceName = device.deviceName
        pendingDeviceName = null
    }

    private fun startPreview(camera: UVCCamera) {
        val surfaceTexture = previewView.surfaceTexture
        if (surfaceTexture == null) {
            previewView.post { startPreview(camera) }
            return
        }
        camera.setPreviewTexture(surfaceTexture)
        camera.startPreview()
    }

    private fun selectCompatiblePreview(camera: UVCCamera): Pair<Int, Int>? {
        val formats = listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
        for (format in formats) {
            for ((width, height) in previewSizes) {
                try {
                    camera.setPreviewSize(width, height, format)
                    startPreview(camera)
                    return width to height
                } catch (_: Exception) {
                    // Try next resolution/format until one works.
                }
            }
        }
        return null
    }

    private fun getUvcDevices(monitor: USBMonitor): List<UsbDevice> {
        return monitor.deviceList.filter { isLikelyUvcDevice(it) }
    }

    private fun isLikelyUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) {
            return true
        }
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun interfaceTypeName(usbInterface: UsbInterface): String {
        return when (usbInterface.interfaceClass) {
            UsbConstants.USB_CLASS_VIDEO -> "VIDEO/UVC"
            UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
            UsbConstants.USB_CLASS_COMM -> "COMM"
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_MASS_STORAGE -> "MSC"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR"
            else -> "OTHER"
        }
    }

    private fun toHex(value: Int): String {
        return String.format("%04X", value and 0xFFFF)
    }
}
