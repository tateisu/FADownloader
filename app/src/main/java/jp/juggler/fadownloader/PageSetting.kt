package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import android.view.View
import android.widget.*
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.picker.FolderPicker
import jp.juggler.fadownloader.picker.SSIDPicker
import jp.juggler.fadownloader.util.PagerAdapterBase

class PageSetting(activity : Activity, ignored : View) :
	PagerAdapterBase.PageViewHolder(activity, ignored), View.OnClickListener {
	
	private lateinit var spTargetType : Spinner
	private lateinit var spLocationMode : Spinner

	internal lateinit var etTargetUrl : EditText
	private lateinit var etFileType : EditText
	private lateinit var swForceWifi : Switch
	private lateinit var etSSID : EditText
	
	private lateinit var tvLocalFolder : TextView
	
	private lateinit var etInterval : EditText
	private lateinit var etLocationIntervalDesired : EditText
	private lateinit var etLocationIntervalMin : EditText
	private lateinit var etTetherSprayInterval : EditText
	private lateinit var etTetherTestConnectionTimeout : EditText
	private lateinit var etWifiChangeApInterval : EditText
	private lateinit var etWifiScanInterval : EditText
	
	
	private lateinit var swThumbnailAutoRotate : Switch
	private lateinit var swCopyBeforeViewSend : Switch
	private lateinit var swProtectedOnly : Switch
	private lateinit var swSkipAlreadyDownload : Switch
	private lateinit var swStopWhenTetheringOff : Switch
	
	private lateinit var btnSSIDPicker : View
	internal var bLoading : Boolean = false
	internal var last_target_type : Int = 0
	
	@Throws(Throwable::class)
	override fun onPageCreate(page_idx : Int, root : View) {
		bLoading = true
		last_target_type = - 1
		
		spTargetType = root.findViewById(R.id.spTargetType)
		etTargetUrl = root.findViewById(R.id.etTargetUrl)
		tvLocalFolder = root.findViewById(R.id.tvFolder)
		etInterval = root.findViewById(R.id.etRepeatInterval)
		etFileType = root.findViewById(R.id.etFileType)
		spLocationMode = root.findViewById(R.id.spLocationMode)
		etLocationIntervalDesired = root.findViewById(R.id.etLocationIntervalDesired)
		etLocationIntervalMin = root.findViewById(R.id.etLocationIntervalMin)
		swForceWifi = root.findViewById(R.id.swForceWifi)
		etSSID = root.findViewById(R.id.etSSID)
		swThumbnailAutoRotate = root.findViewById(R.id.swThumbnailAutoRotate)
		swCopyBeforeViewSend = root.findViewById(R.id.swCopyBeforeViewSend)
		btnSSIDPicker = root.findViewById(R.id.btnSSIDPicker)
		swProtectedOnly = root.findViewById(R.id.swProtectedOnly)
		swSkipAlreadyDownload = root.findViewById(R.id.swSkipAlreadyDownload)
		swStopWhenTetheringOff = root.findViewById(R.id.swStopWhenTetheringOff)
		etTetherSprayInterval = root.findViewById(R.id.etTetherSprayInterval)
		etTetherTestConnectionTimeout = root.findViewById(R.id.etTetherTestConnectionTimeout)
		etWifiChangeApInterval = root.findViewById(R.id.etWifiChangeApInterval)
		etWifiScanInterval = root.findViewById(R.id.etWifiScanInterval)
		
		
		root.findViewById<View>(R.id.btnFolderPicker).setOnClickListener(this)
		root.findViewById<View>(R.id.btnHelpFolderPicker).setOnClickListener(this)
		root.findViewById<View>(R.id.btnHelpTargetUrl).setOnClickListener(this)
		root.findViewById<View>(R.id.btnIntervalHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnFileTypeHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnLocationModeHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnLocationIntervalDesiredHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnLocationIntervalMinHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnForceWifiHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnSSIDHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnSSIDPicker).setOnClickListener(this)
		root.findViewById<View>(R.id.btnThumbnailAutoRotateHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnCopyBeforeViewSendHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnTargetTypeHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnHelpProtectedOnly).setOnClickListener(this)
		root.findViewById<View>(R.id.btnHelpSkipAlreadyDownload).setOnClickListener(this)
		
		root.findViewById<View>(R.id.btnTetherSprayIntervalHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnTetherTestConnectionTimeoutHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnWifiScanIntervalHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnWifiChangeApIntervalHelp).setOnClickListener(this)
		root.findViewById<View>(R.id.btnStopWhenTetheringOffHelp).setOnClickListener(this)
		
		val location_mode_adapter = ArrayAdapter<CharSequence>(
			activity, android.R.layout.simple_spinner_item
		)
		location_mode_adapter.setDropDownViewResource(R.layout.spinner_dropdown)
		
		location_mode_adapter.addAll(
			activity.getString(R.string.location_mode_0),
			activity.getString(R.string.location_mode_1),
			activity.getString(R.string.location_mode_2),
			activity.getString(R.string.location_mode_3),
			activity.getString(R.string.location_mode_4)
		)
		spLocationMode.adapter = location_mode_adapter
		spLocationMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(
				parent : AdapterView<*>,
				view : View,
				position : Int,
				id : Long
			) {
				updateFormEnabled()
			}
			
			override fun onNothingSelected(parent : AdapterView<*>) {
				updateFormEnabled()
			}
		}
		
		val target_type_adapter = ArrayAdapter<CharSequence>(
			activity, android.R.layout.simple_spinner_item
		)
		target_type_adapter.setDropDownViewResource(R.layout.spinner_dropdown)
		target_type_adapter.addAll(
			activity.getString(R.string.target_type_flashair_ap),
			activity.getString(R.string.target_type_flashair_sta),
			activity.getString(R.string.target_type_pentax_kp),
			activity.getString(R.string.target_type_pqi_air_card),
			activity.getString(R.string.target_type_pqi_air_card_tether)
		)
		spTargetType.adapter = target_type_adapter
		spTargetType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(
				parent : AdapterView<*>,
				view : View,
				position : Int,
				id : Long
			) {
				if(bLoading) return
				if(last_target_type >= 0) {
					val e = Pref.pref(activity).edit()
					Pref.saveTargetUrl(e, last_target_type, etTargetUrl.text.toString())
					e.apply()
				}
				last_target_type = position
				if(last_target_type >= 0) {
					// targetTypeごとに異なるURLをロードする
					val sv = Pref.loadTargetUrl(Pref.pref(activity), last_target_type)
					etTargetUrl.setText(sv)
				}
				updateFormEnabled()
			}
			
			override fun onNothingSelected(parent : AdapterView<*>) {
				if(bLoading) return
				if(last_target_type >= 0) {
					val e = Pref.pref(activity).edit()
					Pref.saveTargetUrl(e, last_target_type, etTargetUrl.text.toString())
					e.apply()
				}
				last_target_type = - 1
				updateFormEnabled()
			}
		}
		
		swForceWifi.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, _ ->
			if(bLoading) return@OnCheckedChangeListener
			updateFormEnabled()
		})
		
		swThumbnailAutoRotate.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isChecked ->
			if(bLoading) return@OnCheckedChangeListener
			Pref.pref(activity).edit().put(Pref.uiAutoRotateThumbnail, isChecked).apply()
			(activity as ActMain).reloadDownloadRecord()
			updateFormEnabled()
		})

		swCopyBeforeViewSend.setOnCheckedChangeListener { _, isChecked ->
			Pref.pref(activity).edit().put(Pref.uiCopyBeforeSend, isChecked).apply()
			updateFormEnabled()
		}
		
		val tvWifiScanInterval :TextView = root.findViewById(R.id.tvWifiScanInterval)
		tvWifiScanInterval.visibility = if( Build.VERSION.SDK_INT >= 26){
			View.VISIBLE
		}else{
			View.GONE
		}
		
		ui_value_load()
		folder_view_update()
	}
	
	@Throws(Throwable::class)
	override fun onPageDestroy(page_idx : Int, root : View) {
		val e = Pref.pref(activity).edit()
		ui_value_save(e)
		e.apply()
	}
	
	private fun openHelp(@StringRes stringId:Int){
		(activity as ActMain).openHelp(activity.getString(stringId))
	}
	
	override fun onClick(view : View) {
		when(view.id) {
			R.id.btnFolderPicker -> folder_pick()
			R.id.btnSSIDPicker -> ssid_pick()
			
			R.id.btnHelpFolderPicker -> if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
				(activity as ActMain).openHelpLayout(R.layout.help_local_folder)
			} else {
				openHelp(R.string.local_folder_help_kitkat)
			}
			R.id.btnHelpTargetUrl -> openHelp(R.string.target_url_help)
			R.id.btnIntervalHelp -> openHelp(R.string.repeat_interval_help_text)
			R.id.btnFileTypeHelp -> openHelp(R.string.file_type_help)
			R.id.btnLocationModeHelp -> openHelp(R.string.geo_tagging_mode_help)
			
			R.id.btnForceWifiHelp ->openHelp(R.string.force_wifi_help)
			R.id.btnSSIDHelp -> openHelp(R.string.wifi_ap_ssid_help)
			R.id.btnThumbnailAutoRotateHelp -> openHelp(R.string.thumbnail_auto_rotate_help)
			R.id.btnCopyBeforeViewSendHelp -> openHelp(R.string.copy_before_view_send_help)
			R.id.btnTargetTypeHelp -> openHelp(R.string.target_type_help)
			R.id.btnHelpProtectedOnly -> openHelp(R.string.protected_only_help)
			R.id.btnHelpSkipAlreadyDownload -> openHelp(R.string.skip_already_downloaded_help)
			
			R.id.btnLocationIntervalDesiredHelp ->openHelp(R.string.location_interval_desired_help)
			R.id.btnLocationIntervalMinHelp ->openHelp(R.string.location_interval_min_help)
			R.id.btnTetherSprayIntervalHelp ->openHelp(R.string.tether_spray_interval_help)
			R.id.btnTetherTestConnectionTimeoutHelp -> openHelp(R.string.tether_test_connection_timeout_help)
			R.id.btnWifiScanIntervalHelp -> openHelp(R.string.wifi_scan_interval_help)
			R.id.btnWifiChangeApIntervalHelp -> openHelp(R.string.wifi_change_ap_interval_help)
			R.id.btnStopWhenTetheringOffHelp -> openHelp(R.string.stop_when_tethering_off_help)
		}
	}
	
	// UIフォームの値を設定から読み出す
	internal fun ui_value_load() {
		bLoading = true
		
		val pref = Pref.pref(activity)
		
		// boolean
		swForceWifi.isChecked = Pref.uiForceWifi(pref)
		swThumbnailAutoRotate.isChecked = Pref.uiAutoRotateThumbnail(pref)
		swCopyBeforeViewSend.isChecked = Pref.uiCopyBeforeSend(pref)
		swProtectedOnly.isChecked = Pref.uiProtectedOnly(pref)
		swSkipAlreadyDownload.isChecked = Pref.uiSkipAlreadyDownload(pref)
		swStopWhenTetheringOff.isChecked = Pref.uiStopWhenTetheringOff(pref)
		
		// string
		etInterval.setText(Pref.uiInterval(pref))
		etFileType.setText(Pref.uiFileType(pref))
		etLocationIntervalDesired.setText(Pref.uiLocationIntervalDesired(pref))
		etLocationIntervalMin.setText( Pref.uiLocationIntervalMin( pref))
		etSSID.setText(Pref.uiSsid(pref))
		etTetherSprayInterval.setText(Pref.uiTetherSprayInterval(pref))
		etTetherTestConnectionTimeout.setText(Pref.uiTetherTestConnectionTimeout(pref))
		etWifiChangeApInterval.setText(Pref.uiWifiChangeApInterval(pref))
		etWifiScanInterval.setText(Pref.uiWifiScanInterval(pref))
		
		
		
		
		// integer
		var iv = Pref.uiTargetType(pref)
		if( iv !in 0 until spTargetType.count ){
			iv = 0
		}
		last_target_type = iv
		spTargetType.setSelection(iv)
		etTargetUrl.setText(Pref.loadTargetUrl(pref, iv))
		
		//
		iv = Pref.uiLocationMode(pref)
		if(iv >= 0 && iv < spLocationMode.count) spLocationMode.setSelection(iv)
		
		//
		updateFormEnabled()
		bLoading = false
	}
	
	// UIフォームの値を設定ファイルに保存
	internal fun ui_value_save(e : SharedPreferences.Editor) {
		
		val target_type = spTargetType.selectedItemPosition
		if(target_type >= 0 && target_type < spTargetType.count) {
			Pref.saveTargetUrl(e, target_type, etTargetUrl.text.toString())
		}
		
		e
			.put(Pref.uiTargetType, spTargetType.selectedItemPosition)
			.put(Pref.uiInterval, etInterval.text.toString())
			.put(Pref.uiTetherSprayInterval, etTetherSprayInterval.text.toString())
			.put(Pref.uiTetherTestConnectionTimeout, etTetherTestConnectionTimeout.text.toString())
			.put(Pref.uiWifiChangeApInterval, etWifiChangeApInterval.text.toString())
			.put(Pref.uiWifiScanInterval, etWifiScanInterval.text.toString())
			.put(Pref.uiFileType, etFileType.text.toString())
			.put(Pref.uiLocationMode, spLocationMode.selectedItemPosition)
			.put(Pref.uiLocationIntervalDesired, etLocationIntervalDesired.text.toString())
			.put(Pref.uiLocationIntervalMin, etLocationIntervalMin.text.toString())
			.put(Pref.uiForceWifi, swForceWifi.isChecked)
			.put(Pref.uiSsid, etSSID.text.toString())
			.put(Pref.uiProtectedOnly, swProtectedOnly.isChecked)
			.put(Pref.uiSkipAlreadyDownload, swSkipAlreadyDownload.isChecked)
			.put(Pref.uiStopWhenTetheringOff, swStopWhenTetheringOff.isChecked)
		
		// .apply() は呼び出し側で行う
	}
	
	private fun updateFormEnabled() {
		
		val target_type = spTargetType.selectedItemPosition
		
		val location_enabled = spLocationMode.selectedItemPosition > 0
		etLocationIntervalDesired.isEnabled = location_enabled
		etLocationIntervalMin.isEnabled = location_enabled
		
		val force_wifi_enabled = when(target_type) {
			Pref.TARGET_TYPE_FLASHAIR_STA, Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER -> false
			else -> true
		}
		swForceWifi.isEnabled = force_wifi_enabled
		//
		val ssid_enabled = force_wifi_enabled && swForceWifi.isChecked
		etSSID.isEnabled = ssid_enabled
		btnSSIDPicker.isEnabled = ssid_enabled
		
		swProtectedOnly.isEnabled = when(target_type) {
			Pref.TARGET_TYPE_FLASHAIR_AP, Pref.TARGET_TYPE_FLASHAIR_STA -> true
			else -> false
		}
		
	}
	
	// 転送先フォルダの選択を開始
	private fun folder_pick() {
		if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
			@SuppressLint("InlinedApi") val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
			activity.startActivityForResult(intent, ActMain.REQUEST_CODE_DOCUMENT)
		} else {
			FolderPicker.open(
				activity,
				ActMain.REQUEST_FOLDER_PICKER,
				tvLocalFolder.text.toString()
			)
			
		}
	}
	
	// フォルダの表示を更新
	internal fun folder_view_update() {
		val sv = Pref.uiFolderUri(Pref.pref(activity))
		val name = when {
			sv.isEmpty() -> null
			Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION -> {
				val folder = DocumentFile.fromTreeUri(activity, Uri.parse(sv))
				if(folder != null && folder.exists() && folder.canWrite()) {
					folder.name
				}else{
					null
				}
			}
			else -> sv
		}
		
		tvLocalFolder.text = when {
			name?.isEmpty() != false -> activity.getString(R.string.not_selected)
			else -> name
		}
	}
	
	private fun ssid_pick() {
		SSIDPicker.open(activity, ActMain.REQUEST_SSID_PICKER)
		
	}
	
	fun ssid_view_update() {
		val pref = Pref.pref(activity)
		etSSID.setText(Pref.uiSsid(pref))
	}
}
