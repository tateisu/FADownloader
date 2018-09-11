package jp.juggler.fadownloader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.content.ContextCompat
import jp.juggler.fadownloader.util.LogTag

class Receiver1 : BroadcastReceiver() {
	
	companion object {
		private val log = LogTag("Receiver1")

		const val ACTION_ALARM = "alarm"

		const val ACTION_NEW_FILE_NOTIFICATION_TAP = "newFileNotificationTap"

		const val ACTION_NEW_FILE_NOTIFICATION_DELETE = "newFileNotificationDelete"
		
		private fun intentReceiver1(context : Context, action : String) : Intent {
			val intent = Intent(context, Receiver1::class.java)
			intent.action = action
			return intent
		}
		
		fun piActivity(context : Context) : PendingIntent {
			val intent = Intent(context, ActMain::class.java)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
			return  PendingIntent.getActivity(
				context,
				565,
				intent,
				PendingIntent.FLAG_UPDATE_CURRENT
			)
		}
		
		fun piAlarm(context : Context) : PendingIntent {
			return PendingIntent.getBroadcast(
				context,
				566,
				intentReceiver1(context, ACTION_ALARM),
				PendingIntent.FLAG_UPDATE_CURRENT
			)
		}
		
		fun piNewFileTap(context : Context) : PendingIntent =
			PendingIntent.getBroadcast(
				context,
				567,
				intentReceiver1(context, Receiver1.ACTION_NEW_FILE_NOTIFICATION_TAP),
				PendingIntent.FLAG_UPDATE_CURRENT
			)

		fun piNewFileNotificationDelete(context : Context) : PendingIntent =
			 PendingIntent.getBroadcast(
				context,
				568,
				intentReceiver1(context, Receiver1.ACTION_NEW_FILE_NOTIFICATION_DELETE),
				PendingIntent.FLAG_UPDATE_CURRENT
			)
		
		fun cancelAlarm(context:Context){
			try {
				val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
				am?.cancel( piAlarm(context))
			} catch(ex : Throwable) {
				log.trace(ex, "cancelAlarm failed.")
			}
		}
		
		fun openApp(context : Context) {
			val intent = Intent(context, ActMain::class.java)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
			intent.putExtra(ActMain.EXTRA_TAB, ActMain.TAB_RECORD)
			context.startActivity(intent)
		}
		
		fun openService(context : Context, broadcast_intent : Intent) {
			val service_intent = Intent(context, DownloadService::class.java)
			service_intent.action = DownloadService.ACTION_BROADCAST_RECEIVED
			service_intent.putExtra(DownloadService.EXTRA_BROADCAST_INTENT, broadcast_intent)
			ContextCompat.startForegroundService(context, service_intent)
		}
		
		
	}
	
	override fun onReceive(context : Context, broadcast_intent : Intent) {
		try {
			when(broadcast_intent.action) {
				
				// ダウンロードファイル数の通知やウィジェットのタップ
				ACTION_NEW_FILE_NOTIFICATION_TAP -> {
					NewFileWidget.clearDownloadCount(context)
					openApp(context)
					return
				}
				
				// ダウンロードファイル数の通知をスワイプで消去した
				ACTION_NEW_FILE_NOTIFICATION_DELETE -> {
					NewFileWidget.clearDownloadCount(context)
					return
				}
				
				Intent.ACTION_BOOT_COMPLETED -> {
					// 繰り返しモードなら端末の起動時に常駐開始する
					if(Pref.lastMode(Pref.pref(context)) == Pref.LAST_MODE_REPEAT) {
						openService(context, broadcast_intent)
					}
				}
				
				ACTION_ALARM -> {
					// 実行中ならアラームでサービス再開する
					// 停止ボタンを押した後はアラームによる起動は行わない
					if(Pref.lastMode(Pref.pref(context)) != Pref.LAST_MODE_STOP) {
						openService(context, broadcast_intent)
					}
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex, "onReceive failed.")
		}
	}
}
