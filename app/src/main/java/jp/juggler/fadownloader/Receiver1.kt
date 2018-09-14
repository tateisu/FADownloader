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
		
		private fun intentReceiver1(context : Context, action : String) : Intent =
			Intent(context, Receiver1::class.java)
				.setAction(action)
		
		private fun intentActMain(context : Context) : Intent =
			Intent(context, ActMain::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
		
		//////////////////////////////////////////////////////////////////
		// ダウンロードサービスの通知タップで発動するインテント
		
		fun piActivity(context : Context) : PendingIntent =
			PendingIntent.getActivity(
				context,
				565,
				intentActMain(context),
				PendingIntent.FLAG_UPDATE_CURRENT
			)
		
		//////////////////////////////////////////////////////////////////
		// アラームから発動するインテント
		
		const val ACTION_ALARM = "alarm"
		
		fun piAlarm(context : Context) : PendingIntent =
			PendingIntent.getBroadcast(
				context,
				566,
				intentReceiver1(context, ACTION_ALARM),
				PendingIntent.FLAG_UPDATE_CURRENT
			)
		
		fun cancelAlarm(context : Context) {
			try {
				val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
				am?.cancel(piAlarm(context))
			} catch(ex : Throwable) {
				log.trace(ex, "cancelAlarm failed.")
			}
		}
		
		//////////////////////////////////////////////////////////////////
		// 通知やウィジェットのタップ、スワイプで発動するインテント
		
		const val ACTION_NEW_FILE_NOTIFICATION_TAP = "newFileNotificationTap"
		const val ACTION_NEW_FILE_NOTIFICATION_DELETE = "newFileNotificationDelete"
		
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
		
		//////////////////////////////////////////////////////////////////////////////
		// Tasker連携で受信するbroadcast
		
		// action plugin の発動
		
		const val ACTION_TASKER_ACTION = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
		const val EXTRA_TASKER_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
		const val EXTRA_ACTION = "action"
		
		private fun onTaskerAction(context : Context, intent : Intent) {
			val b = intent.getBundleExtra(EXTRA_TASKER_BUNDLE)
			val actionInt = b?.getInt(EXTRA_ACTION, - 1)
			when(actionInt) {
				Pref.LAST_MODE_STOP -> actionStop(context)
				Pref.LAST_MODE_ONCE -> actionStart(context, false)
				Pref.LAST_MODE_REPEAT -> actionStart(context, true)
			}
		}
		
		// condition plugin の問い合わせ
		
		const val ACTION_TASKER_QUERY_CONDITION =
			"com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
		
		private const val RESULT_CONDITION_SATISFIED = 16
		private const val RESULT_CONDITION_UNSATISFIED = 17
		@Suppress("unused")
		private const val RESULT_CONDITION_UNKNOWN = 18
		
		private fun onTaskerQueryCondition() : Int {
			return when(DownloadService.service_instance) {
				null -> RESULT_CONDITION_UNSATISFIED
				else -> RESULT_CONDITION_SATISFIED
			}
		}
		
		// condition plugin の更新通知
		
		private const val ACTION_REQUEST_QUERY =
			"com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
		private const val EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
		
		fun sendTaskerConditionUpdate(context : Context, editActivityClassName : String) {
			try {
				context.sendBroadcast(
					Intent(ACTION_REQUEST_QUERY)
						.putExtra(EXTRA_ACTIVITY, editActivityClassName)
				)
			} catch(ex : Throwable) {
				log.e(ex, "sendTaskerQueryRequery failed.")
			}
		}
		
		//////////////////////////////////////////////////////////////////////////////
		// レシーバーからアプリ画面やサービスを起動する
		
		private fun openDownloadRecord(context : Context) = context.startActivity(
			intentActMain(context)
				.putExtra(ActMain.EXTRA_TAB, ActMain.TAB_RECORD)
		)
		
		private fun openService(context : Context, broadcast_intent : Intent) {
			val service_intent = Intent(context, DownloadService::class.java)
			service_intent.action = DownloadService.ACTION_BROADCAST_RECEIVED
			service_intent.putExtra(DownloadService.EXTRA_BROADCAST_INTENT, broadcast_intent)
			ContextCompat.startForegroundService(context, service_intent)
		}
	}
	
	override fun onReceive(context : Context, broadcast_intent : Intent?) {
		try {
			when(broadcast_intent?.action) {
				
				ACTION_TASKER_QUERY_CONDITION -> {
					resultCode = onTaskerQueryCondition()
				}
				
				ACTION_TASKER_ACTION -> {
					onTaskerAction(context, broadcast_intent)
				}
				
				ACTION_NEW_FILE_NOTIFICATION_TAP -> {
					NewFileWidget.clearDownloadCount(context)
					openDownloadRecord(context)
				}
				
				ACTION_NEW_FILE_NOTIFICATION_DELETE -> {
					NewFileWidget.clearDownloadCount(context)
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
