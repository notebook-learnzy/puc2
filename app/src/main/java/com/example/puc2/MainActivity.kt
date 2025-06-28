package com.example.puc2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.puc2.ui.theme.Puc2Theme
import com.crrepa.ble.CRPBleClient
import com.crrepa.ble.scan.bean.CRPScanDevice
import com.crrepa.ble.scan.callback.CRPScanCallback
import com.crrepa.ble.conn.CRPBleDevice
import com.crrepa.ble.conn.CRPBleConnection
import com.crrepa.ble.conn.listener.CRPBleConnectionStateListener
import com.crrepa.ble.conn.listener.CRPBatteryListener
import com.crrepa.ble.conn.listener.CRPHrvChangeListener
import com.crrepa.ble.conn.bean.CRPHistoryHrvInfo
import com.crrepa.ble.conn.bean.CRPTimingHrvInfo
import com.crrepa.ble.conn.listener.CRPSleepChangeListener
import com.crrepa.ble.conn.bean.CRPSleepInfo
import com.crrepa.ble.conn.bean.CRPSleepDetailsInfo
import com.crrepa.ble.conn.type.CRPHistoryDay
import com.crrepa.ble.conn.bean.CRPSleepChronotypeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.puc2.calendar.ui.AgendaScreen
import com.example.puc2.calendar.ui.EventEditScreen
import com.example.puc2.calendar.viewmodel.EventViewModel
import com.example.puc2.calendar.viewmodel.EventViewModelFactory

// Define navigation routes
sealed class Screen(val route: String) {
    object Agenda : Screen("agenda")
    object EventEdit : Screen("eventEdit") // Base route
    fun eventEditWithArg(eventId: Long?): String {
        return if (eventId != null) "eventEdit/$eventId" else "eventEdit/0" // 0 for new event
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var crpBleClient: CRPBleClient
    private val discoveredDevices = mutableStateListOf<CRPScanDevice>()
    private var scanStatus = mutableStateOf("Ready to scan")
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isScanning = mutableStateOf(false)
    private val batteryLevel = mutableStateOf<Int?>(null)
    private val isDeviceCharging = mutableStateOf<Boolean?>(null)
    
    // BLE Connection management
    private var crpBleDevice: CRPBleDevice? = null
    private var crpBleConnection: CRPBleConnection? = null
    private var isConnected = mutableStateOf(false)

    // HRV Specific States & Variables
    private val isHrvTracking = mutableStateOf(false)
    private var latestHrvValue = mutableStateOf<Int?>(null) // Stores the last non-255 HRV from onHrv
    private var latestHrvStatus = mutableStateOf<Int?>(null) // Stores the raw status from onHrv
    // private var lastMapEventHrvSnapshot = mutableStateOf<String?>(null) // We'll replace this with a list

    // NEW: List to store all map events from the current session
    private val currentSessionMapEvents = mutableStateListOf<MapEventData>()
    // NEW: State to hold the events of the *last completed* session for display
    private var completedSessionMapEvents = mutableStateListOf<MapEventData>()

    // NEW Sleep States
    private val sleepInfo = mutableStateOf<CRPSleepInfo?>(null)
    private val sleepDetailsInfo = mutableStateOf<CRPSleepDetailsInfo?>(null)
    // private val sleepChronotype = mutableStateOf<CRPSleepChronotypeInfo?>(null) // Optional for later

    private var hrvMeasurementCoroutineJob: Job? = null
    // private val HRV_REQUEST_INTERVAL_MS = 30000L // Start with your current working value
    private fun systemTimestamp(): String { // Renamed from getCurrentTimestamp for consistency with user prompt
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }

    private val myConnectionStateListener = object : CRPBleConnectionStateListener {
        override fun onConnectionStateChange(connectionState: Int) {
            Log.d(TAG, "onConnectionStateChange CALLED with state: $connectionState")

            when (connectionState) {
                CRPBleConnectionStateListener.STATE_CONNECTED -> {
                    Log.d(TAG, "STATE_CONNECTED block entered")
                    runOnUiThread {
                        scanStatus.value = "STATE_CONNECTED! (Listener)"
                        isConnected.value = true
                    }
                    Log.d(TAG, "isConnected.value is now TRUE")
                    Log.d(TAG, "Attempting to call requestBatteryInfoFromDevice AUTOCALL")
                    requestBatteryInfoFromDevice()
                    // Do not automatically start HRV here anymore, it's handled by toggle or explicit calls
                }
                CRPBleConnectionStateListener.STATE_DISCONNECTED -> {
                    Log.d(TAG, "STATE_DISCONNECTED block entered")
                    runOnUiThread { scanStatus.value = "STATE_DISCONNECTED. (Listener)"}
                    stopHrvTrackingContinuously() // Ensure continuous tracking stops on disconnect
                    cleanupConnection()
                }
                CRPBleConnectionStateListener.STATE_CONNECTING -> {
                    Log.d(TAG, "STATE_CONNECTING block entered")
                    runOnUiThread { scanStatus.value = "STATE_CONNECTING... (Listener)"}
                }
                CRPBleConnectionStateListener.STATE_DISCONNECTING -> {
                     Log.d(TAG, "STATE_DISCONNECTING block entered")
                     runOnUiThread { scanStatus.value = "STATE_DISCONNECTING... (Listener)"}
                }
                else -> {
                    Log.w(TAG, "Unknown connection state: $connectionState")
                    runOnUiThread { scanStatus.value = "Unknown state: $connectionState (Listener)"}
                }
            }
        }
    }

    private val myBatteryListener = object : CRPBatteryListener {
        override fun onBattery(level: Int) {
            Log.d(TAG_BATTERY, "BATTERY_CALLBACK - onBattery: Level=$level")
            runOnUiThread { batteryLevel.value = level }
        }

        override fun onRealTimeBattery(level: Int, statusOrVoltage: Int) {
            Log.d(TAG_BATTERY, "BATTERY_CALLBACK - onRealTimeBattery: Level=$level, Status/Voltage=$statusOrVoltage")
            runOnUiThread {
                batteryLevel.value = level
                isDeviceCharging.value = (statusOrVoltage == 1) 
                Log.d(TAG_BATTERY, "Interpreted isDeviceCharging as: ${isDeviceCharging.value} based on statusOrVoltage: $statusOrVoltage")
            }
        }
    }
    
    private val myHrvListener = object : CRPHrvChangeListener {
        override fun onHrv(hrvValueOrStatus: Int) {
            val timestamp = systemTimestamp() 
            Log.d(TAG_HRV, "[HRV LISTENER @ $timestamp] onHrv callback received: $hrvValueOrStatus")
            runOnUiThread {
                latestHrvStatus.value = hrvValueOrStatus
                if (hrvValueOrStatus != 255) { 
                    latestHrvValue.value = hrvValueOrStatus
                    Log.i(TAG_HRV, "[HRV DATA @ $timestamp] Processed HRV Value: $hrvValueOrStatus")
                    // scanStatus.value = "HRV Update: $hrvValueOrStatus" // Optional UI update
                } else {
                    Log.i(TAG_HRV, "[HRV STATUS @ $timestamp] Status Update: $hrvValueOrStatus (Measuring/Busy)")
                }
            }
        }

        override fun onHistoryHrv(historyHrvList: MutableList<CRPHistoryHrvInfo>?) {
            Log.d(TAG_HRV, "[HRV HISTORY] Count: ${historyHrvList?.size ?: 0}")
        }

        override fun onTimingInterval(interval: Int) {
            Log.d(TAG_HRV, "[HRV TIMING] Interval: $interval")
        }

        override fun onTimingHrv(timingHrvInfo: CRPTimingHrvInfo?) {
            Log.w(TAG_HRV, "[HRV UNEXPECTED] onTimingHrv: ${timingHrvInfo?.toString()}")
        }
    }

    private val mySleepListener = object : CRPSleepChangeListener {
        override fun onHistorySleepChange(day: CRPHistoryDay?, info: CRPSleepInfo?) {
            val timestamp = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP HISTORY @ $timestamp] Day: ${day?.name}, TotalTime: ${info?.totalTime} min")
            info?.details?.let { stageList ->
                Log.d(TAG_SLEEP, "  Stages (${stageList.size}):")
                stageList.forEach { stage ->
                    Log.d(TAG_SLEEP, "    - Type: ${mapSleepType(stage.type)} (Raw Type: ${stage.type}), StartTimeOffset: ${stage.startTime}, Duration: ${stage.totalTime}")
                }
            }
            // Example: Update state only if it's today's sleep data
            if (day == CRPHistoryDay.TODAY && info != null) {
                runOnUiThread {
                    sleepInfo.value = info
                }
            }
        }

        override fun onHistorySleepListChange(timestamps: MutableList<Long>?) {
            val ts = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP HISTORY LIST @ $ts] Timestamps for available history: $timestamps")
            // TODO: Store or use these timestamps if needed
        }

        override fun onSleepDetails(details: CRPSleepDetailsInfo?) {
            val ts = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP DETAILS @ $ts] Details: $details")
            runOnUiThread {
                sleepDetailsInfo.value = details
            }
        }

        override fun onSleepInfo(info: CRPSleepInfo?) {
            val ts = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP INFO @ $ts] Current/LastNight: TotalTime: ${info?.totalTime} min, Deep: ${info?.deepTime}, Light: ${info?.lightTime}, REM: ${info?.remTime}, Awake: ${info?.awakeTime}")
            info?.details?.let { stageList ->
                Log.d(TAG_SLEEP, "  Stages (${stageList.size}):")
                stageList.forEach { stage ->
                    Log.d(TAG_SLEEP, "    - Type: ${mapSleepType(stage.type)}, Start: ${stage.startTime}, Duration: ${stage.totalTime}")
                }
            }
            runOnUiThread {
                // This might be the most relevant callback for "last night's sleep"
                // if triggered by a specific query like queryCurrentSleep() or querySleepInfo(0)
                sleepInfo.value = info
            }
        }

        override fun onSleepChronotype(chronotype: CRPSleepChronotypeInfo?) {
            val ts = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP CHRONOTYPE @ $ts] $chronotype")
            runOnUiThread {
                // sleepChronotype.value = chronotype // If you have a state for this
            }
        }

        override fun onSleepEnd(success: Boolean) {
            val ts = systemTimestamp()
            Log.i(TAG_SLEEP, "[SLEEP END @ $ts] Sleep session processing ended, success: $success")
            // This might trigger a UI update or a re-query for fresh data
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startScanWithPermissionCheck()
        } else {
            updateScanStatus("Bluetooth enable request was denied")
        }
    }

    private val scanCallback = object : CRPScanCallback {
        override fun onScanning(crpDevice: CRPScanDevice) {
            val deviceName = crpDevice.device.name ?: "Unknown"
            val deviceAddress = crpDevice.device.address
            Log.d(TAG, "Device found: $deviceName - $deviceAddress")
            if (discoveredDevices.none { it.device.address == deviceAddress }) {
                discoveredDevices.add(crpDevice)
            }
        }

        override fun onScanComplete(results: List<CRPScanDevice>?) {
            Log.d(TAG, "Scan complete. Found devices: ${results?.size ?: 0}")
            updateScanStatus("Scan finished. Found ${discoveredDevices.size} devices.")
            isScanning.value = false
        }
    }

    private fun updateScanStatus(status: String) {
        runOnUiThread {
            scanStatus.value = status
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (isBluetoothEnabled()) {
                startScan()
            } else {
                requestBluetoothEnable()
            }
        } else {
            updateScanStatus("Required permissions not granted")
            Toast.makeText(this, "Permissions required for BLE operations", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG_LIFECYCLE, "onCreate - START")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        crpBleClient = CRPBleClient.create(applicationContext)
        checkAndRequestPermissions()

        Log.d(TAG_LIFECYCLE, "onCreate - BEFORE setContent")
        setContent {
            Puc2Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val eventViewModel: EventViewModel = viewModel(
                        factory = EventViewModelFactory(application as PucApplication)
                    )

                    NavHost(navController = navController, startDestination = Screen.Agenda.route) {
                        composable(Screen.Agenda.route) {
                            AgendaScreen(
                                eventViewModel = eventViewModel,
                                onNavigateToEventEdit = {
                                    navController.navigate(Screen.EventEdit.eventEditWithArg(it))
                                }
                            )
                        }
                        composable(
                            route = "eventEdit/{eventId}", // Actual route with argument
                            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
                        ) {
                            backStackEntry ->
                            val eventId = backStackEntry.arguments?.getLong("eventId")
                            EventEditScreen(
                                eventViewModel = eventViewModel,
                                eventId = if (eventId == 0L) null else eventId, // null for new, actual id for edit
                                onSave = { navController.popBackStack() },
                                onCancel = { navController.popBackStack() }
                            )
                        }
                        // You can add your BleScannerScreen route back here later
                        // composable("bleScanner") { BleScannerScreen(...) }
                    }

                    /* Original content - temporarily commented out for calendar focus
                    val hrvTrackingState by remember { isHrvTracking }
                    val currentActualHrv by remember { latestHrvValue }
                    // val mapEventInfo by remember { lastMapEventHrvSnapshot } // Replaced
                    val eventsToShow = remember { completedSessionMapEvents } // Observe this for the list
                    val currentSleepInfo by remember { sleepInfo } // Added
                    val currentSleepDetails by remember { sleepDetailsInfo } // Added

                    BleScannerScreen(
                        devices = discoveredDevices,
                        statusMessage = scanStatus.value,
                        isScanning = isScanning.value,
                        isConnected = isConnected.value,
                        batteryLevel = batteryLevel.value,
                        isDeviceCharging = isDeviceCharging.value,
                        isHrvTracking = hrvTrackingState,
                        currentHrvValue = currentActualHrv, // This is the LIVE current HRV if tracking
                        // lastMapEventTime = mapEventInfo, // Remove this specific one
                        mapEventsToShow = eventsToShow.toList(), // Pass the list of completed events
                        sleepInfo = currentSleepInfo, // Added
                        sleepDetailsInfo = currentSleepDetails, // Added
                        onScanClicked = { 
                            if (!isScanning.value) {
                                Log.d(MainActivity.TAG_COMPOSE, "onScanClicked: Requesting to START scan")
                                startScanWithPermissionCheck()
                            } else {
                                Log.d(MainActivity.TAG_COMPOSE, "onScanClicked: Requesting to STOP scan")
                                stopScan()
                            }
                        },
                        onDeviceSelected = { device ->
                            Log.d(MainActivity.TAG_COMPOSE, "onDeviceSelected: ${device.device.name ?: "Unknown"} - ${device.device.address}")
                            if (!isConnected.value) {
                                connectToDevice(device)
                            } else {
                                Log.d(MainActivity.TAG_COMPOSE, "onDeviceSelected: Already connected or attempting to connect. Ignoring.")
                                Toast.makeText(this, "Already connected or busy.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDisconnectClicked = {
                            Log.d(MainActivity.TAG_COMPOSE, "onDisconnectClicked")
                            if (isConnected.value) {
                                disconnectDevice()
                            } else {
                                Log.w(MainActivity.TAG_COMPOSE, "onDisconnectClicked: Not connected, no action.")
                                Toast.makeText(this, "Not connected.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleHrvClicked = {
                            if (isHrvTracking.value) {
                                stopHrvTrackingContinuously()
                            } else {
                                startHrvTrackingContinuously()
                            }
                        },
                        onMapEventClicked = { logMapEvent() },
                        onRequestSleepDataClicked = { requestLastNightSleep() } // Added
                    )
                    */
                }
            }
        }
        Log.d(TAG_LIFECYCLE, "onCreate - AFTER setContent")
    }

    override fun onDestroy() {
        Log.d(TAG_LIFECYCLE, "onDestroy called")
        stopHrvTrackingContinuously() // Ensure HRV tracking is stopped
        try {
            if (isScanning.value) {
                Log.d(TAG, "Stopping active scan in onDestroy")
                crpBleClient.cancelScan()
            }
            cleanupConnection() // General cleanup
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy's additional cleanup: ${e.message}")
        } finally {
            isScanning.value = false
            isConnected.value = false // Ensure UI reflects final state
             batteryLevel.value = null
             isDeviceCharging.value = null
            Log.d(TAG_LIFECYCLE, "onDestroy: BLE resources nulled out and states reset.")
        }
        super.onDestroy() // Call super last
    }

    private fun isBluetoothEnabled(): Boolean = bluetoothAdapter.isEnabled

    private fun requestBluetoothEnable() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableBtIntent)
    }

    private fun startScanWithPermissionCheck() {
        Log.d(TAG, "startScanWithPermissionCheck: Entered.")
        if (!isBluetoothEnabled()) {
            updateScanStatus("Bluetooth is not enabled!")
            requestBluetoothEnable()
            return
        }
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG, "All necessary permissions granted. Calling startScan().")
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        Log.d(TAG, "startScan: Entered.")
        discoveredDevices.clear()
        updateScanStatus("Starting scan...")
        isScanning.value = true
        val scanSuccessfullyInitiated = crpBleClient.scanDevice(scanCallback, SCAN_DURATION)
        if (!scanSuccessfullyInitiated) {
            Log.e(TAG, "startScan: crpBleClient.scanDevice FAILED to start scan.")
            updateScanStatus("Failed to initiate scan (library error).")
            isScanning.value = false
        } else {
            Log.d(TAG, "Scan successfully initiated.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (isScanning.value) {
            crpBleClient.cancelScan()
            isScanning.value = false
            updateScanStatus("Scan stopped")
            Log.d(TAG, "Scan stopped by user/logic.")
        } else {
            Log.d(TAG, "Stop scan called but not currently scanning.")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG, "All permissions already granted at app start.")
            // Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show() // Optional
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceInfo: CRPScanDevice) {
        val deviceName = deviceInfo.device.name ?: "Unknown"
        val deviceAddress = deviceInfo.device.address
        Log.d(TAG, "Attempting to connect to: $deviceAddress ($deviceName)")
        updateScanStatus("Connecting to $deviceName...")

        if (isScanning.value) stopScan()

        // Reset relevant states for new connection
        batteryLevel.value = null
        isDeviceCharging.value = null
        latestHrvValue.value = null
        latestHrvStatus.value = null
        // lastMapEventHrvSnapshot.value = null // Keep last map event for viewing after disconnect? User choice. For now, clear.

        try {
            crpBleDevice = crpBleClient.getBleDevice(deviceAddress)
            if (crpBleDevice == null) {
                Log.e(TAG, "Failed to get CRPBleDevice for $deviceAddress")
                updateScanStatus("Error: Could not get device object.")
                return
            }
            crpBleConnection = crpBleDevice!!.connect()
            if (crpBleConnection != null) {
                crpBleConnection!!.setConnectionStateListener(myConnectionStateListener)
                crpBleConnection!!.setBatteryListener(myBatteryListener)
                crpBleConnection!!.setHrvChangeListener(myHrvListener)
                crpBleConnection!!.setSleepChangeListener(mySleepListener)
                Log.d(TAG, "Connection attempt initiated, listeners registered.")
            } else {
                Log.e(TAG, "crpBleDevice.connect() returned null.")
                updateScanStatus("Connection failed: Could not get connection object.")
                cleanupConnection() 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection attempt: ${e.message}", e)
            updateScanStatus("Connection error: ${e.message}")
            cleanupConnection()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun cleanupConnection() {
        Log.d(TAG, "cleanupConnection called for device: ${crpBleDevice?.address}")
        stopHrvTrackingContinuously() // Make sure HRV tracking is stopped first
        try {
            crpBleConnection?.setConnectionStateListener(null)
            crpBleConnection?.setBatteryListener(null)
            crpBleConnection?.setHrvChangeListener(null)
            crpBleConnection?.setSleepChangeListener(null)
            // crpBleConnection?.close() // Library might not have this, rely on device.disconnect()
            crpBleDevice?.disconnect()
            Log.d(TAG, "Disconnected or attempted disconnect for ${crpBleDevice?.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during BLE resource cleanup: ${e.message}", e)
        } finally {
            crpBleConnection = null
            crpBleDevice = null
            isConnected.value = false
            // UI states reset here for clarity, though some might be reset by listeners/other logic
            batteryLevel.value = null 
            isDeviceCharging.value = null
            latestHrvValue.value = null
            latestHrvStatus.value = null
            // lastMapEventHrvSnapshot.value = null // Keep last map event for viewing after disconnect? User choice. For now, clear.
            sleepInfo.value = null // Added
            sleepDetailsInfo.value = null // Added
            if (scanStatus.value.contains("Connected to") || scanStatus.value.contains("Connecting to")) {
                 updateScanStatus("Disconnected.") // General status
            }
            Log.d(TAG, "cleanupConnection: BLE resources nulled out, UI states reset.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        Log.d(TAG, "disconnectDevice called by UI.")
        updateScanStatus("Disconnecting...")
        stopHrvTrackingContinuously() // Stop HRV tracking first
        cleanupConnection() // Then perform full cleanup
        // scanStatus updated within cleanupConnection
    }

    @SuppressLint("MissingPermission")
    private fun requestBatteryInfoFromDevice() {
        Log.d(TAG_BATTERY, "requestBatteryInfoFromDevice: Called.")
        if (crpBleConnection != null && isConnected.value) {
            try {
                crpBleConnection!!.setBatteryListener(myBatteryListener)
                crpBleConnection!!.queryBattery()
                Log.d(TAG_BATTERY, "Battery info query SENT.")
            } catch (e: Exception) {
                Log.e(TAG_BATTERY, "Error in requestBatteryInfoFromDevice: ${e.message}", e)
            }
        } else {
            Log.w(TAG_BATTERY, "Not connected or crpBleConnection is null. Cannot query battery.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun actualDeviceStartSingleHrvMeasurement() {
        if (crpBleConnection != null && isConnected.value) {
            try {
                Log.d(TAG_HRV, "[HRV CMD @ ${systemTimestamp()}] Calling crpBleConnection.setHrvChangeListener & startMeasureHrv()")
                crpBleConnection!!.setHrvChangeListener(myHrvListener) // Ensure listener is fresh
                crpBleConnection!!.startMeasureHrv()
            } catch (e: Exception) {
                Log.e(TAG_HRV, "Error in actualDeviceStartSingleHrvMeasurement: ${e.message}", e)
                runOnUiThread { updateScanStatus("Error starting single HRV read.") }
            }
        } else {
            Log.w(TAG_HRV, "Cannot start single HRV: Not connected or crpBleConnection is null.")
            runOnUiThread {
                updateScanStatus("HRV: Not connected.")
                isHrvTracking.value = false 
                hrvMeasurementCoroutineJob?.cancel()
                hrvMeasurementCoroutineJob = null
            }
        }
    }

    private fun startHrvTrackingContinuously() {
        if (crpBleConnection == null || !isConnected.value) {
            Log.w(TAG_HRV, "Cannot start continuous HRV: Not connected.")
            updateScanStatus("HRV: Not connected for continuous tracking.")
            isHrvTracking.value = false
            return
        }
        if (isHrvTracking.value && hrvMeasurementCoroutineJob?.isActive == true) { // Check if job is also active
            Log.d(TAG_HRV, "Continuous HRV tracking is already active and job is running.")
            return
        }

        Log.i(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] --- Continuous HRV Tracking INITIATED ---")
        isHrvTracking.value = true
        latestHrvValue.value = null
        latestHrvStatus.value = null
        currentSessionMapEvents.clear() // <<<< CLEAR LIST FOR NEW SESSION
        // lastMapEventHrvSnapshot.value = null // Old way
        updateScanStatus("HRV tracking starting...")

        hrvMeasurementCoroutineJob?.cancel()
        hrvMeasurementCoroutineJob = lifecycleScope.launch { 
            while (isActive && isHrvTracking.value) { 
                if (!isConnected.value) { 
                    Log.w(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] Loop: Device disconnected. Stopping HRV loop.")
                    stopHrvTrackingContinuously() 
                    break 
                }
                Log.d(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] Loop: Triggering single HRV measurement.")
                actualDeviceStartSingleHrvMeasurement()
                delay(HRV_REQUEST_INTERVAL_MS)
            }
            // Log reason for loop exit
            if (!isHrvTracking.value) {
                Log.i(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] Continuous HRV loop gracefully exited (isHrvTracking is false).")
            } else if (!isActive) { 
                 Log.i(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] Continuous HRV loop cancelled (coroutine no longer active).")
            } else if (!isConnected.value) {
                 Log.i(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] Continuous HRV loop exited (device disconnected).")
            }
        }
    }

    private fun stopHrvTrackingContinuously() {
        val wasTracking = isHrvTracking.value
        Log.i(TAG_HRV, "[HRV SESSION @ ${systemTimestamp()}] --- Continuous HRV Tracking STOPPING (was active: $wasTracking) ---")

        isHrvTracking.value = false // Then update the state

        hrvMeasurementCoroutineJob?.cancel() // Cancel the coroutine first
        hrvMeasurementCoroutineJob = null

        if (crpBleConnection != null && isConnected.value) { // Check connection before BLE command
            try {
                Log.d(TAG_HRV, "[HRV CMD @ ${systemTimestamp()}] Calling crpBleConnection.stopMeasureHrv()")
                crpBleConnection!!.stopMeasureHrv()
                 // crpBleConnection!!.setHrvChangeListener(null) // Optional: Listener is re-set on start
            } catch (e: Exception) {
                Log.e(TAG_HRV, "Error calling stopMeasureHrv() on device: ${e.message}", e)
            }
        }

        // Copy current session events to completed session events for display
        if (wasTracking) { // Only copy if tracking was actually active
            completedSessionMapEvents.clear()
            completedSessionMapEvents.addAll(currentSessionMapEvents)
        }
        // currentSessionMapEvents.clear() // Clear for next session (already done in start)

        updateScanStatus("HRV tracking stopped. ${completedSessionMapEvents.size} map events logged.")
    }

    private fun logMapEvent() {
        if (!isHrvTracking.value) {
            Log.w(TAG_MAP, "[MAP EVENT @ ${systemTimestamp()}] Attempted to log, but HRV tracking is NOT active.")
            runOnUiThread { Toast.makeText(this, "HRV Tracking not active", Toast.LENGTH_SHORT).show() }
            return
        }

        val eventTimestamp = systemTimestamp()
        val currentProcessedHrv = latestHrvValue.value
        val currentRawStatus = latestHrvStatus.value

        Log.e(TAG_MAP, "[MAP EVENT @ $eventTimestamp] --- 'Map Event' Button Clicked ---")

        if (currentProcessedHrv != null) {
            Log.i(TAG_MAP, "[MAP EVENT @ $eventTimestamp] Snapshot - Last Processed HRV: $currentProcessedHrv")
            // runOnUiThread { lastMapEventHrvSnapshot.value = "Mapped HRV: $currentProcessedHrv @ $eventTimestamp" } // Old way
        } else if (currentRawStatus != null) {
            Log.i(TAG_MAP, "[MAP EVENT @ $eventTimestamp] Snapshot - No processed HRV yet. Last Status: $currentRawStatus (Is it 255?)")
            // runOnUiThread { lastMapEventHrvSnapshot.value = "Mapped Status: $currentRawStatus @ $eventTimestamp (No processed HRV)" } // Old way
        } else {
            Log.i(TAG_MAP, "[MAP EVENT @ $eventTimestamp] Snapshot - No HRV data or status received yet in this tracking session.")
            // runOnUiThread { lastMapEventHrvSnapshot.value = "Mapped: No HRV data yet @ $eventTimestamp" } // Old way
        }

        // Store the event
        val eventData = MapEventData(
            eventTimestamp = eventTimestamp,
            hrvValueAtEvent = currentProcessedHrv,
            hrvStatusAtEvent = currentRawStatus
        )
        currentSessionMapEvents.add(eventData) // Add to our list

        runOnUiThread { Toast.makeText(this, "Map Event Logged!", Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("MissingPermission")
    private fun requestLastNightSleep() {
        if (crpBleConnection != null && isConnected.value) {
            Log.d(TAG_SLEEP, "[SLEEP CMD @ ${systemTimestamp()}] Requesting last night's sleep (via queryHistorySleep(TODAY))...")
            updateScanStatus("Requesting sleep data...") // Keep UI update
            try {
                // Ensure listener is set BEFORE making the query
                crpBleConnection!!.setSleepChangeListener(mySleepListener)

                // Request sleep data for "TODAY", which typically means the sleep session
                // that concluded this morning (i.e., last night's sleep).
                crpBleConnection!!.queryHistorySleep(CRPHistoryDay.TODAY)

                Log.d(TAG_SLEEP, "[SLEEP CMD @ ${systemTimestamp()}] queryHistorySleep(TODAY) command SENT.")
            } catch (e: Exception) {
                Log.e(TAG_SLEEP, "Error requesting sleep data: ${e.message}", e)
                updateScanStatus("Error requesting sleep data.")
            }
        } else {
            Log.w(TAG_SLEEP, "Not connected, cannot request sleep data.")
            updateScanStatus("Sleep: Not connected.")
        }
    }

    // Removed old startHrvMeasurementFromDevice() and toggleHrvTracking() as they are replaced by continuous versions.

    companion object { // Restoring the full companion object
        private const val HRV_REQUEST_INTERVAL_MS = 20000L // As per your request
        private const val SCAN_DURATION = 15000L
        const val TAG = "BLE_APP_MA"
        const val TAG_LIFECYCLE = "MainActivity_LIFECYCLE"
        const val TAG_STATE = "MainActivity_STATE"
        const val TAG_COMPOSE = "BleScannerScreen_MA_DEBUG"
        const val TAG_BATTERY = "BATTERY_MA_DEBUG"
        const val TAG_HRV = "HRV_MA_DEBUG"
        const val TAG_MAP = "MAP_EVENT_MA_DEBUG"
        const val TAG_SLEEP = "SLEEP_MA_DEBUG"
        private const val REQUEST_ENABLE_BT = 1 // This was in the original companion object
        // private const val CONNECTION_CHECK_DELAY = 5000L // This was commented out, keeping it so
    }
}

// MainActivity.kt (can be defined inside or outside the class, or in a separate file)
data class MapEventData(
    val eventTimestamp: String,
    val hrvValueAtEvent: Int?, // The processed HRV value available at the time of map event
    val hrvStatusAtEvent: Int? // The raw status from onHrv at the time of map event
)

// Helper function to map sleep type int to string
fun mapSleepType(typeInt: Int): String {
    return when (typeInt) {
        CRPSleepInfo.SLEEP_STATE_AWAKE -> "Awake"
        CRPSleepInfo.SLEEP_STATE_LIGHT -> "Light"
        CRPSleepInfo.SLEEP_STATE_DEEP -> "Deep"
        CRPSleepInfo.SLEEP_STATE_REM -> "REM"
        else -> "Unknown ($typeInt)"
    }
}

@Composable
fun BleScannerScreen(
    modifier: Modifier = Modifier,
    devices: List<CRPScanDevice>,
    statusMessage: String,
    isScanning: Boolean,
    isConnected: Boolean,
    batteryLevel: Int?,
    isDeviceCharging: Boolean?,
    isHrvTracking: Boolean,
    currentHrvValue: Int?,
    mapEventsToShow: List<MapEventData>,
    sleepInfo: CRPSleepInfo?,
    sleepDetailsInfo: CRPSleepDetailsInfo?,
    onScanClicked: () -> Unit,
    onDeviceSelected: (CRPScanDevice) -> Unit,
    onDisconnectClicked: () -> Unit,
    onToggleHrvClicked: () -> Unit,
    onMapEventClicked: () -> Unit,
    onRequestSleepDataClicked: () -> Unit
) {
    Log.d(MainActivity.TAG_COMPOSE, "Recomposing - isConnected: $isConnected, status: '$statusMessage', isScanning: $isScanning, battery: $batteryLevel, charging: $isDeviceCharging, isHrvTracking: $isHrvTracking, currentHrvValue: $currentHrvValue, devices: ${devices.size}, sleepInfo: ${sleepInfo != null}, sleepDetails: ${sleepDetailsInfo != null}")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusMessage,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        if (isConnected) {
            Log.d(MainActivity.TAG_COMPOSE, "Branch: isConnected == true")
            Button(
                onClick = onDisconnectClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Disconnect")
            }
            Spacer(Modifier.height(8.dp))

            if (batteryLevel != null) {
                Text(
                    text = "Battery: $batteryLevel%${if (isDeviceCharging == true) " (Charging)" else if (isDeviceCharging == false) " (Not Charging)" else ""}",
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    "Battery: (Querying...)",
                    style = TextStyle(fontSize = 16.sp, color = Color.Gray),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onToggleHrvClicked,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),

            ) {
                Text(if (isHrvTracking) "Stop Continuous HRV" else "Start Continuous HRV")
            }

            if (isHrvTracking) {
                currentHrvValue?.let { hrv ->
                    Text(
                        text = "Current HRV: $hrv",
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } ?: 
                    Text(
                        text = "HRV: (Waiting for data...)",
                        style = TextStyle(
                            fontSize = 16.sp
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                Button(
                    onClick = onMapEventClicked,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text("Log Map Event")
                }

                // lastMapEventTime?.let { 
                //    Text("$it (Style removed for testing)", modifier = Modifier.padding(vertical=4.dp))
                // }
            }

            // Display after HRV tracking has stopped and there are events
            if (!isHrvTracking && mapEventsToShow.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Last Session Map Events:", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) { // Constrain height
                    items(mapEventsToShow) { event ->
                        Text(
                            "Time: ${event.eventTimestamp} - HRV: ${event.hrvValueAtEvent ?: "N/A"} (Status: ${event.hrvStatusAtEvent ?: "N/A"})",
                            style = TextStyle(fontSize = 12.sp)
                        )
                    }
                }
            }

            // Sleep Data Section
            Spacer(Modifier.height(16.dp)) // Add some space before sleep section

            Button(
                onClick = onRequestSleepDataClicked,
                enabled = isConnected, // Already guarded by isConnected block, but good for clarity
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("Get Last Night's Sleep")
            }

            sleepInfo?.let { si ->
                Text("Last Sleep (Summary):", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                Text("  Total: ${si.totalTime} min")
                Text("  Deep: ${si.deepTime} min, Light: ${si.lightTime} min")
                Text("  REM: ${si.remTime} min, Awake: ${si.awakeTime} min")
                si.details?.let { stages ->
                    Text("  Sleep Stages (${stages.size}):", style = TextStyle(fontWeight = FontWeight.Medium))
                    // Limiting to 5 stages for brevity in UI, full data is logged.
                    stages.take(5).forEach { stage ->
                        Text("    - Type: ${mapSleepType(stage.type)} (${stage.type}), Start: ${stage.startTime}, Duration: ${stage.totalTime} min")
                    }
                    if (stages.size > 5) {
                        Text("    ... (more stages available in logs)")
                    }
                }
            }

            sleepDetailsInfo?.let { sd ->
                Spacer(Modifier.height(8.dp))
                Text("Last Sleep (Detailed Info):", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                sd.type?.name?.let { Text("  Type (from details): $it") } // type is an enum, access its name
                Text("  Period: ${sd.startDate} to ${sd.endDate}") // Assuming these are formatted dates/times
                Text("  Duration (from details): ${sd.sleepTime} min")
                Text("  Efficiency: ${sd.sleepEfficiency}%") // Assuming it's a percentage
                Text("  Score: ${sd.score}")
                // If CRPSleepDetailsInfo has its own stages, you could display them here too or instead of from CRPSleepInfo.
                // For example, if sd.stages is a list:
                // sd.stages?.let { stages ->
                //     Text("  Detailed Stages (${stages.size}):", style = TextStyle(fontWeight = FontWeight.Medium))
                //     stages.take(5).forEach { stage ->
                //         Text("    - Type: ${mapSleepType(stage.type)}, Start: ${stage.startTime}, Duration: ${stage.totalTime}")
                //     }
                // }
            }

            if (sleepInfo == null && sleepDetailsInfo == null) {
                 Text(
                    "(No sleep data loaded yet. Click 'Get Last Night's Sleep')",
                    style = TextStyle(fontSize = 14.sp, color = Color.Gray),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

        } else {
            Log.d(MainActivity.TAG_COMPOSE, "Branch: isConnected == false")
            Button(
                onClick = onScanClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(if (isScanning) "Stop Scan" else "Scan for Ring")
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceItem(
                        device = device,
                        onClick = { onDeviceSelected(device) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: CRPScanDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.device.name ?: "Unknown Device",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                )
                Text(
                    text = device.device.address,
                    style = TextStyle(fontSize = 14.sp)
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = TextStyle(fontSize = 14.sp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BleScannerScreenPreview() {
    Puc2Theme {
        BleScannerScreen(
            devices = emptyList(),
            statusMessage = "Ready to scan",
            isScanning = false,
            isConnected = false,
            batteryLevel = null,
            isDeviceCharging = null,
            isHrvTracking = false,
            currentHrvValue = 70,
            mapEventsToShow = listOf( // Added example data
                MapEventData("2025-05-29 10:00:00.000", 65, 65),
                MapEventData("2025-05-29 10:01:00.000", null, 255)
            ),
            sleepInfo = null,
            sleepDetailsInfo = null,
            onScanClicked = {},
            onDeviceSelected = { /* ... */ },
            onDisconnectClicked = {},
            onToggleHrvClicked = {},
            onMapEventClicked = {},
            onRequestSleepDataClicked = {}
        )
    }
}