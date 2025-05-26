package com.example.puc2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.puc2.ui.theme.Puc2Theme
import com.crrepa.ble.CRPBleClient
import com.crrepa.ble.scan.bean.CRPScanDevice
import com.crrepa.ble.scan.callback.CRPScanCallback
import com.crrepa.ble.conn.CRPBleDevice
import com.crrepa.ble.conn.CRPBleConnection
import com.crrepa.ble.conn.listener.CRPBleConnectionStateListener

class MainActivity : ComponentActivity() {
    private lateinit var crpBleClient: CRPBleClient
    private val discoveredDevices = ArrayList<CRPScanDevice>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private var scanStatus = mutableStateOf("Ready to scan")
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isScanning = mutableStateOf(false)
    
    // BLE Connection management
    private var crpBleDevice: CRPBleDevice? = null
    private var crpBleConnection: CRPBleConnection? = null
    private var isConnected = mutableStateOf(false)

    private val myConnectionStateListener = object : CRPBleConnectionStateListener {
        override fun onConnectionStateChange(connectionState: Int) {
            Log.d("BLE_APP_CONN", "onConnectionStateChange: newStateValue=$connectionState")

            when (connectionState) {
                CRPBleConnectionStateListener.STATE_CONNECTED -> {
                    updateScanStatus("STATE_CONNECTED!")
                    isConnected.value = true
                    Log.d("BLE_APP_CONN", "Device is CONNECTED")
                }
                CRPBleConnectionStateListener.STATE_DISCONNECTED -> {
                    updateScanStatus("STATE_DISCONNECTED.")
                    cleanupConnection()
                    Log.d("BLE_APP_CONN", "Device is DISCONNECTED")
                }
                CRPBleConnectionStateListener.STATE_CONNECTING -> {
                    updateScanStatus("STATE_CONNECTING...")
                    Log.d("BLE_APP_CONN", "Device is CONNECTING")
                }
                CRPBleConnectionStateListener.STATE_DISCONNECTING -> {
                    updateScanStatus("STATE_DISCONNECTING...")
                    Log.d("BLE_APP_CONN", "Device is DISCONNECTING")
                }
                else -> {
                    updateScanStatus("Unknown connection state: $connectionState")
                    Log.w("BLE_APP_CONN", "Unknown connection state received: $connectionState")
                }
            }
        }
    }

    companion object {
        private const val SCAN_DURATION = 20000L // 20 seconds
        private const val REQUEST_ENABLE_BT = 1
        private const val TAG = "BLE_APP"
        private const val CONNECTION_CHECK_DELAY = 5000L // 5 seconds
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Bluetooth is enabled, start scanning
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
            
            if (!discoveredDevices.any { it.device.address == deviceAddress }) {
                discoveredDevices.add(crpDevice)
                runOnUiThread {
                    listAdapter.add("$deviceName - $deviceAddress")
                    listAdapter.notifyDataSetChanged()
                }
            }
        }

        override fun onScanComplete(results: List<CRPScanDevice>?) {
            Log.d(TAG, "Scan complete")
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
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // Start scan only if permissions are granted and Bluetooth is enabled
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Bluetooth Adapter
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize CRPBleClient
        crpBleClient = CRPBleClient.create(applicationContext)
        
        // Initialize the adapter with an empty mutable list
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        
        // Check and request permissions
        checkAndRequestPermissions()

        setContent {
            Puc2Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BleScannerScreen(
                        devices = discoveredDevices,
                        statusMessage = scanStatus.value,
                        isScanning = isScanning.value,
                        isConnected = isConnected.value,
                        onScanClicked = { scanning ->
                            isScanning.value = scanning
                            if (scanning) {
                                startScanWithPermissionCheck()
                            } else {
                                stopScan()
                            }
                        },
                        onDeviceSelected = { device ->
                            if (!isConnected.value) {
                                connectToDevice(device)
                            }
                        },
                        onDisconnectClicked = {
                            if (isConnected.value) {
                                disconnectDevice()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        try {
            if (isScanning.value) {
                Log.d(TAG, "Stopping active scan in onDestroy")
                crpBleClient.cancelScan()
                isScanning.value = false
            }

            if (isConnected.value || crpBleDevice != null || crpBleConnection != null) { // More robust check
                Log.d(TAG, "Cleaning up BLE connection in onDestroy")
                
                // Try to unregister listener from CRPBleConnection
                try {
                    crpBleConnection?.setConnectionStateListener(null)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unregister listener from crpBleConnection: ${e.message}")
                }

                // Disconnect using CRPBleDevice
                try {
                    crpBleDevice?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during crpBleDevice.disconnect(): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy cleanup: ${e.message}")
        } finally { // Ensure these are always nulled out
            crpBleConnection = null
            crpBleDevice = null
            isConnected.value = false
            Log.d(TAG, "onDestroy: BLE resources nulled out")
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter.isEnabled
    }

    private fun requestBluetoothEnable() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableBtIntent)
    }

    private fun startScanWithPermissionCheck() {
        if (!isBluetoothEnabled()) {
            updateScanStatus("Bluetooth is not enabled!")
            requestBluetoothEnable()
            return
        }

        // Check for BLUETOOTH_SCAN permission on Android 12 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(requiredPermissions)
                return
            }
        }

        startScan()
    }

    private fun startScan() {
        discoveredDevices.clear()
        listAdapter.clear()
        updateScanStatus("Starting scan...")
        isScanning.value = true
        crpBleClient.scanDevice(scanCallback, SCAN_DURATION)
    }

    private fun stopScan() {
        crpBleClient.cancelScan()
        isScanning.value = false
        updateScanStatus("Scan stopped")
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToDevice(deviceInfo: CRPScanDevice) {
        val deviceName = deviceInfo.device.name ?: "Unknown"
        val deviceAddress = deviceInfo.device.address
        
        Log.d(TAG, "Attempting to connect to: $deviceAddress")
        updateScanStatus("Connecting to $deviceAddress...")

        if (isScanning.value) {
            crpBleClient.cancelScan()
            isScanning.value = false
            updateScanStatus("Scan stopped to connect.")
        }

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
                Log.d(TAG, "Connection attempt initiated, listener registered on CRPBleConnection.")
                updateScanStatus("Attempting connection to $deviceName...")
            } else {
                Log.e(TAG, "crpBleDevice.connect() returned null, connection failed to initiate.")
                updateScanStatus("Connection failed: Could not get connection object.")
                cleanupConnection()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during connection attempt: ${e.message}")
            updateScanStatus("Connection error: ${e.message}")
            cleanupConnection()
        }
    }

    private fun cleanupConnection() {
        Log.d(TAG, "cleanupConnection called")
        try {
            // 1. Unregister listener from CRPBleConnection
            crpBleConnection?.setConnectionStateListener(null)

            // 2. Disconnect using CRPBleDevice
            crpBleDevice?.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanupConnection: ${e.message}")
        } finally {
            crpBleConnection = null
            crpBleDevice = null
            isConnected.value = false
            Log.d(TAG, "cleanupConnection: BLE resources nulled out")
        }
    }

    private fun disconnectDevice() {
        Log.d(TAG, "Disconnecting device")
        updateScanStatus("Disconnecting...")
        cleanupConnection()
        updateScanStatus("Disconnected")
    }
}

@Composable
fun BleScannerScreen(
    modifier: Modifier = Modifier,
    devices: List<CRPScanDevice>,
    statusMessage: String,
    isScanning: Boolean,
    isConnected: Boolean,
    onScanClicked: (Boolean) -> Unit,
    onDeviceSelected: (CRPScanDevice) -> Unit,
    onDisconnectClicked: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Message
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Scan/Disconnect Button
        if (isConnected) {
            Button(
                onClick = onDisconnectClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Disconnect")
            }
        } else {
            Button(
                onClick = { onScanClicked(!isScanning) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(if (isScanning) "Stop Scan" else "Scan for Ring")
            }
        }

        // Device List (only show when not connected)
        if (!isConnected) {
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
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.device.address,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium
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
            onScanClicked = {},
            onDeviceSelected = {},
            onDisconnectClicked = {}
        )
    }
}