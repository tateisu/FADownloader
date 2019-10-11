package jp.juggler.fadownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.tracker.LocationTracker
import jp.juggler.fadownloader.util.Utils

fun actionStop(context : Context) {
	val pref = Pref.pref(context)
	pref.edit()
		.put(Pref.lastMode, Pref.LAST_MODE_STOP)
		.put(Pref.lastModeUpdate, System.currentTimeMillis())
		.apply()
	val intent = Intent(context, DownloadService::class.java)
	context.stopService(intent)
	Receiver1.cancelAlarm(context)
}

fun actionStart(context : Context, repeat:Boolean ){
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
