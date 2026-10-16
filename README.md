# Ear Enhancer 🎧

**オフラインAIノイズ除去 → イヤホンへ返送**するAndroidアプリ。Melinaのコンセプトを参考に、まず確実に動作する単独DeepFilterNetエンジンを実装しています。Melina本体のコードはコピーしていません。

## 現在の実装 (v0.1 / 実験版)

- マイク付きイヤホン（Bluetooth LE Audio / HFP / 有線 / USB）の入力を選択
- またはスマホ本体マイクから集音
- **DeepFilterNet** を使用した、端末内での実際のAIノイズ除去
- 処理後の48 kHz / 16-bitモノラル音声をイヤホンへ連続出力
- ノイズ減衰上限 0–60 dB、出力音量、モデル実行負荷、音声経路表示
- Android 12以降（API 31以上）、前面サービスによる連続動作
- 録音の保存・音声データのアップロードなし
- スピーカーへの誤再生防止（出力経路が変わった際に停止）

## APKの作り方

GitHubの **Actions → Android Debug APK** を開き、成功した実行のArtifactsから `ear-enhancer-debug-apk` をダウンロードしてZIPを展開します。Android Studioでプロジェクトを開いてビルドすることもできます。

ローカルのGradle 8.11.1、JDK 17、Android SDK 36でのCLIビルド:

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

**注意: GitHub Actionsによるビルド成功・実機でのBluetooth動作は、確認が取れるまで保証しません。** 最初は小音量で試してください。

## 使用方法

1. マイクと出力先があるイヤホンを接続する。
2. 初回起動時にマイク・Bluetooth権限を許可する。
3. 「イヤホン」を選ぶとイヤホンのマイクを使用。Bluetooth HFP/LE Audioでは通話用の双方向音声経路を使用する。
4. 「スマホ」を選ぶとスマホマイクから、イヤホン（Bluetooth A2DP等）に出力。
5. AIノイズ除去を開始。実行中は前面サービスの通知が表示される。

**Bluetooth Classic HFPの音質制限は解消できません。** 16 kHz相当以下の音声を48 kHzに再サンプリングしても、入力帯域は回復しません。強いノイズ除去で会話そのものが欠ける場合は減衰上限を下げてください。

## 技術

- Kotlin / Jetpack Compose / AudioRecord / AudioTrack / AudioManager
- [KaleyraVideo AndroidDeepFilterNet](https://github.com/KaleyraVideo/AndroidDeepFilterNet) (Apache-2.0)
- `io.github.kaleyravideo:android-deepfilternet:0.0.8`
- モデルはアプリに同梱、完全オンデバイス推論。48 kHz mono PCM 16-bit、固定フレームで処理。
- 正しく出力されたかは「音声経路・診断」で確認する。機器によってはOSがルーティング指定を無視する場合がある。

参考プロジェクト: [Melina](https://github.com/MichaelxBelmonte/melina-audio)（MIT、設計上の参考のみ）

## 今後

MelinaのようなGTCRN / DPDFNet8との聴感比較、音声ファイルによるA/B評価、特定イヤホンに合わせた経路最適化、無音時の安全な音量制御、録音（明示的な同意がある場合のみ）の実装を検討。これらは**未実装**です。

## 安全性

このアプリは研究・試験用であり、補聴器、聴覚保護具、緊急時の周囲音認知の代替にはなりません。開始時は音量を小さくしてください。デジタルリミッターは耳に届く実音圧を測定していません。ハウリング・大音量が発生したら停止してください。
