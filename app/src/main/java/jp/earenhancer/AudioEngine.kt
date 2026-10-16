package jp.earenhancer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Monitors a chosen microphone, runs actual on-device DeepFilterNet inference,
 * and plays ONLY into an external headset output. No recording or upload.
 *
 * All native model calls happen on the same audio coroutine. The model is
 * stateful, so calls must never be made concurrently or reordered.
 */
class AudioEngine(
    private val context: Context,
    private val inputMode: String,
    @Volatile var attenuationDb: Float,
    @Volatile var volumePercent: Float
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    @Volatile private var stopped = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    private var nativeModel: NativeDeepFilterNet? = null
    private val sampleRate = 48_000
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var usingCommunicationDevice = false

    private val headphoneTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
    private val bidirectionalTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET
    )

    fun stop() {
        stopped = true
        try { recorder?.stop() } catch (_: Exception) {}
        try { player?.pause() } catch (_: Exception) {}
    }

    suspend fun run() {
        var model: NativeDeepFilterNet? = null
        var localRecorder: AudioRecord? = null
        var localPlayer: AudioTrack? = null
        try {
            assertPermissions()
            val inputDevice: AudioDeviceInfo?
            val outputDevice: AudioDeviceInfo
            if (inputMode == "headset") {
                val externalInput = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                    ?: throw IllegalStateException("マイク付きイヤホンが見つかりません")
                inputDevice = externalInput
                val comm = manager.availableCommunicationDevices.firstOrNull {
                    it.type == externalInput.type && it.type in bidirectionalTypes
                } ?: throw IllegalStateException("イヤホンの双方向音声経路がありません")
                previousMode = manager.mode
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!manager.setCommunicationDevice(comm)) {
                    throw IllegalStateException("Bluetooth/通話音声経路の確保に失敗しました")
                }
                usingCommunicationDevice = true
                outputDevice = comm
            } else {
                // A phone microphone with a Bluetooth A2DP output generally gives better
                // fidelity than classic Bluetooth HFP mic capture.
                inputDevice = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: throw IllegalStateException("スマホのマイクが見つかりません")
                outputDevice = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                    ?: throw IllegalStateException("イヤホンの出力先が見つかりません")
            }
            EarState.status.value = EarStatus(
                phase = Phase.LOADING,
                message = "DeepFilterNetモデルを読み込み中",
                input = label(inputDevice), output = label(outputDevice)
            )

            // Bundled-model flavor, no network and no server inference.
            model = NativeDeepFilterNet(context.applicationContext)
            nativeModel = model
            val ready = withTimeout(45_000L) {
                suspendCancellableCoroutine<NativeDeepFilterNet> { continuation ->
                    model.onModelLoaded { if (continuation.isActive) continuation.resume(model) }
                }
            }
            if (stopped) return
            val bytesPerFrame = ready.frameLength.toInt()
            require(bytesPerFrame in 2..8192 && bytesPerFrame % 2 == 0) {
                "不正なDeepFilterNetフレーム長: $bytesPerFrame"
            }

            val minRec = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minPlay = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            require(minRec > 0 && minPlay > 0) { "48kHzの録音/再生に対応していません" }
            val source = if (inputMode == "headset") MediaRecorder.AudioSource.VOICE_COMMUNICATION
                         else MediaRecorder.AudioSource.MIC
            val formatInput = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            localRecorder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(formatInput)
                .setBufferSizeInBytes(maxOf(minRec * 2, bytesPerFrame * 16))
                .build()
            require(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "マイクを初期化できません" }
            recorder = localRecorder
            if (inputDevice != null && !localRecorder.setPreferredDevice(inputDevice)) {
                throw IllegalStateException("指定マイクの選択に失敗しました")
            }
            val formatOutput = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val attributes = AudioAttributes.Builder()
                .setUsage(if (inputMode == "headset") AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            localPlayer = AudioTrack.Builder()
                .setAudioAttributes(attributes).setAudioFormat(formatOutput)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minPlay * 2, bytesPerFrame * 24))
                .build()
            require(localPlayer.state == AudioTrack.STATE_INITIALIZED) { "イヤホン出力を初期化できません" }
            player = localPlayer
            require(localPlayer.setPreferredDevice(outputDevice)) { "イヤホンを出力先に選択できません" }

            ready.setAttenuationLimit(attenuationDb.coerceIn(0f, 60f))
            val inputBytes = ByteArray(bytesPerFrame)
            val filtered = ByteArray(bytesPerFrame)
            val frameBuffer = ByteBuffer.allocateDirect(bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
            var frames = 0L
            var lastReport = System.nanoTime()
            var cumulativeProcessingNs = 0L
            var lastAttenuation = attenuationDb
            localRecorder.startRecording()
            require(localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "マイクを録音開始できません"
            }
            localPlayer.play()
            EarState.status.value = EarStatus(
                phase = Phase.LISTENING, message = "AIノイズ除去中（録音・送信なし）",
                input = label(localRecorder.routedDevice ?: inputDevice),
                output = label(localPlayer.routedDevice ?: outputDevice)
            )

            while (!stopped) {
                var offset = 0
                while (offset < bytesPerFrame && !stopped) {
                    val read = localRecorder.read(
                        inputBytes, offset, bytesPerFrame - offset, AudioRecord.READ_BLOCKING
                    )
                    if (read <= 0) {
                        if (stopped) break
                        error("マイク入力が停止しました (code=$read)")
                    }
                    offset += read
                }
                if (stopped) break
                val startedNs = System.nanoTime()
                val target = attenuationDb.coerceIn(0f, 60f)
                if (target != lastAttenuation) {
                    ready.setAttenuationLimit(target)
                    lastAttenuation = target
                }
                frameBuffer.clear()
                frameBuffer.put(inputBytes)
                frameBuffer.flip()
                ready.processFrame(frameBuffer) // Genuine neural inference, modifies buffer in-place
                frameBuffer.rewind()
                frameBuffer.get(filtered)
                softLimiter(filtered, volumePercent.coerceIn(0f, 100f) / 100f)
                var sent = 0
                while (sent < filtered.size && !stopped) {
                    val n = localPlayer.write(filtered, sent, filtered.size - sent, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) {
                        if (stopped) break
                        error("イヤホン出力が停止しました (code=$n)")
                    }
                    sent += n
                }
                cumulativeProcessingNs += System.nanoTime() - startedNs
                frames++
                if (System.nanoTime() - lastReport >= 800_000_000L) {
                    val routedOut = localPlayer.routedDevice
                    if (routedOut?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        routedOut?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        error("イヤホンへの出力が切れました。スピーカーへの誤再生を防ぐため停止しました")
                    }
                    if (routedOut != null && routedOut.type !in headphoneTypes) {
                        error("イヤホン以外が出力先になったため停止しました")
                    }
                    val frameDurationNs = 1_000_000_000.0 * (bytesPerFrame / 2) / sampleRate
                    val load = (cumulativeProcessingNs / (frames * frameDurationNs)).toFloat()
                    EarState.status.value = EarStatus(
                        phase = Phase.LISTENING,
                        message = if (load > 1f) "処理が実時間に追いついていません"
                                  else "DeepFilterNetで音声を強調中",
                        input = label(localRecorder.routedDevice ?: inputDevice),
                        output = label(routedOut ?: outputDevice),
                        frames = frames,
                        loadRatio = load,
                        inputDbfs = levelDb(inputBytes)
                    )
                    lastReport = System.nanoTime()
                }
            }
        } finally {
            stopped = true
            try { localRecorder?.stop() } catch (_: Exception) {}
            try { localPlayer?.stop() } catch (_: Exception) {}
            try { localRecorder?.release() } catch (_: Exception) {}
            try { localPlayer?.release() } catch (_: Exception) {}
            recorder = null
            player = null
            try { model?.release() } catch (_: Exception) {}
            nativeModel = null
            if (usingCommunicationDevice) {
                try { manager.clearCommunicationDevice() } catch (_: Exception) {}
                try { manager.mode = previousMode } catch (_: Exception) {}
            }
        }
    }

    private fun assertPermissions() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) { "録音権限がありません" }
        if (inputMode == "headset") {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) { "Bluetooth権限がありません" }
        }
    }

    private fun label(d: AudioDeviceInfo?): String =
        if (d == null) "未確定" else "${d.productName} (${when (d.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT HFP"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "本体マイク"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            else -> "type ${d.type}"
        }})"

    /** Keeps absolute PCM amplitude below 0.91 FS; not an acoustic SPL limiter. */
    private fun softLimiter(data: ByteArray, requestedGain: Float) {
        var peak = 1
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or
                (data[i].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        val gain = min(requestedGain, 29_800f / peak)
        i = 0
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or
                (data[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (v * gain).roundToInt().coerceIn(-29_800, 29_800)
            data[i] = (scaled and 0xFF).toByte()
            data[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun levelDb(data: ByteArray): Int {
        var power = 0.0
        var n = 0
        var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or
                (data[i].toInt() and 0xFF)).toShort().toInt()
            power += v.toDouble() * v
            n++
            i += 2
        }
        val rms = sqrt(power / maxOf(n, 1)) / 32768.0
        return if (rms < 1e-6) -90 else (20 * log10(rms)).roundToInt()
    }
}
