package jp.juggler.fadownloader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.support.v4.content.ContextCompat
import android.support.v4.provider.DocumentFile
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.tracker.LocationTracker
import jp.juggler.fadownloader.util.LogTag
import jp.juggler.fadownloader.util.Utils

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
			return PendingIntent.getActivity(
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
		
		fun cancelAlarm(context : Context) {
			try {
				val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
				am?.cancel(piAlarm(context))
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
		
		fun actionStop(context : Context) {
			val pref = Pref.pref(context)
			pref.edit()
				.put(Pref.lastMode, Pref.LAST_MODE_STOP)
				.put(Pref.lastModeUpdate, System.currentTimeMillis())
				.apply()
			val intent = Intent(context, DownloadService::class.java)
			context.stopService(intent)
			cancelAlarm(context)
		}
		
		private fun actionStart(context : Context,repeat:Boolean ){
			Pref.pref(context).edit().put(Pref.uiRepeat,repeat).apply()
			val error = actionStart(context)
			if(error != null){
				Utils.showToast(context,true,error)
			}
		}

		fun actionStart(context : Context) : String? {
			val pref = Pref.pref(context)
			
			// LocationSettingを確認する前のrepeat引数の値を思い出す
			val repeat = Pref.uiRepeat(pref)
			
			// 設定から値を読んでバリデーション
			val target_type = Pref.uiTargetType(pref)
			if(target_type < 0) {
				return context.getString(R.string.target_type_invalid)
			}
			
			val target_url = Pref.loadTargetUrl(pref, target_type)
			if(target_url.isEmpty()) {
				return context.getString(R.string.target_url_not_ok)
			}
			
			var folder_uri = ""
			val sv = Pref.uiFolderUri(pref)
			if(sv.isNotEmpty()) {
				if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
					val folder = DocumentFile.fromTreeUri(context, Uri.parse(sv))
					if(folder != null) {
						if(folder.exists() && folder.canWrite()) {
							folder_uri = sv
						}
					}
				} else {
					folder_uri = sv
				}
			}
			if(folder_uri.isEmpty()) {
				return context.getString(R.string.local_folder_not_ok)
			}
			
			val file_type = Pref.uiFileType(pref).trim()
			if(file_type.isEmpty()) {
				return context.getString(R.string.file_type_empty)
			}
			
			val location_mode = Pref.uiLocationMode(pref)
			if(location_mode < 0 || location_mode > LocationTracker.LOCATION_HIGH_ACCURACY) {
				return context.getString(R.string.location_mode_invalid)
			}
			
			
			fun validSeconds(v : Int?) : Boolean {
				return v != null && v > 0
			}
			
			if(repeat) {
				if(! validSeconds(Pref.uiInterval.getIntOrNull(pref))) {
					return context.getString(R.string.repeat_interval_not_ok)
				}
			}
			
			if(location_mode != LocationTracker.NO_LOCATION_UPDATE) {
				
				if(! validSeconds(Pref.uiLocationIntervalDesired.getIntOrNull(pref))) {
					return context.getString(R.string.location_update_interval_not_ok)
				}
				if(! validSeconds(Pref.uiLocationIntervalMin.getIntOrNull(pref))) {
					return context.getString(R.string.location_update_interval_not_ok)
				}
			}
			
			val force_wifi = Pref.uiForceWifi(pref)
			
			val ssid : String
			if(! force_wifi) {
				ssid = ""
			} else {
				ssid = Pref.uiSsid(pref).trim()
				if(ssid.isEmpty()) {
					return context.getString(R.string.ssid_empty)
				}
			}
			
			// 最後に押したボタンを覚えておく
			pref.edit()
				.put(Pref.lastMode, if(repeat) Pref.LAST_MODE_REPEAT else Pref.LAST_MODE_ONCE)
				.put(Pref.lastModeUpdate, System.currentTimeMillis())
				.apply()
			
			// 転送サービスを開始
			val intent = Intent(context, DownloadService::class.java)
			intent.action = DownloadService.ACTION_START
			
			intent.put(pref, Pref.uiTetherSprayInterval)
			intent.put(pref, Pref.uiTetherTestConnectionTimeout)
			intent.put(pref, Pref.uiWifiChangeApInterval)
			intent.put(pref, Pref.uiWifiScanInterval)
			intent.put(pref, Pref.uiLocationIntervalDesired)
			intent.put(pref, Pref.uiLocationIntervalMin)
			intent.put(pref, Pref.uiInterval)
			
			intent.put(pref, Pref.uiProtectedOnly)
			intent.put(pref, Pref.uiSkipAlreadyDownload)
			intent.put(pref, Pref.uiStopWhenTetheringOff)
			intent.put(pref, Pref.uiForceWifi)
			intent.put(pref, Pref.uiRepeat)
			intent.put(pref, Pref.uiLocationMode)
			
			intent.put(ssid, Pref.uiSsid)
			intent.put(folder_uri, Pref.uiFolderUri)
			intent.put(file_type, Pref.uiFileType)
			
			intent.put(target_type, Pref.uiTargetType)
			intent.putExtra(DownloadService.EXTRA_TARGET_URL, target_url)
			
			ContextCompat.startForegroundService(context, intent)
			return null
		}
		
		const val ACTION_TASKER_ACTION = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
		const val EXTRA_TASKER_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
		const val EXTRA_ACTION = "action"
		
		private fun onTaskerAction(context : Context, intent : Intent) {
			val b = intent.getBundleExtra(EXTRA_TASKER_BUNDLE)
			if(b != null) {
				val actionInt = b.getInt(EXTRA_ACTION, - 1)
				when(actionInt) {
					Pref.LAST_MODE_STOP -> actionStop(context)
					Pref.LAST_MODE_ONCE ->actionStart(context,false)
					Pref.LAST_MODE_REPEAT ->actionStart(context,true)
				}
			}
		}
		
		const val ACTION_TASKER_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"

		private const val RESULT_CONDITION_SATISFIED = 16
		private const val RESULT_CONDITION_UNSATISFIED = 17
		@Suppress("unused")
		private const val RESULT_CONDITION_UNKNOWN = 18
		
		private fun onTaskerQueryCondition():Int {
			val isServiceAlive = DownloadService.service_instance != null
			return when( isServiceAlive ){
				true -> RESULT_CONDITION_SATISFIED
				false-> RESULT_CONDITION_UNSATISFIED
			}
		}
		
		private const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
		private const val EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
		
		fun sendTaskerQueryRequery(context:Context ,editActivityClassName:String){
			try {
				val intent = Intent(ACTION_REQUEST_QUERY)
				intent.putExtra(EXTRA_ACTIVITY, editActivityClassName)
				context.sendBroadcast(intent)
			}catch(ex:Throwable){
				log.e(ex,"sendTaskerQueryRequery failed.")
			}
		}
	}
	
	override fun onReceive(context : Context, broadcast_intent : Intent) {
		try {
			when(broadcast_intent.action) {
				
				ACTION_TASKER_QUERY_CONDITION ->{
					resultCode = onTaskerQueryCondition()
					return
				}
				
				ACTION_TASKER_ACTION -> {
					onTaskerAction(context, broadcast_intent)
					return
				}
				
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
