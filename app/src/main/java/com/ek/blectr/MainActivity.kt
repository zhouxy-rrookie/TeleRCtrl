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
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    private lateinit var tvPacketHex: TextView
    private lateinit var btnSelectDevice: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnVideoMenu: Button
    private lateinit var btnUsbDiag: Button
    private lateinit var btnConfig: Button
    private lateinit var btnProtocol: Button
    private lateinit var btnSidebarToggle: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnVideoFilter: Button
    private lateinit var panelSidebar: View
    private lateinit var guideLeftEnd: Guideline

    private lateinit var drivePad: ThrottleSteeringView
    private lateinit var joystickRight: JoystickView

    private lateinit var imgLogo: ImageView
    private lateinit var processedCameraView: ImageView
    private lateinit var btnToggleTelemetry: Button
    private lateinit var keypadMatrix: LinearLayout
    private var selectedKeypadIndex: Int = -1
    private val keypadButtons = mutableListOf<TextView>()
    private lateinit var panelTelemetry: View
    private val funcButtons = mutableListOf<TextView>()
    private val funcButtonStates = BooleanArray(8)
    private lateinit var cameraView: android.view.TextureView
    private lateinit var uvcController: UvcPreviewController
    private lateinit var usbSerialController: UsbSerialController

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var selectedDevice: UsbDevice? = null
    private var axisX: Float = 0f
    private var axisY: Float = 0f
    private var axisYaw: Float = 0f
    private var axisJoystickY: Float = 0f
    private var packetSequence: Int = 0
    private var isStreaming: Boolean = false
    private val streamHandler = Handler(Looper.getMainLooper())
    private var sidebarExpanded: Boolean = false
    private val cellStates = IntArray(12)
    private var switchChassis = 0
    private var switchChannel = 0
    private var switchZone = 0
    private var processedVideoEnabled = false

    companion object {
        private const val STREAM_INTERVAL_MS = 50L
        private const val AXIS_SCALE = 100
        private const val FRAME_H1 = 0x5B
        private const val FRAME_H2 = 0x5B
        private const val FRAME_TAIL = 0x2B
        private const val CONFIG_H1 = 0x5C
        private const val CONFIG_H2 = 0x5C
        private const val CONFIG_TAIL = 0x2C
    }

    private val streamRunnable = object : Runnable {
        override fun run() {
            if (!isStreaming) return
            sendUnifiedFrame()
            streamHandler.postDelayed(this, STREAM_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        applyLogoVibrancy()
        usbSerialController = UsbSerialController(this) { message ->
            runOnUiThread {
                updateStatus(message)
                if (message.startsWith("已连接")) {
                    startStreaming()
                } else if (message.startsWith("已断开") || message.startsWith("USB 设备已拔出") || message.startsWith("USB 发送失败")) {
                    stopStreaming()
                }
            }
        }
        uvcController = UvcPreviewController(this, cameraView, processedCameraView) { message ->
            runOnUiThread { updateStatus(message) }
        }
        bindClickListeners()
        updateVideoFilterButton()
        setSidebarExpanded(false)
        updateStatus("未连接")
        checkForUpdates()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvPacketHex = findViewById(R.id.tvPacketHex)
        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnVideoMenu = findViewById(R.id.btnVideoMenu)
        btnUsbDiag = findViewById(R.id.btnUsbDiag)
        btnConfig = findViewById(R.id.btnConfig)
        btnProtocol = findViewById(R.id.btnProtocol)
        btnSidebarToggle = findViewById(R.id.btnSidebarToggle)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnVideoFilter = findViewById(R.id.btnVideoFilter)
        panelSidebar = findViewById(R.id.panelSidebar)
        guideLeftEnd = findViewById(R.id.guideLeftEnd)

        cameraView = findViewById(R.id.camera_view)
        imgLogo = findViewById(R.id.imgLogo)
        processedCameraView = findViewById(R.id.processed_camera_view)
        btnToggleTelemetry = findViewById(R.id.btnToggleTelemetry)
        keypadMatrix = findViewById(R.id.keypadMatrix)
        panelTelemetry = findViewById(R.id.panelTelemetry)
        drivePad = findViewById(R.id.drivePad)
        joystickRight = findViewById(R.id.joystickRight)

        rebuildKeypad()

        val funcButtonIds = intArrayOf(
            R.id.funcPump, R.id.funcGrab, R.id.funcFix,
            R.id.funcLight1, R.id.funcLight2, R.id.funcLight3,
            R.id.funcToggle, R.id.funcLock,
        )
        funcButtonIds.forEachIndexed { index, id ->
            val btn = findViewById<TextView>(id)
            btn.setOnClickListener { onFuncButtonClicked(index, btn) }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((3f * resources.displayMetrics.density).toInt(), Color.parseColor("#88EE781F"))
                setColor(Color.parseColor("#C00A2037"))
            }
            btn.background = bg
            funcButtons.add(btn)
        }
    }

    private fun bindClickListeners() {
        applyGamePadMotion(btnVideoMenu, 1.04f)
        applyGamePadMotion(btnSelectDevice, 1.04f)
        applyGamePadMotion(btnConnect, 1.04f)
        applyGamePadMotion(btnDisconnect, 1.04f)
        applyGamePadMotion(btnUsbDiag, 1.04f)
        applyGamePadMotion(btnConfig, 1.04f)
        applyGamePadMotion(btnProtocol, 1.04f)
        applyGamePadMotion(btnSidebarToggle, 1.04f)
        applyGamePadMotion(btnCheckUpdate, 1.04f)
        applyGamePadMotion(btnVideoFilter, 1.04f)

        btnVideoFilter.setOnClickListener {
            processedVideoEnabled = !processedVideoEnabled
            uvcController.setProcessedPreviewEnabled(processedVideoEnabled)
            updateVideoFilterButton()
            updateStatus(if (processedVideoEnabled) "已切换到滤镜图传" else "已切换到原始图传")
        }

        btnCheckUpdate.setOnClickListener {
            updateStatus("正在检查更新...")
            checkForUpdates()
        }

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
        btnUsbDiag.setOnClickListener { showUsbDiagnosticsDialog() }
        btnConfig.setOnClickListener { showConfigDialog() }
        btnProtocol.setOnClickListener { showProtocolDialog() }
        btnToggleTelemetry.setOnClickListener { toggleTelemetryPanel() }
        btnSidebarToggle.setOnClickListener { setSidebarExpanded(!sidebarExpanded) }

        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchChassis),
                findViewById(R.id.switchTask),
            ),
            defaultIndex = 0,
        ) { index -> switchChassis = index }
        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchChannel1),
                findViewById(R.id.switchChannel2),
            ),
            defaultIndex = 0,
        ) { index -> switchChannel = index }
        configureSegmentGroup(
            listOf(
                findViewById(R.id.switchZone1),
                findViewById(R.id.switchZone2),
                findViewById(R.id.switchZone3),
            ),
            defaultIndex = 0,
        ) { index ->
            switchZone = index
            rebuildKeypad()
        }

        joystickRight.listener = object : JoystickView.Listener {
            override fun onMove(normalizedX: Float, normalizedY: Float) {
                axisX = normalizedX
                axisJoystickY = normalizedY
            }

            override fun onRelease() {
                axisX = 0f
                axisJoystickY = 0f
            }
        }

        drivePad.listener = object : ThrottleSteeringView.Listener {
            override fun onMove(normalizedSteering: Float, normalizedThrottle: Float) {
                axisY = normalizedThrottle
                axisYaw = normalizedSteering
            }

            override fun onRelease() {
                axisYaw = 0f
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbSerialController.onStart()
        uvcController.onStart()
    }

    override fun onStop() {
        uvcController.onStop()
        usbSerialController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        uvcController.onDestroy()
        usbSerialController.onDestroy()
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
        stopStreaming()
        usbSerialController.disconnect()
        updateStatus("已断开连接")
    }

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        streamHandler.post(streamRunnable)
    }

    private fun stopStreaming() {
        isStreaming = false
        streamHandler.removeCallbacks(streamRunnable)
    }

    private fun sendUnifiedFrame() {
        if (!usbSerialController.isConnected()) return
        val frame = ByteArray(9)
        frame[0] = FRAME_H1.toByte()
        frame[1] = FRAME_H2.toByte()
        val matrixVal = if (selectedKeypadIndex >= 0) selectedKeypadIndex else 0
        val modeVal = switchZone * 4 + switchChannel * 2 + switchChassis
        frame[2] = ((matrixVal shl 4) or (modeVal and 0x0F)).toByte()
        var btnByte = 0
        for (i in funcButtonStates.indices) {
            if (funcButtonStates[i]) btnByte = btnByte or (1 shl i)
        }
        frame[3] = btnByte.toByte()
        val deadZone = 0.14f
        frame[4] = toAxisByte(if (abs(axisY) < deadZone) 0f else axisY)
        frame[5] = toAxisByte(if (abs(axisYaw) < deadZone) 0f else axisYaw)
        frame[6] = toAxisByte(if (abs(axisX) < deadZone) 0f else axisX)
        frame[7] = toAxisByte(if (abs(axisJoystickY) < deadZone) 0f else axisJoystickY)
        frame[8] = FRAME_TAIL.toByte()
        val hex = frame.joinToString(" ") { String.format("%02X", it) }
        runOnUiThread { tvPacketHex.text = hex }
        ioExecutor.execute {
            usbSerialController.write(frame)
        }
    }

    private fun toAxisByte(value: Float): Byte {
        return (value.coerceIn(-1f, 1f) * AXIS_SCALE).roundToInt().coerceIn(-AXIS_SCALE, AXIS_SCALE).toByte()
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

    private fun rebuildKeypad() {
        keypadMatrix.removeAllViews()
        keypadButtons.clear()
        selectedKeypadIndex = -1
        for (i in 0 until 12) cellStates[i] = 0

        val density = resources.displayMetrics.density

        data class KeyButton(val btn: TextView, val cellIdx: Int)
        val groups = mutableListOf<Pair<List<KeyButton>, IntArray>>()

        fun makeBtn(label: String, bgRes: Int): TextView {
            return TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f)
                background = ContextCompat.getDrawable(this@MainActivity, bgRes)
                gravity = Gravity.CENTER
                text = label
                textSize = 12f
                setTextColor(
                    ContextCompat.getColorStateList(this@MainActivity, R.color.toggle_segment_text)
                )
            }
        }

        fun buildRow(labels: List<String>, cellStart: Int, groupCells: IntArray) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_toggle_group)
                setPadding(1, 1, 1, 1)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * density).toInt() }
            }
            val bgRes = when (labels.size) {
                2 -> listOf(R.drawable.bg_toggle_segment_left, R.drawable.bg_toggle_segment_right)
                3 -> listOf(R.drawable.bg_toggle_segment_left, R.drawable.bg_toggle_segment_center, R.drawable.bg_toggle_segment_right)
                else -> List(labels.size) { R.drawable.bg_toggle_segment_center }
            }
            val btns = mutableListOf<KeyButton>()
            labels.forEachIndexed { i, label ->
                if (i > 0) row.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.parseColor("#55EE781F"))
                })
                val cellIdx = cellStart + i
                val btn = makeBtn(label, bgRes[i])
                btns.add(KeyButton(btn, cellIdx))
                row.addView(btn)
            }
            keypadMatrix.addView(row)
            groups.add(Pair(btns, groupCells))
        }

        when (switchZone) {
            0 -> {
                buildRow(listOf("\u53D6\u6746", "\u6536\u6746"), 0, intArrayOf(0, 1))
                buildRow(listOf("\u62AC\u5347\u4F4E", "\u62AC\u5347\u4E2D", "\u62AC\u5347\u9AD8"), 2, intArrayOf(2, 3, 4))
                buildRow(listOf("\u6362\u67461", "\u6362\u67462"), 5, intArrayOf(5, 6))
            }
            1 -> {
                buildRow(listOf("\u6885\u6797\u4F4E", "\u6885\u6797\u4E2D", "\u6885\u6797\u9AD8"), 0, intArrayOf(0, 1, 2, 3, 4, 5))
                buildRow(listOf("\u56DE\u6536", "\u653E\u56DE", "\u8D8A\u533A"), 3, intArrayOf(0, 1, 2, 3, 4, 5))
            }
            2 -> {
                buildRow(listOf("R2\u9AD8", "R2\u4F4E"), 0, intArrayOf(0, 1, 2, 3))
                buildRow(listOf("\u653B\u51FB\u4F4E", "\u653B\u51FB\u9AD8"), 2, intArrayOf(0, 1, 2, 3))
                buildRow(listOf("\u653E\u5757", "\u6361\u5757", "\u653E\u6746"), 4, intArrayOf(4, 5, 6))
            }
        }

        val allBtnItems = groups.flatMap { it.first }
        for ((btns, groupCells) in groups) {
            for ((btnIdx, kb) in btns.withIndex()) {
                kb.btn.setOnClickListener {
                    for (ci in groupCells) cellStates[ci] = 0
                    cellStates[kb.cellIdx] = 1
                    selectedKeypadIndex = kb.cellIdx
                    for (item in allBtnItems) {
                        item.btn.isSelected = cellStates[item.cellIdx] == 1
                    }
                }
            }
        }
    }

    private fun onFuncButtonClicked(index: Int, btn: TextView) {
        funcButtonStates[index] = !funcButtonStates[index]
        val active = funcButtonStates[index]
        val d = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                setColor(Color.parseColor("#CCEE781F"))
                setStroke((4f * d).toInt(), Color.parseColor("#FFD08040"))
            } else {
                setStroke((3f * d).toInt(), Color.parseColor("#88EE781F"))
                setColor(Color.parseColor("#C00A2037"))
            }
        }
        btn.background = bg
        btn.setTextColor(if (active) Color.WHITE else resources.getColor(R.color.text_primary, theme))
    }

    private fun applyLogoVibrancy() {
        val cm = ColorMatrix().apply { setSaturation(1.6f) }
        imgLogo.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun updateVideoFilterButton() {
        btnVideoFilter.text = if (processedVideoEnabled) {
            getString(R.string.video_filter_processed)
        } else {
            getString(R.string.video_filter_raw)
        }
    }

    private fun applyGamePadMotion(view: View, pressedScale: Float) {
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchedView.animate().scaleX(pressedScale).scaleY(pressedScale).alpha(0.92f).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchedView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
                }
            }
            false
        }
    }

    private fun setSidebarExpanded(expanded: Boolean) {
        sidebarExpanded = expanded
        panelSidebar.visibility = if (expanded) View.VISIBLE else View.GONE
        val params = guideLeftEnd.layoutParams as ConstraintLayout.LayoutParams
        params.guidePercent = if (expanded) 0.20f else 0.03f
        guideLeftEnd.layoutParams = params
        btnSidebarToggle.text = if (expanded) getString(R.string.sidebar_close) else getString(R.string.sidebar_open)
    }

    private fun configureSegmentGroup(options: List<TextView>, defaultIndex: Int, onSelected: (Int) -> Unit = {}) {
        options.forEachIndexed { index, option ->
            option.isSelected = index == defaultIndex
            applyGamePadMotion(option, 1.02f)
            option.setOnClickListener {
                options.forEachIndexed { selectedIndex, selectedOption ->
                    selectedOption.isSelected = selectedIndex == index
                }
                onSelected(index)
            }
        }
    }

    private fun showModeSelectDialog() {
        val modeLabels = (1..15).map { mode -> "模式 $mode" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mode_select_title))
            .setSingleChoiceItems(modeLabels, 0) { dialog, _ -> dialog.dismiss() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProtocolDialog() {
        val protocolText = """
9字节帧  |  每50ms持续发送

┌────┬────┬──────────────────┬──────────────────┬──────┬──────┬──────┬──────┬────┐
│ 0  │ 1  │       2         │        3         │  4   │  5   │  6   │  7   │ 8  │
├────┼────┼──────────────────┼──────────────────┼──────┼──────┼──────┼──────┼────┤
│5B  │5B  │高4:矩阵 低4:模式 │低6:功能键 高2:保留│ 油门 │  VW  │  VX  │  VY  │2B  │
└────┴────┴──────────────────┴──────────────────┴──────┴──────┴──────┴──────┴────┘

Byte 0-1: 帧头 0x5B 0x5B
Byte 8:   帧尾 0x2B

Byte 2 高4bit: 矩阵选中键 (0~11)
  0=1, 1=2, 2=3, 3=A, 4=4, 5=5, 6=6,
  7=B, 8=7, 9=8, 10=9, 11=C

Byte 2 低4bit: 模式 (zone*4+channel*2+chassic)
  chassis: 底盘=0, 任务=1
  channel: 通道1=0, 通道2=1
  zone:    一区=0, 二区=1, 三区=2
  共 2*2*3 = 12 种组合 (0~11)

Byte 3 bit0~7: 功能键 (1=开 0=关)
  bit0: 气泵
  bit1: 夹取
  bit2: 固定
  bit3: 灯1
  bit4: 灯2
  bit5: 灯3
  bit6: 切换
  bit7: 锁定

Byte 4: 油门   -100~100 (上推正, 死区0.14)
Byte 5: VW     -100~100 (左摇杆转向, 死区0.14)
Byte 6: VX     -100~100 (右摇杆横移, 死区0.14)
Byte 7: VY     -100~100 (右摇杆Y轴, 死区0.14)

┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅┅

配置帧  |  点击"发送"时触发一次

┌────┬──────────┬──────────┬──────────┬────┐
│ 0  │    1     │    2     │    3     │ 4  │
├────┼──────────┼──────────┼──────────┼────┤
│ CC │ 格子0~3  │ 格子4~7  │ 格子8~11 │ 2B │
└────┴──────────┴──────────┴──────────┴────┘

Byte 0:  帧头 0xCC
Byte 4:  帧尾 0x2B

Byte 1~3: 每个格子 2bit, 从高到低排列
  00=默认  01=R1  10=R2  11=OFF
  格子0~3  → Byte 1 [6:5][4:3][2:1][0:1]
  格子4~7  → Byte 2
  格子8~11 → Byte 3

仅在用户点击"配置→发送"时发送一次, 用于
向底盘下发地图/规则配置.
        """.trimIndent()

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        val horizontalScrollView = HorizontalScrollView(this)
        val textView = TextView(this).apply {
            text = protocolText
            textSize = 12f
            setTextColor(Color.parseColor("#D0E0F0"))
            setPadding(24, 20, 24, 20)
            setLineSpacing(4f, 1f)
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
        }
        horizontalScrollView.addView(textView)
        scrollView.addView(horizontalScrollView)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.protocol_title))
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.config_title))
            .setView(root)
            .setPositiveButton(getString(R.string.config_send)) { _, _ ->
                sendConfigPacket()
            }
            .setNegativeButton("\u5173\u95ED", null)
            .show()
    }

    private fun sendConfigPacket() {
        if (!usbSerialController.isConnected()) {
            updateStatus("未连接，无法发送配置")
            Toast.makeText(this, "未连接，无法发送配置", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = ByteArray(3)
        for (i in 0 until 12) {
            val byteIdx = i / 4
            val shift = (3 - (i % 4)) * 2
            payload[byteIdx] = (payload[byteIdx].toInt() or (cellStates[i] shl shift)).toByte()
        }
        val frame = ByteArray(9)
        frame[0] = CONFIG_H1.toByte()
        frame[1] = CONFIG_H2.toByte()
        frame[2] = payload[0]
        frame[3] = payload[1]
        frame[4] = payload[2]
        frame[5] = 0
        frame[6] = 0
        frame[7] = 0
        frame[8] = CONFIG_TAIL.toByte()
        val hex = frame.joinToString(" ") { String.format("%02X", it) }
        runOnUiThread { tvPacketHex.text = hex }
        ioExecutor.execute {
            usbSerialController.write(frame)
        }
        updateStatus("配置已发送")
        Toast.makeText(this, "配置已发送: $hex", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(msg: String) {
        tvStatus.text = getString(R.string.status_format, msg)
    }

    private fun checkForUpdates() {
        UpdateManager(this).checkForUpdate { result ->
            runOnUiThread {
                when (result) {
                    is UpdateManager.UpdateResult.HasUpdate -> {
                        updateStatus("发现新版本 ${result.info.versionName}")
                        AlertDialog.Builder(this)
                            .setTitle("发现新版本 ${result.info.versionName}")
                            .setMessage(result.info.releaseNotes)
                            .setPositiveButton("立即更新") { _, _ ->
                                UpdateManager(this).downloadAndInstall(result.info)
                            }
                            .setNegativeButton("稍后", null)
                            .show()
                    }
                    is UpdateManager.UpdateResult.NoUpdate -> {
                        updateStatus("已是最新版本")
                    }
                    is UpdateManager.UpdateResult.Error -> {
                        updateStatus(result.message)
                    }
                }
            }
        }
    }
}
