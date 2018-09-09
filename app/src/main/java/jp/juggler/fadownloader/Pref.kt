package jp.juggler.fadownloader

import android.content.Context
import android.content.SharedPreferences
import jp.juggler.fadownloader.tracker.LocationTracker

open class BasePref(val key : String)

fun SharedPreferences.Editor.remove(bp : BasePref) : SharedPreferences.Editor =
	this.remove(bp.key)

class BooleanPref(
	key : String,
	private val defVal : Boolean
) : BasePref(key) {
	
	operator fun invoke(pref : SharedPreferences) = pref.getBoolean(key, defVal)
}

fun SharedPreferences.Editor.put(bp : BooleanPref, v : Boolean) : SharedPreferences.Editor =
	this.putBoolean(bp.key, v)

class IntPref(
	key : String,
	val defVal : Int
) : BasePref(key) {
	
	operator fun invoke(pref : SharedPreferences) = pref.getInt(key, defVal)
}

fun SharedPreferences.Editor.put(bp : IntPref, v : Int) : SharedPreferences.Editor =
	this.putInt(bp.key, v)

class LongPref(
	key : String,
	private val defVal : Long
) : BasePref(key) {
	
	operator fun invoke(pref : SharedPreferences) = pref.getLong(key, defVal)
}

fun SharedPreferences.Editor.put(bp : LongPref, v : Long) : SharedPreferences.Editor =
	this.putLong(bp.key, v)

class StringPref(
	key : String,
	val defVal : String
) : BasePref(key) {
	
	operator fun invoke(pref : SharedPreferences) : String = pref.getString(key, defVal) ?: defVal
	
	//	fun getInt(pref : SharedPreferences) = try {
	//		invoke(pref).trim().toInt()
	//	} catch(ex : Throwable) {
	//		defVal.toInt()
	//	}
	
	fun getIntOrNull(pref : SharedPreferences) = try {
		invoke(pref).trim().toInt()
	} catch(ex : Throwable) {
		null
	}
}

fun SharedPreferences.Editor.put(bp : StringPref, v : String) : SharedPreferences.Editor =
	this.putString(bp.key, v)

object Pref {
	fun pref(context : Context) : SharedPreferences {
		return context.getSharedPreferences("app_pref", Context.MODE_PRIVATE)
	}
	
	const val TARGET_TYPE_FLASHAIR_AP = 0
	const val TARGET_TYPE_FLASHAIR_STA = 1
	const val TARGET_TYPE_PENTAX_KP = 2
	const val TARGET_TYPE_PQI_AIR_CARD = 3
	const val TARGET_TYPE_PQI_AIR_CARD_TETHER = 4
	
	const val LAST_MODE_STOP = 0
	const val LAST_MODE_ONCE = 1
	const val LAST_MODE_REPEAT = 2
	
	// UI画面に表示されている情報の永続化
	val uiRepeat = BooleanPref("ui_repeat", false)
	val uiForceWifi = BooleanPref("ui_force_wifi", false)
	val uiAutoRotateThumbnail = BooleanPref("ui_thumbnail_auto_rotate", true)
	val uiCopyBeforeSend = BooleanPref("ui_copy_before_view_send", false)
	val uiProtectedOnly = BooleanPref("ui_protected_only", false)
	val uiSkipAlreadyDownload = BooleanPref("ui_skip_already_download", false)
	
	val uiLastPage = IntPref("ui_last_page", 0)
	val uiTargetType = IntPref("ui_target_type", - 1)
	val uiLocationMode = IntPref("ui_location_mode", LocationTracker.DEFAULT_MODE)
	
	val uiFolderUri = StringPref("ui_folder_uri", "")
	val uiInterval = StringPref("ui_interval", "30")
	val uiFileType = StringPref("ui_file_type", ".jp*")
	val uiSsid = StringPref("ui_ssid", "")
	
	val uiLocationIntervalDesired = StringPref(
		"ui_location_interval_desired",
		(LocationTracker.DEFAULT_INTERVAL_DESIRED / 1000L).toString()
	)
	
	val uiLocationIntervalMin = StringPref(
		"ui_location_interval_min",
		(LocationTracker.DEFAULT_INTERVAL_MIN / 1000L).toString()
	)
	
	//////////////////////////////////////////////////////////////////////
	
	private val uiTargetUrlFlashAirAp =
		StringPref("ui_flashair_url", "http://flashair/") // 歴史的な理由でキー名が特別
	private val uiTargetUrlFlashAirSta = StringPref("ui_target_url_1", "http://flashair/")
	private val uiTargetUrlPentaxKp = StringPref("ui_target_url_2", "http://192.168.0.1/")
	private val uiTargetUrlPentaxPqiAirCard =
		StringPref("ui_target_url_pqi_air_card", "http://192.168.1.1/")
	private val uiTargetUrlPentaxPqiAirCardTether =
		StringPref("ui_target_url_pqi_air_card_tether", "http://AutoDetect/")
	
	private fun uiTargetUrl(target_type : Int) = when(target_type) {
		TARGET_TYPE_FLASHAIR_AP -> uiTargetUrlFlashAirAp
		TARGET_TYPE_FLASHAIR_STA -> uiTargetUrlFlashAirSta
		TARGET_TYPE_PENTAX_KP -> uiTargetUrlPentaxKp
		TARGET_TYPE_PQI_AIR_CARD -> uiTargetUrlPentaxPqiAirCard
		TARGET_TYPE_PQI_AIR_CARD_TETHER -> uiTargetUrlPentaxPqiAirCardTether
		else -> uiTargetUrlFlashAirAp
	}
	
	fun loadTargetUrl(pref : SharedPreferences, target_type : Int) =
		uiTargetUrl(target_type)(pref)
	
	fun saveTargetUrl(edit : SharedPreferences.Editor, target_type : Int, value : String) =
		edit.put(uiTargetUrl(target_type), value)
	
	//////////////////////////////////////////////////////////////////////
	
	// 最後にWorkerを手動開始した時の設定
	
	val workerRepeat = BooleanPref("worker_repeat", false)
	val workerTargetType = IntPref("worker_target_type", - 1)
	val workerTargetUrl = StringPref("worker_flashair_url", "")
	val workerFolderUri = StringPref("worker_folder_uri", "")
	val workerInterval = IntPref("worker_interval", 86400)
	val workerFileType = StringPref("worker_file_type", ".jp*")
	val workerLocationMode = IntPref("worker_location_mode", LocationTracker.DEFAULT_MODE)
	val workerLocationIntervalDesired = LongPref(
		"worker_location_interval_desired",
		LocationTracker.DEFAULT_INTERVAL_DESIRED
	)
	val workerLocationIntervalMin = LongPref(
		"worker_location_interval_min",
		LocationTracker.DEFAULT_INTERVAL_MIN
	)
	val workerForceWifi = BooleanPref("worker_force_wifi", false)
	val workerSsid = StringPref("worker_ssid", "")
	val workerProtectedOnly = BooleanPref("worker_protected_only", false)
	val workerSkipAlreadyDownload = BooleanPref("worker_skip_already_download", false)
	
	//////////////////////////////////////////////////////////////////////
	
	// 最後に押した動作ボタンとその時刻
	val lastMode = IntPref("last_mode", LAST_MODE_STOP)
	val lastModeUpdate = LongPref("last_mode_update", 0L)
	
	// ファイルスキャンが完了した時刻
	val lastIdleStart = LongPref("last_idle_start", 0L)
	val flashAirUpdateStatusOld = LongPref("flashair_update_status_old", - 1L)
	
	// ダウンロード完了通知に表示する数字
	val downloadCompleteCount = LongPref("download_complete_count", 0L)
	val downloadCompleteCountHidden = LongPref("download_complete_count_hidden", 0L)
	
	val purchasedRemoveAd = BooleanPref("remove_ad_purchased", false)
	
	//////////////////////////////////////////////////////////////////////
	
	fun initialize(context : Context) {
		val pref = pref(context)
		
		var bChanged = false
		
		val e = pref.edit()
		
		// 空文字列ならデフォルト値に設定しなおす
		for(sp in arrayOf(
			uiInterval,
			uiFileType,
			uiLocationIntervalDesired,
			uiLocationIntervalMin
		)) {
			if(sp(pref).trim().isEmpty()) {
				bChanged = true
				e.put(sp, sp.defVal)
			}
		}
		
		// 範囲外の値ならデフォルト値に設定しなおす
		if(uiLocationMode(pref) !in 0 .. LocationTracker.LOCATION_HIGH_ACCURACY) {
			bChanged = true
			e.put(uiLocationMode, uiLocationMode.defVal)
		}
		
		if(bChanged) e.apply()
	}
}
