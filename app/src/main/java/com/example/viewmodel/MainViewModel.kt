package com.example.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.model.AppCommand
import com.example.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "MainViewModel"
    }

    private val context = getApplication<Application>().applicationContext

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    private val _executionStatus = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val executionStatus: SharedFlow<String> = _executionStatus

    // Mapping popular names to absolute system package names for lightning fast launch
    private val appPackageMap = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "settings" to "com.android.settings",
        "calculator" to "com.android.calculator2",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "phone" to "com.android.dialer",
        "contacts" to "com.android.contacts",
        "play store" to "com.android.vending",
        "amazon" to "com.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "discord" to "com.discord",
        "linkedin" to "com.linkedin.android"
    )

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Executing command: ${command.type} with params: ${command.params}")
            _executionStatus.emit("Executing task: ${command.type}")
            
            when (command.type) {
                AppCommand.OPEN_APP -> openApp(command.params["app_name"])
                AppCommand.CLOSE_APP -> closeCurrentApp()
                AppCommand.CALL -> makeCall(command.params["target"])
                AppCommand.SMS -> prepareSms(command.params["name"])
                AppCommand.WHATSAPP_MSG -> prepareWhatsApp(command.params["name"])
                AppCommand.PRIME_CALL -> makePrimeCall(command.params["index"]?.toIntOrNull() ?: 0)
                AppCommand.PRIME_MSG -> preparePrimeSms(command.params["index"]?.toIntOrNull() ?: 0)
                AppCommand.VOLUME_UP -> adjustVolume(increase = true)
                AppCommand.VOLUME_DOWN -> adjustVolume(increase = false)
                AppCommand.FLASHLIGHT_ON -> toggleFlashlight(enable = true)
                AppCommand.FLASHLIGHT_OFF -> toggleFlashlight(enable = false)
                AppCommand.WIFI_ON -> toggleWifi(enable = true)
                AppCommand.WIFI_OFF -> toggleWifi(enable = false)
                AppCommand.BLUETOOTH_ON -> toggleBluetooth(enable = true)
                AppCommand.BLUETOOTH_OFF -> toggleBluetooth(enable = false)
                else -> {
                    _commandResult.postValue("Command not supported yet")
                }
            }
        }
    }

    private fun openApp(appName: String?) {
        if (appName.isNullOrBlank()) {
            _commandResult.postValue("Konsa app kholu, Sir? Naam barabar sunayi nahi dia.")
            return
        }

        val query = appName.lowercase().trim()
        var targetPkg = appPackageMap[query]

        if (targetPkg == null) {
            // Dyno lookup inside package manager
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (packageInfo in packages) {
                val label = pm.getApplicationLabel(packageInfo).toString().lowercase()
                if (label.contains(query) || query.contains(label)) {
                    targetPkg = packageInfo.packageName
                    break
                }
            }
        }

        if (targetPkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _commandResult.postValue("Opening $appName app now!")
            } else {
                _commandResult.postValue("App installation verified but launch capability is blocked.")
            }
        } else {
            _commandResult.postValue("Sorry! Mujhe $appName ke package details nahi mile.")
        }
    }

    private fun closeCurrentApp() {
        val helper = AccessibilityHelperService.instance
        if (helper != null) {
            val success = helper.closeCurrentApp()
            if (success) {
                _commandResult.postValue("Succeeded closing the app.")
            } else {
                _commandResult.postValue("Failed exiting the target app.")
            }
        } else {
            Log.e(TAG, "Accessibility Service is not enabled")
            _commandResult.postValue("Please enable Accessibility service in settings to exit applications.")
        }
    }

    private fun makeCall(target: String?) {
        if (target.isNullOrBlank()) {
            _commandResult.postValue("Kisko call karun? Naam ya mobile number dijiye.")
            return
        }

        var resolvedNumber = target
        // Check if input is non-numeric, meaning name needs resolution
        if (!target.all { it.isDigit() || it == '+' }) {
            resolvedNumber = lookupContactByName(target)
        }

        if (resolvedNumber.isNullOrBlank()) {
            _commandResult.postValue("Contact list me $target ka number nahi mila.")
            return
        }

        val dialIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$resolvedNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dialIntent)
            _commandResult.postValue("Placing call to $target right away!")
        } catch (e: SecurityException) {
            _commandResult.postValue("Call karne ki permissions complete nahi hain.")
        } catch (e: Exception) {
            _commandResult.postValue("Call place karne me error aayi.")
        }
    }

    private fun lookupContactByName(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numColumn != -1) {
                    return cursor.getString(numColumn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving name contact: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun prepareSms(contactName: String?) {
        if (contactName.isNullOrBlank()) {
            _commandResult.postValue("Kisko SMS bhejna hai? Naam batayiye.")
            return
        }

        val resolvedNum = lookupContactByName(contactName)
        if (resolvedNum.isNullOrBlank()) {
            _commandResult.postValue("$contactName ka number nahi mila, Sir.")
            return
        }

        val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$resolvedNum")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(smsIntent)
            _commandResult.postValue("Drafting SMS box to $contactName")
        } catch (e: Exception) {
            _commandResult.postValue("SMS application start load failed.")
        }
    }

    private fun prepareWhatsApp(contactName: String?) {
        if (contactName.isNullOrBlank()) {
            _commandResult.postValue("Kisko WhatsApp message bhejna hai?")
            return
        }

        val resolvedNum = lookupContactByName(contactName)
        if (resolvedNum.isNullOrBlank()) {
            _commandResult.postValue("$contactName ka number contacts list me nahi hai.")
            return
        }

        // Clean number for WhatsApp URL (requires country code)
        var cleanNum = resolvedNum.replace(" ", "").replace("-", "")
        if (!cleanNum.startsWith("+") && cleanNum.length == 10) {
            cleanNum = "+91$cleanNum" // Default fallback country code (India)
        }

        val url = "https://api.whatsapp.com/send?phone=$cleanNum"
        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(waIntent)
            _commandResult.postValue("WhatsApp application details resolving for $contactName.")
        } catch (e: Exception) {
            _commandResult.postValue("WhatsApp opening target parameters failed.")
        }
    }

    private fun makePrimeCall(index: Int) {
        val primeContacts = getPrimeContacts()
        if (index >= primeContacts.size) {
            _commandResult.postValue("Aapka configure kiya hua speed dial prime contact empty hai.")
            return
        }
        val contactNumber = primeContacts[index].optString("number")
        val contactName = primeContacts[index].optString("name")
        if (contactNumber.isNullOrBlank()) {
            _commandResult.postValue("Prime contact has no stored number.")
            return
        }
        val dialIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$contactNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dialIntent)
            _commandResult.postValue("Calling your prime friend $contactName!")
        } catch (e: Exception) {
            _commandResult.postValue("Could not make speed dial phone call.")
        }
    }

    private fun preparePrimeSms(index: Int) {
        val primeContacts = getPrimeContacts()
        if (index >= primeContacts.size) {
            _commandResult.postValue("Prime message contact configure nahi hai.")
            return
        }
        val contactNumber = primeContacts[index].optString("number")
        val contactName = primeContacts[index].optString("name")
        if (contactNumber.isNullOrBlank()) {
            _commandResult.postValue("Prime contact number not found.")
            return
        }
        val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$contactNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(smsIntent)
            _commandResult.postValue("Drafting secure message draft to $contactName.")
        } catch (e: Exception) {
            _commandResult.postValue("Could not start chat thread.")
        }
    }

    private fun getPrimeContacts(): List<JSONObject> {
        val sharedPrefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val jsonStr = sharedPrefs.getString("prime_contacts_json", null)
        val list = mutableListOf<JSONObject>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    list.add(arr.getJSONObject(i))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error seeking prime contacts json: ${e.message}")
            }
        }

        // Legacy fallback support as requested
        if (list.isEmpty()) {
            val name = sharedPrefs.getString("prime_name", "") ?: ""
            val number = sharedPrefs.getString("prime_number", "") ?: ""
            if (name.isNotBlank() && number.isNotBlank()) {
                val oldContact = JSONObject().put("name", name).put("number", number)
                list.add(oldContact)
            }
        }
        return list
    }

    private fun adjustVolume(increase: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            _commandResult.postValue("Volume adjusted successfully, Sir.")
        } catch (e: Exception) {
            _commandResult.postValue("Could not toggle volume parameters.")
        }
    }

    private fun toggleFlashlight(enable: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.getOrNull(0)
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                _commandResult.postValue(if (enable) "Flashlight turned ON!" else "Flashlight turned OFF.")
            } else {
                _commandResult.postValue("Camera hardware parameters missed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight action error: ${e.message}")
            _commandResult.postValue("Failed accessing device torch.")
        }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun toggleWifi(enable: Boolean) {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ restricts direct programmatic toggling. Tell user to open wireless settings or attempt system activity
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _commandResult.postValue("Opening Android Quick WiFi panel.")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                _commandResult.postValue("WiFi toggle configured.")
            }
        } catch (e: Exception) {
            _commandResult.postValue("Error adjusting wireless details: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleBluetooth(enable: Boolean) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                _commandResult.postValue("Bluetooth hardware not recognized on this device.")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Redirect user to Bluetooth connection settings panel directly
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _commandResult.postValue("Opening wireless accessories control center.")
            } else {
                if (enable) {
                    adapter.enable()
                    _commandResult.postValue("Powering up bluetooth antennae.")
                } else {
                    adapter.disable()
                    _commandResult.postValue("Shutteling down wireless radio.")
                }
            }
        } catch (e: Exception) {
            _commandResult.postValue("Failed Bluetooth state transaction: ${e.message}")
        }
    }

    // Call Acceptance Actions
    @SuppressLint("MissingPermission")
    fun acceptCall() {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecom.acceptRingingCall()
                _commandResult.postValue("Call status: Connected.")
            } else {
                _commandResult.postValue("Direct phone pickup not supported on this version.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Accept call fail: ${e.message}")
            _commandResult.postValue("Failed to connect ringing call.")
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectCall() {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
                _commandResult.postValue("Call status: Terminated.")
            } else {
                _commandResult.postValue("Ringing end action skipped due to older platform.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "End call error: ${e.message}")
            _commandResult.postValue("Direct call dropping request failed.")
        }
    }
}
