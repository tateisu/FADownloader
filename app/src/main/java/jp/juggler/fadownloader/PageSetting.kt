package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.support.v4.provider.DocumentFile
import android.view.View
import android.widget.*
import jp.juggler.fadownloader.model.LocalFile

class PageSetting(activity : Activity, ignored : View) :
	PagerAdapterBase.PageViewHolder(activity, ignored), View.OnClickListener {
	
	internal lateinit var spTargetType : Spinner
	internal lateinit var etTargetUrl : EditText
	private lateinit var tvLocalFolder : TextView
	private lateinit var etInterval : EditText
	internal lateinit var etFileType : EditText
	internal lateinit var spLocationMode : Spinner
	internal lateinit var etLocationIntervalDesired : EditText
	internal lateinit var etLocationIntervalMin : EditText
	internal lateinit var swForceWifi : Switch
	internal lateinit var etSSID : EditText
	internal lateinit var swThumbnailAutoRotate : Switch
	internal lateinit var swCopyBeforeViewSend : Switch
	internal lateinit var swProtectedOnly : Switch
	internal lateinit var swSkipAlreadyDownload : Switch
	
	internal lateinit var btnSSIDPicker : View
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

		ui_value_load()
		folder_view_update()
	}
	
	@Throws(Throwable::class)
	override fun onPageDestroy(page_idx : Int, root : View) {
		val e = Pref.pref(activity).edit()
		ui_value_save(e)
		e.apply()
	}
	
	override fun onClick(view : View) {
		when(view.id) {
			R.id.btnFolderPicker -> folder_pick()
			R.id.btnSSIDPicker -> ssid_pick()
			
			R.id.btnHelpFolderPicker -> if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
				(activity as ActMain).openHelp(R.layout.help_local_folder)
			} else {
				(activity as ActMain).openHelp(activity.getString(R.string.local_folder_help_kitkat))
			}
			R.id.btnHelpTargetUrl -> (activity as ActMain).openHelp(activity.getString(R.string.target_url_help))
			R.id.btnIntervalHelp -> (activity as ActMain).openHelp(activity.getString(R.string.repeat_interval_help_text))
			R.id.btnFileTypeHelp -> (activity as ActMain).openHelp(activity.getString(R.string.file_type_help))
			R.id.btnLocationModeHelp -> (activity as ActMain).openHelp(activity.getString(R.string.geo_tagging_mode_help))
			R.id.btnLocationIntervalDesiredHelp -> (activity as ActMain).openHelp(
				activity.getString(
					R.string.help_location_interval_desired
				)
			)
			R.id.btnLocationIntervalMinHelp -> (activity as ActMain).openHelp(activity.getString(R.string.help_location_interval_min))
			R.id.btnForceWifiHelp -> (activity as ActMain).openHelp(activity.getString(R.string.force_wifi_help))
			R.id.btnSSIDHelp -> (activity as ActMain).openHelp(activity.getString(R.string.wifi_ap_ssid_help))
			R.id.btnThumbnailAutoRotateHelp -> (activity as ActMain).openHelp(activity.getString(R.string.help_thumbnail_auto_rotate))
			R.id.btnCopyBeforeViewSendHelp -> (activity as ActMain).openHelp(activity.getString(R.string.help_copy_before_view_send))
			R.id.btnTargetTypeHelp -> (activity as ActMain).openHelp(activity.getString(R.string.target_type_help))
			R.id.btnHelpProtectedOnly -> (activity as ActMain).openHelp(activity.getString(R.string.protected_only_help))
			R.id.btnHelpSkipAlreadyDownload -> (activity as ActMain).openHelp(activity.getString(R.string.skip_already_downloaded_help))
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
		
		// string
		etInterval.setText(Pref.uiInterval(pref))
		etFileType.setText(Pref.uiFileType(pref))
		etLocationIntervalDesired.setText(Pref.uiLocationIntervalDesired(pref))
		etLocationIntervalMin.setText( Pref.uiLocationIntervalMin( pref))
		etSSID.setText(Pref.uiSsid(pref))
		
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
			.put(Pref.uiFileType, etFileType.text.toString())
			.put(Pref.uiLocationMode, spLocationMode.selectedItemPosition)
			.put(Pref.uiLocationIntervalDesired, etLocationIntervalDesired.text.toString())
			.put(Pref.uiLocationIntervalMin, etLocationIntervalMin.text.toString())
			.put(Pref.uiForceWifi, swForceWifi.isChecked)
			.put(Pref.uiSsid, etSSID.text.toString())
			.put(Pref.uiProtectedOnly, swProtectedOnly.isChecked)
			.put(Pref.uiSkipAlreadyDownload, swSkipAlreadyDownload.isChecked)
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
