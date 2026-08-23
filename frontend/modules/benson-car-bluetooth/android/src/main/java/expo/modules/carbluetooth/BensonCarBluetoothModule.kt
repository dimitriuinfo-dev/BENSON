package expo.modules.carbluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.Permissions.askForPermissionsWithPermissionsManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

// Car Mode signal 1/2 — Bluetooth. Monitors system-wide ACL connect/disconnect
// broadcasts (any profile — A2DP/HFP/etc), so it fires for the car's head unit
// even though this app never opened a Bluetooth connection itself.
class BensonCarBluetoothModule : Module() {
  private var receiver: BroadcastReceiver? = null

  private fun hasPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun deviceMap(device: BluetoothDevice?, context: Context): Map<String, Any?> {
    val name = if (hasPermission(context)) device?.name else null
    return mapOf("name" to (name ?: "Unknown device"), "address" to (device?.address ?: ""))
  }

  override fun definition() = ModuleDefinition {
    Name("BensonCarBluetooth")

    Events("onDeviceConnected", "onDeviceDisconnected")

    OnDestroy {
      stopReceiver()
    }

    AsyncFunction("requestPermissionsAsync") { promise: Promise ->
      askForPermissionsWithPermissionsManager(appContext.permissions, promise, Manifest.permission.BLUETOOTH_CONNECT)
    }

    Function("getBondedDevices") {
      val context = appContext.reactContext ?: return@Function emptyList<Map<String, Any?>>()
      if (!hasPermission(context)) return@Function emptyList<Map<String, Any?>>()
      val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      val devices = manager?.adapter?.bondedDevices ?: emptySet()
      devices.map { deviceMap(it, context) }
    }

    Function("startMonitoring") {
      val context = appContext.reactContext
      if (context == null || receiver != null) return@Function Unit
      val r = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
          val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
          val info = deviceMap(device, ctx)
          when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED    -> sendEvent("onDeviceConnected", info)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> sendEvent("onDeviceDisconnected", info)
          }
        }
      }
      val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(r, filter)
      }
      receiver = r
    }

    Function("stopMonitoring") {
      stopReceiver()
    }
  }

  private fun stopReceiver() {
    val context = appContext.reactContext
    val r = receiver
    receiver = null
    if (r != null && context != null) {
      try { context.unregisterReceiver(r) } catch (e: IllegalArgumentException) {}
    }
  }
}
