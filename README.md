# KP Downloader

+ Androidスマーフォンから一回だけor定期的に、Pentax KPのWifi AP経由でファイル一覧をスキャンして、特定拡張子のファイルをダウンロードするアプリです。
+ WakeLockやWifiLockを取得したり通信状態を確認したりHTTP通信もしつこくリトライしたりします。
+ DOZEモードでも動くsetAlarmClockを贅沢に使ったりしてるので、バッテリーにはあまり優しくありません。
+ 1.1で位置情報埋め込み機能をを追加しました

## 動作環境
+ Android OS 4.0以降
+ FlashAir ファームウェア v2.00.02以降
+ 位置情報埋め込み機能にはPlay開発者サービスが必要

## 注意事項
+ 4.0.x だとPlayサービスもWi-Fi AP半強制機能も不安定だと思います

## ビルドについて
+ BuildVariant ウィンドウで rc/dev のproductFlavor を選択してください
+ Generate signed SDK でも rc/dev のproductFlavor を選択してください
