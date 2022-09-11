package com.viliussutkus89.iamspeed.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.viliussutkus89.iamspeed.R
import com.viliussutkus89.iamspeed.ui.IamSpeedActivity
import java.util.concurrent.Executors


class SpeedListenerService: LifecycleService() {

    companion object {
        private const val TAG = "SpeedListenerService"

        private const val notificationId = 1

        private val started_ = MutableLiveData(false)
        val started: LiveData<Boolean> get() = started_

        val speed: LiveData<SpeedEntry?> get() = SpeedListener.speed
        val satelliteCount: LiveData<Int> get() = SatelliteCountListener.satelliteCount

        private const val START_INTENT_ACTION = "START"
        fun startSpeedListener(context: Context) {
            val intent = Intent(context, SpeedListenerService::class.java).also {
                it.action = START_INTENT_ACTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val STOP_INTENT_ACTION = "STOP"
        fun stopSpeedListener(context: Context) {
            val intent = Intent(context, SpeedListenerService::class.java).also {
                it.action = STOP_INTENT_ACTION
            }
            context.startService(intent)
        }

        private const val STOP_BROADCAST_ACTION = "com.viliussutkus89.iamspeed.STOP_BROADCAST"
    }

    private val stopBroadcastReceiver = object: BroadcastReceiver() {
        val intentFilter = IntentFilter().also {
            it.addAction(STOP_BROADCAST_ACTION)
            it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                it.addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }

        override fun onReceive(context: Context, intent: Intent?) {
            if (started_.value == true) {
                when (intent?.action) {
                    STOP_BROADCAST_ACTION -> stop()
                    LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
                        var isGpsEnabled = false
                        try {
                            isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        }
                        if (!isGpsEnabled) {
                            stop()
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(id: String) {
        val name: CharSequence = getString(R.string.notification_channel_name)
        val description = getString(R.string.notification_channel_description)
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        channel.description = description
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private val notificationBuilder: NotificationCompat.Builder by lazy {
        val channelId = getString(R.string.notification_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(channelId)
        }

        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val tapActionIntent = Intent(this, IamSpeedActivity::class.java)
        val tapActionPendingIntent = PendingIntent.getActivity(this, 0, tapActionIntent, mutabilityFlag)

        val stopIntent = Intent().apply {
            action = STOP_BROADCAST_ACTION
        }
        val stopPendingIntent = PendingIntent.getBroadcast(this, 0, stopIntent, mutabilityFlag)
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.notification_icon_iamspeed)
            .setContentIntent(tapActionPendingIntent)
            .addAction(R.drawable.notification_icon_off, getString(R.string.stop), stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private fun getNotification(speedEntry: SpeedEntry?): Notification {
        val notificationTitle = speedEntry?.speedStr ?: getString(R.string.waiting_for_signal)
        return notificationBuilder
            .setTicker(notificationTitle)
            .setContentTitle(notificationTitle)
            .build()
    }

    private val notificationManagerCompat by lazy { NotificationManagerCompat.from(this) }

    // These are lazy loaded, because Context isn't ready yet
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val executor = Executors.newSingleThreadExecutor()
    private val sharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)
    private val speedListener by lazy { SpeedListener(locationManager, executor, sharedPreferences) }
    private val satelliteCountListener by lazy { SatelliteCountListener(locationManager, executor) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                START_INTENT_ACTION -> start()
                STOP_INTENT_ACTION -> stop()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start() {
        if (started.value == true) {
            Log.e(TAG, "Double start event detected!")
            return
        }

        val initialNotification = notificationBuilder
            .setTicker(getString(R.string.waiting_for_signal))
            .setContentTitle(getString(R.string.waiting_for_signal))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, initialNotification, FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(notificationId, initialNotification)
        }

        registerReceiver(stopBroadcastReceiver, stopBroadcastReceiver.intentFilter)
        speedListener.start()
        satelliteCountListener.start()

        speed.observe(this) { speedEntry ->
            notificationManagerCompat.notify(notificationId, getNotification(speedEntry))
        }

        started_.postValue(true)
    }

    private fun stop() {
        if (started.value != true) {
            return
        }

        unregisterReceiver(stopBroadcastReceiver)
        speedListener.stop()
        satelliteCountListener.stop()
        executor.shutdownNow()
        notificationManagerCompat.cancel(notificationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        started_.value = false
        stopSelf()
    }
}
