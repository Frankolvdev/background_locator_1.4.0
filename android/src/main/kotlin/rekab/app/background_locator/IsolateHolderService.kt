package rekab.app.background_locator

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.FlutterNativeView
import rekab.app.background_locator.Keys.Companion.ARG_DISPOSE_CALLBACK
import rekab.app.background_locator.Keys.Companion.ARG_INIT_CALLBACK
import rekab.app.background_locator.Keys.Companion.ARG_INIT_DATA_CALLBACK
import rekab.app.background_locator.Keys.Companion.BACKGROUND_CHANNEL_ID
import rekab.app.background_locator.Keys.Companion.BCM_DISPOSE
import rekab.app.background_locator.Keys.Companion.BCM_INIT
import rekab.app.background_locator.Keys.Companion.CHANNEL_ID
import rekab.app.background_locator.Keys.Companion.DISPOSE_CALLBACK_HANDLE_KEY
import rekab.app.background_locator.Keys.Companion.INIT_CALLBACK_HANDLE_KEY
import rekab.app.background_locator.Keys.Companion.INIT_DATA_CALLBACK_KEY
import rekab.app.background_locator.Keys.Companion.NOTIFICATION_ACTION
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_ICON
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_MSG
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_NOTIFICATION_TITLE
import rekab.app.background_locator.Keys.Companion.SETTINGS_ANDROID_WAKE_LOCK_TIME
import java.util.concurrent.atomic.AtomicBoolean

class IsolateHolderService : MethodChannel.MethodCallHandler, Service() {
    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        @JvmStatic
        val ACTION_START = "START"

        @JvmStatic
        val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"

        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"

        @JvmStatic
        var backgroundFlutterView: FlutterNativeView? = null

        @JvmStatic
        fun setBackgroundFlutterViewManually(view: FlutterNativeView?) {
            backgroundFlutterView = view
            sendInit()
        }

        @JvmStatic
        var isRunning = false

        @JvmStatic
        var isSendedInit = false

        @JvmStatic
        var instance: Context? = null

        @JvmStatic
        fun sendInit() {
            if (backgroundFlutterView != null && instance != null && !isSendedInit) {
                val context = instance
                val initCallback = BackgroundLocatorPlugin.getCallbackHandle(context!!, INIT_CALLBACK_HANDLE_KEY)
                if (initCallback != null) {
                    val initialDataMap = BackgroundLocatorPlugin.getDataCallback(context, INIT_DATA_CALLBACK_KEY)
                    val backgroundChannel = MethodChannel(backgroundFlutterView,
                            BACKGROUND_CHANNEL_ID)
                    Handler(context.mainLooper)
                            .post {
                                backgroundChannel.invokeMethod(BCM_INIT,
                                        hashMapOf(ARG_INIT_CALLBACK to initCallback, ARG_INIT_DATA_CALLBACK to initialDataMap))
                            }
                }
                isSendedInit = true
            }
        }

        @JvmStatic
        internal val serviceStarted = AtomicBoolean(false)

        @JvmStatic
        internal var pluginRegistrantCallback: PluginRegistry.PluginRegistrantCallback? = null

        @JvmStatic
        fun setPluginRegistrant(callback: PluginRegistry.PluginRegistrantCallback) {
            pluginRegistrantCallback = callback
        }
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg = "Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationIconColor = 0
    private var icon = 0
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private val notificationId = 1
    internal lateinit var backgroundChannel: MethodChannel
    internal lateinit var context: Context

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startLocatorService(this)
    }

    private fun start() {
        if (isRunning) {
            return
        }

        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(wakeLockTime)
            }
        }

        instance = this
        sendInit()

        // Starting Service as foreground with a notification prevent service from closing
        val notification = getNotification()
        startForeground(notificationId, notification)

        isRunning = true
    }

    private fun getNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Notification channel is available in Android O and up
            val channel = NotificationChannel(CHANNEL_ID, notificationChannelName,
                    NotificationManager.IMPORTANCE_LOW)

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
        }

        val intent = Intent(this, getMainActivityClass(this))
        intent.action = NOTIFICATION_ACTION

        val pendingIntent: PendingIntent = PendingIntent.getActivity(this,
                1, intent, PendingIntent.FLAG_MUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationMsg)
                .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(notificationBigMsg))
                .setSmallIcon(icon)
                .setColor(notificationIconColor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
                .setOngoing(true)
                .build()
    }

    private fun stop() {
        instance = null
        isRunning = false
        isSendedInit = false
        if (backgroundFlutterView != null) {
            val context = this
            val disposeCallback = BackgroundLocatorPlugin.getCallbackHandle(context, DISPOSE_CALLBACK_HANDLE_KEY)
            if (disposeCallback != null && backgroundFlutterView != null) {
                val backgroundChannel = MethodChannel(backgroundFlutterView,
                        BACKGROUND_CHANNEL_ID)
                Handler(context.mainLooper)
                        .post {
                            backgroundChannel.invokeMethod(BCM_DISPOSE,
                                    hashMapOf(ARG_DISPOSE_CALLBACK to disposeCallback))
                        }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }

        when {
            ACTION_SHUTDOWN == intent.action -> {
                shutdownHolderService()
            }
            ACTION_START == intent.action -> {
                startHolderService(intent)
            }
            ACTION_UPDATE_NOTIFICATION == intent.action -> {
                if (isRunning) {
                    updateNotification(intent)
                }
            }
        }

        return START_STICKY
    }

    private fun startHolderService(intent: Intent) {
        notificationChannelName = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME).toString()
        notificationTitle = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        notificationMsg = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        notificationBigMsg = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        val iconNameDefault = "ic_launcher"
        var iconName = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_ICON)
        if (iconName == null || iconName.isEmpty()) {
            iconName = iconNameDefault
        }
        icon = resources.getIdentifier(iconName, "mipmap", packageName)
        notificationIconColor = intent.getLongExtra(SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
        wakeLockTime = intent.getIntExtra(SETTINGS_ANDROID_WAKE_LOCK_TIME, 60) * 60 * 1000L
        start()
    }

    private fun shutdownHolderService() {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) {
                    release()
                }
            }
        }
        stopForeground(true)
        stopSelf()
        stop()
    }

    private fun updateNotification(intent: Intent) {
        if (intent.hasExtra(SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg = intent.getStringExtra(SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        }

        val notification = getNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            Keys.METHOD_SERVICE_INITIALIZED -> {
                synchronized(serviceStarted) {
                    serviceStarted.set(true)
                }
            }
            else -> result.notImplemented()
        }

        result.success(null)
    }


}