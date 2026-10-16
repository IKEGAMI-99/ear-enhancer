package jp.earenhancer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EarService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var engine: AudioEngine? = null
    private var stoppingRequested = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(
                CHANNEL, "Ear Enhancer", NotificationManager.IMPORTANCE_LOW
            ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            EarActions.STOP -> {
                stoppingRequested = true
                engine?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            EarActions.UPDATE -> {
                engine?.attenuationDb = intent.getFloatExtra(EarActions.ATTENUATION, 45f)
                engine?.volumePercent = intent.getFloatExtra(EarActions.VOLUME, 55f)
                return START_NOT_STICKY
            }
            EarActions.START -> {
                if (worker?.isActive == true) return START_NOT_STICKY
                stoppingRequested = false
                val mic = intent.getStringExtra(EarActions.INPUT) ?: "headset"
                val db = intent.getFloatExtra(EarActions.ATTENUATION, 45f)
                val volume = intent.getFloatExtra(EarActions.VOLUME, 55f)

                val notification = notification()
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    } else startForeground(42, notification)
                } catch (e: Exception) {
                    EarState.status.value = EarStatus(Phase.ERROR,
                        "マイクサービスを起動できません: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

                EarState.status.value = EarStatus(
                    phase = Phase.LOADING, message = "イヤホンとAIモデルを準備しています"
                )
                val session = AudioEngine(applicationContext, mic, db, volume)
                engine = session
                worker = scope.launch {
                    try {
                        session.run()
                        if (!stoppingRequested) {
                            EarState.status.value = EarStatus(Phase.STOPPED, "処理を終了しました")
                        }
                    } catch (_: CancellationException) {
                        // Normal cancellation.
                    } catch (e: Exception) {
                        if (!stoppingRequested) {
                            EarState.status.value = EarStatus(
                                phase = Phase.ERROR,
                                message = e.localizedMessage ?: "オーディオ処理に失敗しました"
                            )
                        }
                    } finally {
                        if (engine === session) engine = null
                        stopSelf()
                    }
                }
            }
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stoppingRequested = true
        engine?.stop()
        worker?.cancel()
        if (EarState.status.value.phase != Phase.ERROR) {
            EarState.status.value = EarStatus(Phase.STOPPED, "停止しました")
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EarService::class.java).apply { action = EarActions.STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Ear Enhancer")
            .setContentText("マイク音声を端末内AIで処理しています")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(launch)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "ear_enhancer_audio" }
}
