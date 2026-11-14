package com.example.limit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LimitService : Service() {

    companion object {
        private const val ACTION_CANCEL = "com.example.limit.CANCEL"
        private const val ACTION_TIME_UP = "com.example.limit.TIME_UP"
        private const val CHANNEL_ID = "limit_timer"
        private const val NOTIF_ID = 1
        private const val ALARM_DURATION_MS = 30_000L

        /** Zapisuje koniec limitu i uruchamia usługę. */
        fun start(ctx: Context, durationMs: Long) {
            Prefs.setEndTime(ctx, System.currentTimeMillis() + durationMs)
            Prefs.setLocked(ctx, false)
            ContextCompat.startForegroundService(ctx, Intent(ctx, LimitService::class.java))
        }

        /** Wznawia usługę na podstawie zapisanego stanu (np. po restarcie). */
        fun resume(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LimitService::class.java))
        }

        /** Anulowanie limitu (hasło sprawdza UI). */
        fun cancel(ctx: Context) {
            Prefs.clear(ctx)
            ctx.startService(Intent(ctx, LimitService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var player: MediaPlayer? = null

    // Zapas gdyby alarm systemowy się spóźnił - sprawdza czas co sekundę.
    private val ticker = object : Runnable {
        override fun run() {
            val end = Prefs.endTime(this@LimitService)
            if (end > 0L && System.currentTimeMillis() >= end) {
                step { onTimeUp() }
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private var lastForeground: String? = null

    /**
     * Gdy telefon jest zablokowany: jeśli na pierwszym planie są Ustawienia, chowa nakładkę
     * (żeby dało się wejść i odinstalować). Gdy tylko użytkownik z nich wyjdzie, nakładka wraca.
     */
    private val watcher = object : Runnable {
        override fun run() {
            if (!Prefs.locked(this@LimitService)) return
            var inSettings = false
            try {
                if (UsageAccess.granted(this@LimitService)) {
                    val window = if (lastForeground == null) 3_600_000L else 10_000L
                    UsageAccess.latestForeground(this@LimitService, window)?.let { lastForeground = it }
                    inSettings = lastForeground?.let { UsageAccess.isSettingsPackage(it) } == true
                }
            } catch (e: Throwable) {
                // Bez wykrywania Ustawień blokada i tak działa - tylko nie da się z niej wyjść do Ustawień.
                CrashLog.record(this@LimitService, e)
            }
            try {
                if (inSettings) removeOverlay() else showOverlay()
            } catch (e: Throwable) {
                CrashLog.record(this@LimitService, e)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startWatcher() {
        handler.removeCallbacks(watcher)
        handler.post(watcher)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        try {
            handleStart(intent)
        } catch (e: Exception) {
            CrashLog.record(this, e)
            Prefs.clear(this)
            stopSelf()
            START_NOT_STICKY
        }

    private fun handleStart(intent: Intent?): Int {
        createChannel()

        val end = Prefs.endTime(this)
        val locked = Prefs.locked(this)

        // Po startForegroundService trzeba w ciągu 5 s wywołać startForeground.
        val first = if (locked) lockedNotification() else countdownNotification(end)
        ServiceCompat.startForeground(
            this, NOTIF_ID, first,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        if (intent?.action == ACTION_CANCEL || (end == 0L && !locked)) {
            shutdown()
            return START_NOT_STICKY
        }

        if (locked) {
            // Wznowienie po restarcie / ubiciu procesu: tylko pokaż blokadę, bez dzwonka.
            startWatcher()
        } else if (System.currentTimeMillis() >= end) {
            onTimeUp()
        } else {
            scheduleAlarm(end)
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
        return START_STICKY
    }

    // ---------- koniec limitu ----------

    private fun onTimeUp() {
        handler.removeCallbacks(ticker)
        val wasLocked = Prefs.locked(this)
        Prefs.setLocked(this, true)
        lastForeground = null

        // Każdy krok osobno, żeby błąd w jednym nie wyłączył reszty (blokady, dzwonka).
        step { cancelAlarm() }
        step { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, lockedNotification()) }
        step { startWatcher() }
        if (!wasLocked) step { playAlarm() }
    }

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLog.record(this, e)
        }
    }

    private fun unlock() {
        stopAlarm()
        Prefs.clear(this)
        shutdown()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        cancelAlarm()
        stopAlarm()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- alarm systemowy (działa też przy uśpionym telefonie) ----------

    private fun alarmIntent(): PendingIntent = PendingIntent.getForegroundService(
        this, 1,
        Intent(this, LimitService::class.java).setAction(ACTION_TIME_UP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleAlarm(end: Long) {
        val show = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        try {
            getSystemService(AlarmManager::class.java)
                .setAlarmClock(AlarmManager.AlarmClockInfo(end, show), alarmIntent())
        } catch (e: Exception) {
            // Bez alarmu systemowego działa zapasowe sprawdzanie co sekundę (ticker).
            CrashLog.record(this, e)
        }
    }

    private fun cancelAlarm() {
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())
    }

    // ---------- dzwonek 30 s ----------

    private fun playAlarm() {
        stopAlarm()
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val mp = MediaPlayer.create(this, R.raw.alarm, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?: return
            mp.isLooping = true
            mp.start()
            player = mp
            handler.postDelayed({ stopAlarm() }, ALARM_DURATION_MS)
        } catch (_: Exception) {
        }
    }

    private fun stopAlarm() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    // ---------- ekran "Czas minął!" nad wszystkim ----------

    private var overlayFailed = false

    private fun showOverlay() {
        if (overlayFailed) return
        try {
            showOverlayUnsafe()
        } catch (e: Throwable) {
            overlayFailed = true
            CrashLog.record(this, e)
        }
    }

    private fun showOverlayUnsafe() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return

        val themed = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(this, R.style.Theme_LimitCzasu)
        )
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)

        val layout = view.findViewById<TextInputLayout>(R.id.pin_layout)
        val input = view.findViewById<TextInputEditText>(R.id.pin_input)

        fun tryUnlock() {
            if (input.text?.toString() == Prefs.PASSWORD) {
                getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                unlock()
            } else {
                layout.error = getString(R.string.lock_wrong)
                input.text?.clear()
            }
        }
        view.findViewById<View>(R.id.unlock_button).setOnClickListener { tryUnlock() }
        input.setOnEditorActionListener { _, _, _ -> tryUnlock(); true }

        @Suppress("DEPRECATION")
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        getSystemService(WindowManager::class.java).addView(view, lp)
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    // ---------- powiadomienia ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    /** Powiadomienie z odliczaniem - system sam odświeża stoper, bez budzenia aplikacji. */
    private fun countdownNotification(end: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setWhen(end)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .build()

    private fun lockedNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notif_locked_title))
            .setContentText(getString(R.string.notif_locked_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAlarm()
        removeOverlay()
        super.onDestroy()
    }
}
