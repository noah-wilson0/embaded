package com.example.doorlockapp
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {
    lateinit var btnScan: Button
    lateinit var listView: ListView
    private val devices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_ACCESS_FINE_LOCATION = 2
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 3
        private const val UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB" // UUID for Serial Port Profile (SPP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        listView = findViewById(R.id.listDevice)
        val btnShowPairedDevices = findViewById<Button>(R.id.btnShowPairedDevices)
        val open=findViewById<Button>(R.id.open)
        val close=findViewById<Button>(R.id.close)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        listView.adapter = adapter

        btnScan.setOnClickListener {
            startBluetoothScan()
        }
        btnShowPairedDevices.setOnClickListener {
            showPairedDevices()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            connectToDevice(device)
        }
        open.setOnClickListener {
            sendBluetoothMessage("OPEN\r\n")
        }

        close.setOnClickListener {
            sendBluetoothMessage("CLOSE\r\n")
        }
        if (bluetoothAdapter == null) {
            showMessage("블루투스를 지원하지 않는 장비입니다.")
            return
        }

        // 권한 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_ACCESS_FINE_LOCATION)
            }
        }

        // 리시버 등록
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
    }

    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            showMessage("블루투스 스캔을 시작합니다.")

            devices.clear()
            deviceNames.clear()
            adapter.notifyDataSetChanged()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                showMessage("BLUETOOTH_SCAN 권한이 필요합니다.")
                return
            }
            bluetoothAdapter?.startDiscovery()
        } else {
            showMessage("블루투스가 활성화되지 않았습니다.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        devices.add(it)
                        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            showMessage("BLUETOOTH_CONNECT 권한이 필요합니다.")
                            return
                        }
                        deviceNames.add(it.name ?: "Unknown Device")
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothAdapter == null) {
            showMessage("블루투스를 지원하지 않는 장비입니다.")
            return
        }

        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }

        // 선택한 장치와의 연결을 시도합니다.
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    showMessage("장치에 페어링되었습니다.")
                    // 연결이 성공한 경우, 추가 작업을 수행합니다.
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    showMessage("장치와의 페어링이 해제되었습니다.")
                }
            }
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToPairedDevice(device: BluetoothDevice) {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e("Test", e.toString())
            }
            bluetoothSocket = null
        }
        val connectThread = Thread {
            try {
                val uuid = UUID.fromString(UUID_STRING)
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()
                runOnUiThread {
                    showMessage("블루투스 소켓을 통해 장치와 연결되었습니다.")
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e("Test", e.toString())
                runOnUiThread {
                    showMessage("장치와 연결할 수 없습니다.")
                }
                try {
                    bluetoothSocket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                    Log.e("Test", closeException.toString())
                }
            }
        }
        connectThread.start()
    }


    private fun showPairedDevices() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 페어링된 기기 목록을 가져옵니다.
        val pairedDevices = bluetoothAdapter?.bondedDevices
        pairedDevices?.let {
            val pairedDeviceList = it.toList()
            val pairedDeviceNames = pairedDeviceList.map { device -> device.name ?: "Unknown Device" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("페어링된 기기 목록")
                .setItems(pairedDeviceNames) { dialog, which ->
                    val selectedDevice = pairedDeviceList[which]
                    connectToPairedDevice(selectedDevice)
                }
                .setNegativeButton("취소") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), REQUEST_BLUETOOTH_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSIONS, REQUEST_ACCESS_FINE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showMessage("권한이 허용되었습니다.")
                } else {
                    showMessage("권한이 거부되었습니다.")
                }
            }
        }
    }
    private fun sendBluetoothMessage(message: String) {
        try {
            bluetoothSocket?.outputStream?.write(message.toByteArray())
            showMessage("메시지 전송: $message")
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("Test", e.toString())
            showMessage("메시지 전송 실패: $message")
        }
    }
}
