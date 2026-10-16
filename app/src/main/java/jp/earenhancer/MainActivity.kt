package jp.earenhancer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF87B9FF),
                secondary = Color(0xFF86E0CF),
                background = Color(0xFF0D1420),
                surface = Color(0xFF192432)
            )
            MaterialTheme(colorScheme = colors) { EarScreen() }
        }
    }
}

@Composable
private fun EarScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pref = remember { context.getSharedPreferences("ear_settings", 0) }
    val state by EarState.status.collectAsState()
    var input by rememberSaveable { mutableStateOf(pref.getString("input", "headset") ?: "headset") }
    var attenuation by rememberSaveable { mutableFloatStateOf(pref.getFloat("attenuation", 45f)) }
    var volume by rememberSaveable { mutableFloatStateOf(pref.getFloat("volume", 55f)) }
    var permissionError by remember { mutableStateOf("") }

    fun launchService(action: String) {
        val i = Intent(context, EarService::class.java).apply {
            this.action = action
            putExtra(EarActions.INPUT, input)
            putExtra(EarActions.ATTENUATION, attenuation)
            putExtra(EarActions.VOLUME, volume)
        }
        try {
            if (action == EarActions.START) ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        } catch (e: Exception) {
            permissionError = e.localizedMessage ?: "サービスの起動に失敗しました"
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionError = ""
            launchService(EarActions.START)
        } else permissionError = "マイクの許可が必要です"
    }

    fun start() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (input == "headset") add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty() ||
            (missing.size == 1 && missing[0] == Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissionError = ""
            launchService(EarActions.START)
        } else permissionLauncher.launch(missing.toTypedArray())
    }

    fun update(atten: Float = attenuation, vol: Float = volume) {
        pref.edit().putString("input", input).putFloat("attenuation", atten)
            .putFloat("volume", vol).apply()
        if (state.phase == Phase.LISTENING) launchService(EarActions.UPDATE)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("EAR ENHANCER", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text("ON-DEVICE NEURAL SPEECH ENHANCEMENT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary)

            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val stateLabel = when (state.phase) {
                        Phase.LISTENING -> "● AI処理中"
                        Phase.LOADING -> "モデル読み込み中…"
                        Phase.ERROR -> "エラー"
                        Phase.STOPPED -> "待機中"
                    }
                    Text(stateLabel, style = MaterialTheme.typography.titleLarge)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            if (state.phase == Phase.LISTENING || state.phase == Phase.LOADING)
                                launchService(EarActions.STOP)
                            else start()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.phase == Phase.LISTENING || state.phase == Phase.LOADING)
                            "停止" else "AIノイズ除去を開始")
                    }
                }
            }

            Text("入力マイク", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = input == "headset",
                    onClick = { if (state.phase == Phase.STOPPED || state.phase == Phase.ERROR) {
                        input = "headset"; update()
                    }},
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("イヤホン") }
                SegmentedButton(
                    selected = input == "phone",
                    onClick = { if (state.phase == Phase.STOPPED || state.phase == Phase.ERROR) {
                        input = "phone"; update()
                    }},
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("スマホ") }
            }
            Text("イヤホンマイクを選ぶと、Bluetooth通話経路を使用します。音質は機種によって制限されます。",
                style = MaterialTheme.typography.bodySmall)

            Text("AIノイズ除去の上限: ${attenuation.toInt()} dB",
                style = MaterialTheme.typography.titleMedium)
            Slider(
                value = attenuation, onValueChange = {
                    attenuation = it; update(atten = it)
                }, valueRange = 0f..60f
            )
            Text("高くすると雑音は減りますが、会話が不自然になる場合があります。",
                style = MaterialTheme.typography.bodySmall)

            Text("出力音量: ${volume.toInt()}%",
                style = MaterialTheme.typography.titleMedium)
            Slider(
                value = volume,
                onValueChange = { volume = it; update(vol = it) },
                valueRange = 0f..100f
            )
            Text("最初は小音量で開始してください。デジタルリミッターは耳への音圧を保証しません。",
                style = MaterialTheme.typography.bodySmall)

            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音声経路・診断", style = MaterialTheme.typography.titleMedium)
                    Text("入力: ${state.input}")
                    Text("出力: ${state.output}")
                    Text("AI: DeepFilterNet (48 kHz / PCM 16-bit)")
                    Text("入力レベル: ${state.inputDbfs} dBFS")
                    Text("処理負荷: ${String.format(Locale.US, "%.0f",state.loadRatio*100)}% / 実時間")
                    Text("処理フレーム数: ${state.frames}")
                }
            }
            if (permissionError.isNotBlank()) Text(permissionError,
                color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("録音データは送信・保存しません。周囲の音を聞く実験用アプリであり、補聴器や聴覚保護具ではありません。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
