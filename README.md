# FADownloader

+ Androidスマーフォンから一回だけor定期的にFlashAirのファイル一覧をスキャンして、特定拡張子のファイルをダウンロードするアプリです。
+ WakeLockやWifiLockを取得したり通信状態を確認したりHTTP通信もしつこくリトライしたりします。
+ DOZEモードでも動くsetAlarmClockを贅沢に使ったりしてるので、バッテリーにはあまり優しくありません。
+ 1.1で位置情報埋め込み機能をを追加しました

## 動作環境
+ Android OS 5.0以降
+ FlashAir ファームウェア v2.00.02以降

## 注意事項
+ FlashAirには無線LANタイムアウトが存在します。無通信状態が続くと無線LAN機能を停止してしまいます。デフォルトは300秒です。アプリから自動転送を行う場合は更新間隔を300秒より短くするか、もしくはFlashAir側の無線LANタイムアウトの設定を変更してください。
+ 電波状況によりスマホがWi-Fi APが勝手に切り替える現象が確認されています。時々アプリ画面やスマホのWi-Fi設定画面を確認してください。
+ ダウンロードした画像にGPSタグを付与するなどしてサイズが変化すると、このアプリはそのファイルを再度ダウンロードしてしまいます。タグを付与したい場合は事前にスマホ上の別のフォルダにデータをコピーするなどしてください。
+ 作ったばっかりだし、自分での使用頻度も低めなので品質はそれなりだと思います。

## ビルドについて
BuildVariant ウィンドウで rc/dev のproductFlavor を選択してください
Generate signed SDK でも rc/dev のproductFlavor を選択してください
