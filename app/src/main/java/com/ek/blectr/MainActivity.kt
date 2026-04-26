package com.ek.blectr

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSelectDevice: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnVideoMenu: Button
    private lateinit var btnModeSelect: Button
    private lateinit var btnUsbDiag: Button
    private lateinit var btnConfig: Button
    private lateinit var btnSidebarToggle: Button
    private lateinit var panelSidebar: View
    private lateinit var guideLeftEnd: Guideline
    private lateinit var tvModeStatus: TextView

    private lateinit var drivePad: ThrottleSteeringView
    private lateinit var joystickRight: JoystickView

    private lateinit var imgLogo: ImageView
    private lateinit var btnToggleTelemetry: Button
    private lateinit var keypadMatrix: View
    private lateinit var panelTelemetry: View
    private val keypadCells = mutableListOf<TextView>()
    private var selectedKeypadIndex: Int = -1
    private lateinit var cameraView: android.view.TextureView
    private lateinit var uvcController: UvcPreviewController
    private lateinit var usbSerialController: UsbSerialController

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var selectedDevice: UsbDevice? = null
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
    private val cellStates = IntArray(12)

    companion object {
        private const val MOTION_STREAM_INTERVAL_MS = 50L
        private const val AXIS_SCALE = 100
        private const val FRAME_HEADER_A = 0xAA
        private const val FRAME_HEADER_B = 0x55
        private const val FRAME_TAIL = 0x0D
        private const val MODE_MARKER = 0x7F
        private const val CONFIG_HEADER = 0xCC
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
        applyLogoVibrancy()
        usbSerialController = UsbSerialController(this) { message ->
            runOnUiThread { updateStatus(message) }
        }
        uvcController = UvcPreviewController(this, cameraView) { message ->
            runOnUiThread { updateStatus(message) }
        }
        bindClickListeners()
        setSidebarExpanded(false)
        updateModeStatus()
        updateStatus("未连接")
        checkForUpdates()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnVideoMenu = findViewById(R.id.btnVideoMenu)
        btnModeSelect = findViewById(R.id.btnModeSelect)
        btnUsbDiag = findViewById(R.id.btnUsbDiag)
        btnConfig = findViewById(R.id.btnConfig)
        btnSidebarToggle = findViewById(R.id.btnSidebarToggle)
        panelSidebar = findViewById(R.id.panelSidebar)
        guideLeftEnd = findViewById(R.id.guideLeftEnd)
        tvModeStatus = findViewById(R.id.tvModeStatus)

        cameraView = findViewById(R.id.camera_view)
        imgLogo = findViewById(R.id.imgLogo)
        btnToggleTelemetry = findViewById(R.id.btnToggleTelemetry)
        keypadMatrix = findViewById(R.id.keypadMatrix)
        panelTelemetry = findViewById(R.id.panelTelemetry)
        drivePad = findViewById(R.id.drivePad)
        joystickRight = findViewById(R.id.joystickRight)

        val keyMatrixIds = intArrayOf(
            R.id.keyMatrix1, R.id.keyMatrix2, R.id.keyMatrix3, R.id.keyMatrixA,
            R.id.keyMatrix4, R.id.keyMatrix5, R.id.keyMatrix6, R.id.keyMatrixB,
            R.id.keyMatrix7, R.id.keyMatrix8, R.id.keyMatrix9, R.id.keyMatrixC,
        )
        keyMatrixIds.forEach { id ->
            val cell = findViewById<TextView>(id)
            cell.setOnClickListener { onKeypadCellClicked(cell) }
            keypadCells.add(cell)
        }
    }

    private fun bindClickListeners() {
        applyGamePadMotion(btnVideoMenu, 1.04f)
        applyGamePadMotion(btnSelectDevice, 1.04f)
        applyGamePadMotion(btnConnect, 1.04f)
        applyGamePadMotion(btnDisconnect, 1.04f)
        applyGamePadMotion(btnModeSelect, 1.04f)
        applyGamePadMotion(btnUsbDiag, 1.04f)
        applyGamePadMotion(btnConfig, 1.04f)
        applyGamePadMotion(btnSidebarToggle, 1.04f)

        btnSelectDevice.setOnClickListener {
            showUsbSerialDevicesDialog()
        }

        btnConnect.setOnClickListener {
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
        btnConfig.setOnClickListener { showConfigDialog() }
        btnToggleTelemetry.setOnClickListener { toggleTelemetryPanel() }
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
                axisYaw = 0f
                if (axisY == 0f) {
                    drivePadActive = false
                }
                stopMotionStreamingIfIdle()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbSerialController.onStart()
        uvcController.onStart()
    }

    override fun onStop() {
        stopMotionStreaming(forceStopCommand = false)
        usbSerialController.onStop()
        uvcController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMotionStreaming(forceStopCommand = false)
        uvcController.onDestroy()
        usbSerialController.onDestroy()
        disconnect()
        ioExecutor.shutdownNow()
    }

    private fun showUsbSerialDevicesDialog() {
        val devices = usbSerialController.getAvailableSerialDevices()
        if (devices.isEmpty()) {
            updateStatus("未检测到 USB 串口设备")
            return
        }

        val display = devices.map { device ->
            "${device.deviceName} (VID:${formatHex(device.vendorId)} PID:${formatHex(device.productId)})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择 USB 串口设备")
            .setItems(display) { _, which ->
                selectedDevice = devices[which]
                updateStatus("已选择: ${devices[which].deviceName}")
            }
            .show()
    }

    private fun connectSelectedDevice() {
        val device = selectedDevice
        if (device == null) {
            updateStatus("请先选择设备")
            return
        }

        updateStatus("连接中...")
        usbSerialController.requestPermissionAndConnect(device)
    }

    private fun disconnect() {
        usbSerialController.disconnect()
        updateStatus("已断开连接")
    }

    private fun sendPacket(packet: ByteArray) {
        if (!usbSerialController.isConnected()) {
            updateStatus("未连接，无法发送指令")
            return
        }

        ioExecutor.execute {
            usbSerialController.write(packet)
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

    private fun formatHex(value: Int): String {
        return String.format(Locale.US, "%04X", value and 0xFFFF)
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

    private fun toggleTelemetryPanel() {
        if (panelTelemetry.visibility == View.VISIBLE) {
            panelTelemetry.visibility = View.GONE
            keypadMatrix.visibility = View.VISIBLE
        } else {
            panelTelemetry.visibility = View.VISIBLE
            keypadMatrix.visibility = View.GONE
        }
    }

    private fun onKeypadCellClicked(cell: TextView) {
        val index = keypadCells.indexOf(cell)
        if (index < 0) return

        if (selectedKeypadIndex == index) return

        val unselectedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(Color.parseColor("#C00A2037"))
            setStroke((1f * resources.displayMetrics.density).toInt(), Color.parseColor("#66EE781F"))
        }
        val selectedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(Color.parseColor("#CCEE781F"))
        }

        if (selectedKeypadIndex >= 0) {
            keypadCells[selectedKeypadIndex].background = unselectedBg
            keypadCells[selectedKeypadIndex].setTextColor(
                resources.getColor(R.color.text_primary, theme)
            )
        }

        selectedKeypadIndex = index
        cell.background = selectedBg
        cell.setTextColor(Color.WHITE)
    }

    private fun applyLogoVibrancy() {
        val cm = ColorMatrix().apply {
            setSaturation(1.6f)
        }
        imgLogo.colorFilter = ColorMatrixColorFilter(cm)
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

    private fun showConfigDialog() {
        val darkGreen = Color.parseColor("#2D7D2D")
        val lightGreen = Color.parseColor("#55BB55")
        val yellowGreen = Color.parseColor("#99CC33")
        val defaultColors = intArrayOf(
            darkGreen, lightGreen, darkGreen,
            lightGreen, yellowGreen, lightGreen,
            darkGreen, lightGreen, yellowGreen,
            lightGreen, darkGreen, lightGreen,
        )
        val stateColors = intArrayOf(
            Color.parseColor("#FF4444"),
            Color.parseColor("#4488FF"),
            Color.parseColor("#888888"),
        )
        val stateLabels = arrayOf("R1", "R2", "OFF")
        val stateTextColors = intArrayOf(
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
        )
        val borderColor = Color.parseColor("#66EE781F")
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        fun GradientDrawable.applyFill(color: Int) {
            setColor(color)
        }

        fun TextView.updateAppearance(index: Int) {
            val state = cellStates[index]
            val bg = background as GradientDrawable
            if (state == 0) {
                bg.applyFill(defaultColors[index])
                setTextColor(defaultColors[index])
                text = ""
            } else {
                val si = state - 1
                bg.applyFill(stateColors[si])
                setTextColor(stateTextColors[si])
                text = stateLabels[si]
            }
        }

        val cellViews = Array(12) { index ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setStroke((2f * density).toInt(), borderColor)
                setColor(defaultColors[index])
            }
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (140 * density).toInt(), 1f).apply {
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                gravity = Gravity.CENTER
                textSize = 16f
                background = bg
                setOnClickListener {
                    cellStates[index] = (cellStates[index] + 1) % 4
                    updateAppearance(index)
                }
            }
        }

        for (row in 0 until 4) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0 until 3) {
                rowLayout.addView(cellViews[row * 3 + col])
            }
            root.addView(rowLayout)
        }

        val sendButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (16 * density).toInt()
            }
            text = getString(R.string.config_send)
            textSize = 16f
            setOnClickListener {
                sendConfigPacket()
            }
        }
        root.addView(sendButton)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.config_title))
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sendConfigPacket() {
        val payload = IntArray(3)
        for (i in 0 until 12) {
            val byteIndex = i / 4
            val shift = (3 - (i % 4)) * 2
            payload[byteIndex] = payload[byteIndex] or (cellStates[i] shl shift)
        }
        val frame = ByteArray(4)
        frame[0] = CONFIG_HEADER.toByte()
        frame[1] = payload[0].toByte()
        frame[2] = payload[1].toByte()
        frame[3] = payload[2].toByte()
        sendPacket(frame)
        updateStatus("配置已发送")
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = getString(R.string.status_format, msg)
    }

    private fun updateModeStatus() {
        tvModeStatus.text = getString(R.string.mode_current_format, selectedMode)
    }

    private fun checkForUpdates() {
        UpdateManager(this).checkForUpdate { info ->
            if (info == null) return@checkForUpdate
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 ${info.versionName}")
                    .setMessage(info.releaseNotes)
                    .setPositiveButton("立即更新") { _, _ ->
                        UpdateManager(this).downloadAndInstall(info)
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }
}
