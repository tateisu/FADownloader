# FA Downloader
+ AndroidスマーフォンからFlashAirのファイル一覧をスキャンして、特定拡張子のファイルをダウンロードするアプリです。
+ スキャンが完了すると終了する単発モードと、繰り返し更新チェックを行い変化があるとスキャンする繰り返しモードがあります。

## Play Store
https://play.google.com/store/apps/details?id=jp.juggler.fadownloader

## ビルドについて
+ BuildVariant ウィンドウで rc/dev のproductFlavor を選択してください
+ Generate signed SDK でも rc/dev のproductFlavor を選択してください

## 動作環境
+ Android 4.0.3 以降
+ FlashAir ファームウェア v2.00.02 以降
+ Wi-Fi 必須
+ 位置情報埋め込み機能は Android 4.1以降推奨。Playサービスが必要です.

## 注意事項
+ FlashAir には無線LANタイムアウトが存在します。無通信状態が続くと無線LAN機能を停止してしまいます。デフォルトは300秒です。アプリから自動転送を行う場合は更新間隔を300秒より短くするか、もしくはFlashAir側の無線LANタイムアウトの設定を変更してください。
+ 電波状況によりスマホがWi-Fi APが勝手に切り替える現象が確認されています。時々アプリ画面やスマホのWi-Fi設定画面を確認してください。v1.3からWi-Fi AP 半強制機能を用意しています。FlashAir への接続を安定させたい場合に使ってください。
+ Android 4.0.x だとPlayサービスが落ちまくるようです。その場合は位置情報埋め込み機能をOFFにしてください

## パーミッション

INTERNET
FlashAirとの通信、広告の表示のために必要です。

ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
CHANGE_WIFI_STATE
通信状態の取得と、Wi-Fi APの切り替えのために必要です。

WRITE_EXTERNAL_STORAGE
READ_EXTERNAL_STORAGE
FlashAir からダウンロードしたデータを保存するために必要です。

ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION
位置情報埋め込み機能に使います。

WAKE_LOCK
処理中に端末のCPUがスリープしないようにするために必要です。

BILLING
「広告非表示」をアプリ内購入できるようにするために必要です。
