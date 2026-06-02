package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class CallMonitorService : Service() {
    private companion object {
        const val TAG = "CallMonitorService"
        const val CHANNEL_ID = "myra_call_monitor"
        const val NOTIFICATION_ID = 2002
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating CallMonitorService")
        createNotificationChannel()
        startForegroundServiceNotification()
        registerPhoneStateListener()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors background incoming calls to announce to user"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("MYRA Call Assistant Active")
            .setContentText("Listening for incoming calls to assist you")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start specialUse foreground service, falling back to standard: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed completely to start foreground service: ${ex.message}")
            }
        }
    }

    private fun registerPhoneStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                Log.d(TAG, "Call State Changed: $state, number: $phoneNumber")
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val incomingNumber = phoneNumber ?: ""
                        if (incomingNumber.isNotBlank()) {
                            val resolvedName = resolveCallerName(incomingNumber)
                            Log.d(TAG, "Incoming ringing call from: $resolvedName ($incomingNumber)")
                            sendCallIntentToActivity(resolvedName, incomingNumber)
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Call State Idle. Sending termination broadcast.")
                        val intent = Intent("com.myra.CALL_ENDED")
                        sendBroadcast(intent)
                    }
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun resolveCallerName(phoneNumber: String): String {
        var contactName = phoneNumber
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (columnIndex != -1) {
                    contactName = cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking contact name: ${e.message}")
        } finally {
            cursor?.close()
        }
        return contactName
    }

    private fun sendCallIntentToActivity(callerName: String, number: String) {
        val intent = Intent("com.myra.INCOMING_CALL_TRIGGER").apply {
            setClassName(packageName, "com.example.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("CALLER_NAME", callerName)
            putExtra("CALLER_NUMBER", number)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call MainActivity: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallMonitorService destroyed")
        phoneStateListener?.let {
            telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
