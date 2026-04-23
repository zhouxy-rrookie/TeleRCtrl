package com.ek.blectr

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private lateinit var tvStatus: TextView
    private lateinit var btnSelectDevice: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnVideoMenu: Button
    private lateinit var btnModeSelect: Button
    private lateinit var btnUsbDiag: Button
    private lateinit var btnSidebarToggle: Button
    private lateinit var panelSidebar: View
    private lateinit var guideLeftEnd: Guideline
    private lateinit var tvModeStatus: TextView

    private lateinit var drivePad: ThrottleSteeringView
    private lateinit var joystickRight: JoystickView

    private lateinit var cameraView: android.view.TextureView
    private lateinit var uvcController: UvcPreviewController

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var selectedDevice: BluetoothDevice? = null
    private var axisX: Float = 0f
    private var axisY: Float = 0f
    private var axisYaw: Float = 0f
    private var lastXValue: Int = 0
    private var lastYValue: Int = 0
    private var lastYawValue: Int = 0
    private var packetSequence: Int = 0
    private var strafeStickActive: Boolean = false
    private var drivePadActive: Boolean = false
    private var motionStreaming: Boolean = false
    private val motionHandler = Handler(Looper.getMainLooper())
    private var sidebarExpanded: Boolean = false
    private var selectedMode: Int = 1

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQ_BT_PERMISSIONS = 1001
        private const val MOTION_STREAM_INTERVAL_MS = 50L
        private const val AXIS_SCALE = 100
        private const val FRAME_HEADER_A = 0xAA
        private const val FRAME_HEADER_B = 0x55
        private const val FRAME_TAIL = 0x0D
        private const val MODE_MARKER = 0x7F
    }

    private val motionStreamRunnable = object : Runnable {
        override fun run() {
            if (!motionStreaming) {
                return
            }
            dispatchMotionCommand(forceSend = true)
            motionHandler.postDelayed(this, MOTION_STREAM_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        uvcController = UvcPreviewController(this, cameraView) { message ->
            runOnUiThread { updateStatus(message) }
        }
        bindClickListeners()
        requestBluetoothPermissionsIfNeeded()
        setSidebarExpanded(false)
        updateModeStatus()
        updateStatus("未连接")
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnVideoMenu = findViewById(R.id.btnVideoMenu)
        btnModeSelect = findViewById(R.id.btnModeSelect)
        btnUsbDiag = findViewById(R.id.btnUsbDiag)
        btnSidebarToggle = findViewById(R.id.btnSidebarToggle)
        panelSidebar = findViewById(R.id.panelSidebar)
        guideLeftEnd = findViewById(R.id.guideLeftEnd)
        tvModeStatus = findViewById(R.id.tvModeStatus)

        cameraView = findViewById(R.id.camera_view)
        drivePad = findViewById(R.id.drivePad)
        joystickRight = findViewById(R.id.joystickRight)
    }

    private fun bindClickListeners() {
        applyGamePadMotion(btnVideoMenu, 1.04f)
        applyGamePadMotion(btnSelectDevice, 1.04f)
        applyGamePadMotion(btnConnect, 1.04f)
        applyGamePadMotion(btnDisconnect, 1.04f)
        applyGamePadMotion(btnModeSelect, 1.04f)
        applyGamePadMotion(btnUsbDiag, 1.04f)
        applyGamePadMotion(btnSidebarToggle, 1.04f)

        btnSelectDevice.setOnClickListener {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissionsIfNeeded()
                return@setOnClickListener
            }
            showPairedDevicesDialog()
        }

        btnConnect.setOnClickListener {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissionsIfNeeded()
                return@setOnClickListener
            }
            connectSelectedDevice()
        }

        btnDisconnect.setOnClickListener { disconnect() }
        btnVideoMenu.setOnClickListener { showUvcDevicesDialog() }
        btnVideoMenu.setOnLongClickListener {
            showUsbDiagnosticsDialog()
            true
        }
        btnModeSelect.setOnClickListener { showModeSelectDialog() }
        btnUsbDiag.setOnClickListener { showUsbDiagnosticsDialog() }
        btnSidebarToggle.setOnClickListener { setSidebarExpanded(!sidebarExpanded) }

        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchChassis),
                findViewById(R.id.switchTask),
            ),
            defaultIndex = 0,
        )
        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchChannel1),
                findViewById(R.id.switchChannel2),
            ),
            defaultIndex = 0,
        )
        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchZone1),
                findViewById(R.id.switchZone2),
                findViewById(R.id.switchZone3),
            ),
            defaultIndex = 0,
        )

        joystickRight.listener = object : JoystickView.Listener {
            override fun onMove(normalizedX: Float, normalizedY: Float) {
                strafeStickActive = true
                axisX = normalizedX
                ensureMotionStreaming()
            }

            override fun onRelease() {
                strafeStickActive = false
                axisX = 0f
                stopMotionStreamingIfIdle()
            }
        }

        drivePad.listener = object : ThrottleSteeringView.Listener {
            override fun onMove(normalizedSteering: Float, normalizedThrottle: Float) {
                drivePadActive = true
                axisY = normalizedThrottle
                axisYaw = normalizedSteering
                ensureMotionStreaming()
            }

            override fun onRelease() {
                drivePadActive = false
                axisY = 0f
                axisYaw = 0f
                stopMotionStreamingIfIdle()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        uvcController.onStart()
    }

    override fun onStop() {
        stopMotionStreaming(forceStopCommand = false)
        uvcController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMotionStreaming(forceStopCommand = false)
        uvcController.onDestroy()
        disconnect()
        ioExecutor.shutdownNow()
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BT_PERMISSIONS)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            updateStatus("设备不支持蓝牙")
            return
        }

        if (!adapter.isEnabled) {
            updateStatus("请先打开手机蓝牙")
            return
        }

        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            updateStatus("没有已配对设备，请先在系统蓝牙中配对")
            return
        }

        val display = paired.map { device ->
            "${device.name ?: "未知设备"} (${device.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择蓝牙设备")
            .setItems(display) { _, which ->
                selectedDevice = paired[which]
                updateStatus("已选择: ${paired[which].name ?: paired[which].address}")
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        val device = selectedDevice
        if (device == null) {
            updateStatus("请先选择设备")
            return
        }

        updateStatus("连接中...")
        ioExecutor.execute {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()

                socket = newSocket
                outputStream = newSocket.outputStream

                runOnUiThread {
                    updateStatus("已连接: ${device.name ?: device.address}")
                }
            } catch (e: IOException) {
                runOnUiThread {
                    updateStatus("连接失败: ${e.message ?: "未知错误"}")
                }
                disconnect()
            }
        }
    }

    private fun sendPacket(packet: ByteArray) {
        val os = outputStream
        if (os == null) {
            updateStatus("未连接，无法发送指令")
            return
        }

        ioExecutor.execute {
            try {
                os.write(packet)
                os.flush()
            } catch (e: IOException) {
                runOnUiThread {
                    updateStatus("发送失败: ${e.message ?: "未知错误"}")
                }
                disconnect()
            }
        }
    }

    private fun showUvcDevicesDialog() {
        val devices = uvcController.getAvailableCameras()
        if (devices.isEmpty()) {
            updateStatus("未检测到 UVC 设备")
            return
        }

        if (devices.size == 1) {
            uvcController.connectCamera(devices[0])
            return
        }

        val display = devices.map { device ->
            formatUvcDeviceLabel(device)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择图传设备")
            .setItems(display) { _, which ->
                uvcController.connectCamera(devices[which])
            }
            .show()
    }

    private fun formatUvcDeviceLabel(device: UsbDevice): String {
        val vendorId = String.format(Locale.US, "%04X", device.vendorId)
        val productId = String.format(Locale.US, "%04X", device.productId)
        return "${device.deviceName} (VID:$vendorId PID:$productId)"
    }

    private fun showModeSelectDialog() {
        val modeLabels = (1..15).map { mode -> "模式 $mode" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mode_select_title))
            .setSingleChoiceItems(modeLabels, selectedMode - 1) { dialog, which ->
                val mode = which + 1
                selectedMode = mode
                updateModeStatus()
                sendModePacket(mode)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUsbDiagnosticsDialog() {
        val report = uvcController.getUsbDiagnosticsReport()
        AlertDialog.Builder(this)
            .setTitle("USB 设备诊断")
            .setMessage(report)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun disconnect() {
        ioExecutor.execute {
            try {
                outputStream?.close()
            } catch (_: IOException) {
            }

            try {
                socket?.close()
            } catch (_: IOException) {
            }

            outputStream = null
            socket = null

            runOnUiThread {
                updateStatus("已断开连接")
            }
        }
    }

    private fun applyGamePadMotion(view: View, pressedScale: Float) {
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchedView.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .alpha(0.92f)
                        .setDuration(90)
                        .start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(110)
                        .start()
                }
            }
            false
        }
    }

    private fun setSidebarExpanded(expanded: Boolean) {
        sidebarExpanded = expanded
        panelSidebar.visibility = if (expanded) View.VISIBLE else View.GONE

        val params = guideLeftEnd.layoutParams as ConstraintLayout.LayoutParams
        params.guidePercent = if (expanded) 0.18f else 0.02f
        guideLeftEnd.layoutParams = params

        btnSidebarToggle.text = if (expanded) getString(R.string.sidebar_close) else getString(R.string.sidebar_open)
    }

    private fun configureSegmentGroup(options: List<TextView>, defaultIndex: Int) {
        options.forEachIndexed { index, option ->
            option.isSelected = index == defaultIndex
            applyGamePadMotion(option, 1.02f)
            option.setOnClickListener {
                options.forEachIndexed { selectedIndex, selectedOption ->
                    selectedOption.isSelected = selectedIndex == index
                }
            }
        }
    }

    private fun ensureMotionStreaming() {
        if (motionStreaming) {
            return
        }
        motionStreaming = true
        motionHandler.post(motionStreamRunnable)
    }

    private fun stopMotionStreamingIfIdle() {
        if (drivePadActive || strafeStickActive) {
            return
        }
        stopMotionStreaming(forceStopCommand = true)
    }

    private fun stopMotionStreaming(forceStopCommand: Boolean) {
        motionStreaming = false
        motionHandler.removeCallbacks(motionStreamRunnable)
        if (forceStopCommand) {
            dispatchMotionCommand(forceSend = true)
        }
    }

    private fun dispatchMotionCommand(forceSend: Boolean = false) {
        val deadZone = 0.14f
        val x = toAxisInt(if (abs(axisX) < deadZone) 0f else axisX)
        val y = toAxisInt(if (abs(axisY) < deadZone) 0f else axisY)
        val yaw = toAxisInt(if (abs(axisYaw) < deadZone) 0f else axisYaw)

        if (!forceSend && x == lastXValue && y == lastYValue && yaw == lastYawValue) {
            return
        }

        val packet = buildMotionPacket(
            x,
            y,
            yaw,
        )
        lastXValue = x
        lastYValue = y
        lastYawValue = yaw
        sendPacket(packet)
    }

    private fun toAxisInt(value: Float): Int {
        return (value.coerceIn(-1f, 1f) * AXIS_SCALE).roundToInt().coerceIn(-AXIS_SCALE, AXIS_SCALE)
    }

    private fun sendModePacket(mode: Int) {
        val normalizedMode = mode.coerceIn(1, 15)
        val packet = buildModePacket(normalizedMode)
        sendPacket(packet)
        updateStatus(getString(R.string.mode_sent_format, normalizedMode))
    }

    private fun buildMotionPacket(x: Int, y: Int, yaw: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = FRAME_HEADER_A.toByte()
        frame[1] = FRAME_HEADER_B.toByte()
        frame[2] = x.toByte()
        frame[3] = y.toByte()
        frame[4] = yaw.toByte()
        frame[5] = (packetSequence and 0xFF).toByte()
        frame[6] = computeChecksum(frame)
        frame[7] = FRAME_TAIL.toByte()
        packetSequence = (packetSequence + 1) and 0xFF
        return frame
    }

    private fun buildModePacket(mode: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = FRAME_HEADER_A.toByte()
        frame[1] = FRAME_HEADER_B.toByte()
        frame[2] = MODE_MARKER.toByte()
        frame[3] = mode.toByte()
        frame[4] = 0
        frame[5] = (packetSequence and 0xFF).toByte()
        frame[6] = computeChecksum(frame)
        frame[7] = FRAME_TAIL.toByte()
        packetSequence = (packetSequence + 1) and 0xFF
        return frame
    }

    private fun computeChecksum(frame: ByteArray): Byte {
        var sum = 0
        for (index in 0..5) {
            sum = (sum + (frame[index].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = getString(R.string.status_format, msg)
    }

    private fun updateModeStatus() {
        tvModeStatus.text = getString(R.string.mode_current_format, selectedMode)
    }
}
