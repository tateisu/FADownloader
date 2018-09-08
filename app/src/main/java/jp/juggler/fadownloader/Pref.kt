package jp.juggler.fadownloader

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

object Pref {
	
	val TARGET_TYPE_FLASHAIR_AP = 0
	val TARGET_TYPE_FLASHAIR_STA = 1
	val TARGET_TYPE_PENTAX_KP = 2
	val TARGET_TYPE_PQI_AIR_CARD = 3
	val TARGET_TYPE_PQI_AIR_CARD_TETHER = 4
	
	// UI画面に表示されている情報の永続化
	val UI_REPEAT = "ui_repeat"
	val UI_LAST_PAGE = "ui_last_page"
	val UI_TARGET_TYPE = "ui_target_type"
	val UI_TARGET_URL_FLASHAIR_AP = "ui_flashair_url" // 歴史的な理由でキー名が特別
	val UI_TARGET_URL_FLASHAIR_STA = "ui_target_url_1"
	val UI_TARGET_URL_PENTAX_KP = "ui_target_url_2"
	val UI_TARGET_URL_PQI_AIR_CARD = "ui_target_url_pqi_air_card"
	val UI_TARGET_URL_PQI_AIR_CARD_TETHER = "ui_target_url_pqi_air_card_tether"
	
	val UI_FOLDER_URI = "ui_folder_uri"
	val UI_INTERVAL = "ui_interval"
	val UI_FILE_TYPE = "ui_file_type"
	val UI_LOCATION_MODE = "ui_location_mode"
	val UI_LOCATION_INTERVAL_DESIRED = "ui_location_interval_desired"
	val UI_LOCATION_INTERVAL_MIN = "ui_location_interval_min"
	val UI_FORCE_WIFI = "ui_force_wifi"
	val UI_SSID = "ui_ssid"
	val UI_THUMBNAIL_AUTO_ROTATE = "ui_thumbnail_auto_rotate"
	val UI_COPY_BEFORE_VIEW_SEND = "ui_copy_before_view_send"
	val UI_PROTECTED_ONLY = "ui_protected_only"
	val UI_SKIP_ALREADY_DOWNLOAD = "ui_skip_already_download"
	
	val DEFAULT_THUMBNAIL_AUTO_ROTATE = true
	
	// 最後に押したボタン
	val LAST_MODE_UPDATE = "last_mode_update"
	val LAST_MODE = "last_mode"
	val LAST_MODE_STOP = 0
	val LAST_MODE_ONCE = 1
	val LAST_MODE_REPEAT = 2
	
	// 最後にWorkerを手動開始した時の設定
	val WORKER_REPEAT = "worker_repeat"
	val WORKER_TARGET_TYPE = "worker_target_type"
	val WORKER_FLASHAIR_URL = "worker_flashair_url"
	val WORKER_FOLDER_URI = "worker_folder_uri"
	val WORKER_INTERVAL = "worker_interval"
	val WORKER_FILE_TYPE = "worker_file_type"
	val WORKER_LOCATION_INTERVAL_DESIRED = "worker_location_interval_desired"
	val WORKER_LOCATION_INTERVAL_MIN = "worker_location_interval_min"
	val WORKER_LOCATION_MODE = "worker_location_mode"
	val WORKER_FORCE_WIFI = "worker_force_wifi"
	val WORKER_SSID = "worker_ssid"
	val WORKER_PROTECTED_ONLY = "worker_protected_only"
	val WORKER_SKIP_ALREADY_DOWNLOAD = "worker_skip_already_download"
	
	// ファイルスキャンが完了した時刻
	val LAST_IDLE_START = "last_idle_start"
	val FLASHAIR_UPDATE_STATUS_OLD = "flashair_update_status_old"
	
	val REMOVE_AD_PURCHASED = "remove_ad_purchased"
	
	// ダウンロード完了通知に表示する数字
	val DOWNLOAD_COMPLETE_COUNT = "download_complete_count"
	val DOWNLOAD_COMPLETE_COUNT_HIDDEN = "download_complete_count_hidden"
	
	fun pref(context : Context) : SharedPreferences {
		return context.getSharedPreferences("app_pref", Context.MODE_PRIVATE)
	}
	
	fun loadTargetUrl(pref : SharedPreferences, target_type : Int) : String {
		when(target_type) {
			TARGET_TYPE_FLASHAIR_AP -> return pref.getString(
				Pref.UI_TARGET_URL_FLASHAIR_AP,
				"http://flashair/"
			)
			
			TARGET_TYPE_FLASHAIR_STA -> return pref.getString(
				Pref.UI_TARGET_URL_FLASHAIR_STA,
				"http://flashair/"
			)
			
			TARGET_TYPE_PENTAX_KP -> return pref.getString(
				Pref.UI_TARGET_URL_PENTAX_KP,
				"http://192.168.0.1/"
			)
			
			TARGET_TYPE_PQI_AIR_CARD -> return pref.getString(
				Pref.UI_TARGET_URL_PQI_AIR_CARD,
				"http://192.168.1.1/"
			)
			
			TARGET_TYPE_PQI_AIR_CARD_TETHER -> return pref.getString(
				Pref.UI_TARGET_URL_PQI_AIR_CARD_TETHER,
				"http://AutoDetect/"
			)
			else -> return pref.getString(Pref.UI_TARGET_URL_FLASHAIR_AP, "http://flashair/")
		}
	}
	
	fun saveTargetUrl(edit : SharedPreferences.Editor, target_type : Int, value : String) {
		when(target_type) {
			TARGET_TYPE_FLASHAIR_AP -> edit.putString(Pref.UI_TARGET_URL_FLASHAIR_AP, value)
			
			TARGET_TYPE_FLASHAIR_STA -> edit.putString(Pref.UI_TARGET_URL_FLASHAIR_STA, value)
			
			TARGET_TYPE_PENTAX_KP -> edit.putString(Pref.UI_TARGET_URL_PENTAX_KP, value)
			TARGET_TYPE_PQI_AIR_CARD -> edit.putString(Pref.UI_TARGET_URL_PQI_AIR_CARD, value)
			TARGET_TYPE_PQI_AIR_CARD_TETHER -> edit.putString(
				Pref.UI_TARGET_URL_PQI_AIR_CARD_TETHER,
				value
			)
		}
	}
	
	fun initialize(context : Context) {
		val pref = pref(context)
		
		val e = pref.edit()
		var bChanged = false
		var sv : String?
		val iv : Int
		
		//
		sv = pref.getString(Pref.UI_INTERVAL, null)
		if(TextUtils.isEmpty(sv)) {
			bChanged = true
			e.putString(Pref.UI_INTERVAL, "30")
		}
		//
		sv = pref.getString(Pref.UI_FILE_TYPE, null)
		if(TextUtils.isEmpty(sv)) {
			bChanged = true
			e.putString(Pref.UI_FILE_TYPE, ".jp*")
		}
		//
		iv = pref.getInt(Pref.UI_LOCATION_MODE, - 1)
		if(iv < 0 || iv > LocationTracker.LOCATION_HIGH_ACCURACY) {
			bChanged = true
			e.putInt(Pref.UI_LOCATION_MODE, LocationTracker.DEFAULT_MODE)
		}
		//
		sv = pref.getString(Pref.UI_LOCATION_INTERVAL_DESIRED, null)
		if(TextUtils.isEmpty(sv)) {
			bChanged = true
			e.putString(
				Pref.UI_LOCATION_INTERVAL_DESIRED,
				java.lang.Long.toString(LocationTracker.DEFAULT_INTERVAL_DESIRED / 1000L)
			)
		}
		//
		sv = pref.getString(Pref.UI_LOCATION_INTERVAL_MIN, null)
		if(TextUtils.isEmpty(sv)) {
			bChanged = true
			e.putString(
				Pref.UI_LOCATION_INTERVAL_MIN,
				java.lang.Long.toString(LocationTracker.DEFAULT_INTERVAL_MIN / 1000L)
			)
		}
		//
		if(bChanged) e.apply()
	}
	
}
