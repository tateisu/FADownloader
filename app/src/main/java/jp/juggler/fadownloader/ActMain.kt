package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.support.v4.app.ActivityCompat
import android.support.v4.provider.DocumentFile
import android.support.v4.view.ViewPager
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.*
import android.widget.TextView
import com.example.android.trivialdrivesample.util.IabHelper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.OnCompleteListener
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.picker.FolderPicker
import jp.juggler.fadownloader.picker.SSIDPicker
import jp.juggler.fadownloader.tracker.LocationTracker
import jp.juggler.fadownloader.util.*
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

open class ActMain : AppCompatActivity(), View.OnClickListener {
	
	companion object {
		private val log = LogTag("ActMain")
		
		internal const val REQUEST_CODE_PERMISSION = 1
		internal const val REQUEST_CODE_DOCUMENT = 2
		internal const val REQUEST_RESOLUTION = 3
		internal const val REQUEST_PURCHASE = 4
		internal const val REQUEST_FOLDER_PICKER = 5
		internal const val REQUEST_SSID_PICKER = 6
		const val REQUEST_CODE_SEND = 7
		
		internal const val LOADER_ID_LOG = 0
		internal const val LOADER_ID_RECORD = 1
		const val EXTRA_TAB = "tab"
		const val TAB_RECORD = "record"
		
		internal const val APP_PUBLIC_KEY =
			"MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkTbDT+kbberoRK6QHAKNzuKsFh0zSVJk97trga30ZHHyQHPsHtIJCvIibgHmm5QL6xr9TualN5iYMfNKA4bZM3x25kNiJ0NVuP86sravHdTyVuZyIu2WUI1CNdGRun5GYSGtxXNOuZujRkPtIMGjl750Z18CirrXYkl85KHDLgiOAu+d7HjssQ215+Qfo7iJIl30CYgcBl+szfH42MQK2Jd03LeTMf+5MA/ve/6iL2I1nyZrtWrC6Sw1uqOqjB9jx8cJALOrX+CmDa+si9krAI7gcOV/E8CJvVyC7cPxxooB425S8xHTr/MPjkEmwnu7ppMk5MyO+G1XP927fVg0ywIDAQAB"
		internal const val REMOVE_AD_PRODUCT_ID = "remove_ad"
	}
	
	internal lateinit var pref : SharedPreferences
	
	internal lateinit var tvStatus : TextView
	internal lateinit var handler : Handler
	
	private lateinit var pager : ViewPager
	private lateinit var pager_adapter : PagerAdapterBase
	private var mAdView : AdView? = null
	private var permission_alert : WeakReference<Dialog>? = null
	private var mLocationSettingsRequest : LocationSettingsRequest? = null
	
	private var is_resume = false
	internal var is_start = false
	
	private var page_idx_setting : Int = 0
	private var page_idx_log : Int = 0
	private var page_idx_record : Int = 0
	private var page_idx_other : Int = 0
	
	/////////////////////////////////////////////////////////////////////////
	// アプリ権限の要求
	
	private val location_setting_callback2 : OnCompleteListener<LocationSettingsResponse> =
		OnCompleteListener { task ->
			try {
				task.getResult(ApiException::class.java)
				// All location settings are satisfied.
				// The client can initialize location requests here.
				startDownloadService()
			} catch(apiException : ApiException) {
				when(apiException.statusCode) {
					LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
						
						if(Build.VERSION.SDK_INT <= 17) {
							
							// SH-02E(4.1.2),F10d(4.2.2)などで
							// Wi-Fiが無効だと RESOLUTION_REQUIRED を返すが、
							// STAモード前提だとWi-FiはOFFで正しい
							// startResolutionForResult で表示されるダイアログで
							// OKしてもキャンセルしても戻るボタンを押してもresultCodeが0を返す
							// ていうかGeoTagging modeをOFF以外のどれにしてもWi-FiをONにしろと警告が出る
							// これなら何もチェックせずにサービスを開始した方がマシ
							// なお、4.3のGalaxy Nexus ではこの問題は起きなかった
							
							startDownloadService()
							
						} else {
							// Location settings are not satisfied.
							// But could be fixed by showing the user a dialog.
							try {
								if(apiException !is ResolvableApiException) {
									// should not happen
								} else {
									// Show the dialog by calling startResolutionForResult(),
									// and check the result in onActivityResult().
									apiException.startResolutionForResult(
										this@ActMain,
										REQUEST_RESOLUTION
									)
								}
							} catch(ex : IntentSender.SendIntentException) {
								log.trace(ex, "resolution_request_failed")
								Utils.showToast(
									this@ActMain,
									true,
									R.string.resolution_request_failed
								)
							}
							
						}
					}
					
					LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
						// Location settings are not satisfied.
						// However, we have no way to fix the settings so we won't show the dialog.
						Utils.showToast(
							this@ActMain,
							true,
							R.string.location_setting_change_unavailable
						)
					}
					
					else -> {
						Utils.showToast(
							this@ActMain,
							true,
							R.string.location_setting_returns_unknown_status,
							apiException.statusCode
						)
					}
				}
			}
		}
	
	private val proc_status : Runnable = object : Runnable {
		override fun run() {
			handler.removeCallbacks(this)
			handler.postDelayed(this, 1000L)
			
			val status = DownloadService.getStatusForActivity(this@ActMain)
			tvStatus.text = status
		}
	}
	
	private var mIabHelper : IabHelper? = null
	internal var bSetupCompleted : Boolean = false
	internal var bRemoveAdPurchased : Boolean = false
	
	// 購入済みアイテム取得のコールバック
	private val mGotInventoryListener : IabHelper.QueryInventoryFinishedListener =
		IabHelper.QueryInventoryFinishedListener { result, inventory ->
			// return if activity is destroyed
			if(mIabHelper == null) return@QueryInventoryFinishedListener
			
			if(result.isFailure) {
				log.e("onQueryInventoryFinished: ${result.response},${result.message}")
				return@QueryInventoryFinishedListener
			}
			
			// 広告除去アイテムがあれば広告を非表示にする
			remove_ad(inventory.getPurchase(REMOVE_AD_PRODUCT_ID) != null)
		}
	
	// 購入結果の受け取り用メソッド
	private val mPurchaseFinishedListener : IabHelper.OnIabPurchaseFinishedListener =
		IabHelper.OnIabPurchaseFinishedListener { result, purchase ->
			// return if activity destroyed
			if(mIabHelper == null) return@OnIabPurchaseFinishedListener
			
			if(result.isFailure) {
				log.e("onIabPurchaseFinished: ${result.response},${result.message}")
				return@OnIabPurchaseFinishedListener
			}
			
			remove_ad(purchase.sku == REMOVE_AD_PRODUCT_ID)
		}
	
	override fun onClick(view : View) {
		when(view.id) {
			
			R.id.btnOnce -> download_start_button(false)
			
			R.id.btnRepeat -> download_start_button(true)
			
			R.id.btnStop -> download_stop_button()
			
			R.id.btnModeHelp -> openHelpLayout(R.layout.help_mode)
		}
	}
	
	override fun onOptionsItemSelected(item : MenuItem) : Boolean {
		return true
	}
	
	override fun onResume() {
		super.onResume()
		is_resume = true
		
		if(mAdView != null) {
			mAdView !!.resume()
		}
		
		val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
		if(page != null) {
			page.ui_value_load()
			page.folder_view_update()
		}
		
		permission_request()
	}
	
	override fun onPause() {
		is_resume = false
		
		if(mAdView != null) {
			mAdView !!.pause()
		}
		
		val e = pref.edit()
		e.put(Pref.uiLastPage, pager.currentItem)
		
		val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
		page?.ui_value_save(e)
		
		e.apply()
		
		super.onPause()
	}
	
	override fun onStart() {
		super.onStart()
		is_start = true
		
		val page = pager_adapter.getPage<PageLog>(page_idx_log)
		page?.onStart()
		
		val pageR = pager_adapter.getPage<PageRecord>(page_idx_record)
		pageR?.onStart()
		
		proc_status.run()
		
	}
	
	override fun onStop() {
		is_start = false
		
		
		handler.removeCallbacks(proc_status)
		
		val page = pager_adapter.getPage<PageLog>(page_idx_log)
		page?.onStop()
		
		val pageR = pager_adapter.getPage<PageRecord>(page_idx_record)
		pageR?.onStart()
		
		super.onStop()
	}
	
	override fun onRequestPermissionsResult(
		requestCode : Int,
		permissions : Array<String>,
		grantResults : IntArray
	) {
		when(requestCode) {
			REQUEST_CODE_PERMISSION -> permission_request()
		}
	}
	
	@SuppressLint("NewApi")
	public override fun onActivityResult(
		requestCode : Int,
		resultCode : Int,
		resultData : Intent?
	) {
		
		if(mIabHelper?.handleActivityResult(requestCode, resultCode, resultData) == true) {
			// mIabHelper が結果を処理した
			return
		}
		if(requestCode != Activity.RESULT_OK) {
			when(requestCode) {
				REQUEST_RESOLUTION -> Utils.showToast(
					this,
					true,
					"resolution request result: $resultCode"
				)
			}
		} else if(resultCode == Activity.RESULT_OK) {
			when(requestCode) {
				REQUEST_RESOLUTION -> startDownloadService()
				
				REQUEST_CODE_DOCUMENT -> {
					if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
						try {
							val treeUri = resultData?.data
							if(treeUri != null) {
								// 永続的な許可を取得
								contentResolver.takePersistableUriPermission(
									treeUri,
									Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
								)
								// 覚えておく
								pref.edit()
									.put(Pref.uiFolderUri, treeUri.toString())
									.apply()
							}
						} catch(ex : Throwable) {
							log.trace(ex, "folder access failed.")
							
							Utils.showToast(this, true, ex.withCaption("folder access failed."))
						}
						
					}
					val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
					page?.folder_view_update()
					return
				}
				
				REQUEST_FOLDER_PICKER -> {
					try {
						val path = resultData?.getStringExtra(FolderPicker.EXTRA_FOLDER)
						if(path != null) {
							val dummy =
								Thread.currentThread().id.toString() + "." + android.os.Process.myPid()
							val test_dir = File(File(path), dummy)
							
							test_dir.mkdir()
							try {
								val test_file = File(test_dir, dummy)
								try {
									FileOutputStream(test_file).use { fos ->
										fos.write("TEST".encodeUTF8())
									}
								} finally {
									test_file.delete()
								}
							} finally {
								test_dir.delete()
							}
							// 覚えておく
							pref.edit()
								.put(Pref.uiFolderUri, path)
								.apply()
						}
					} catch(ex : Throwable) {
						log.trace(ex, "folder access failed.")
						Utils.showToast(this, true, ex.withCaption("folder access failed."))
					}
					val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
					page?.folder_view_update()
					return
				}
				
				REQUEST_SSID_PICKER -> {
					val sv = resultData?.getStringExtra(SSIDPicker.EXTRA_SSID)
					if(sv?.isNotEmpty() == true) {
						pref.edit()
							.put(Pref.uiSsid, sv)
							.apply()
					}
					val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
					page?.ssid_view_update()
					return
				}
			}
		}
		
		super.onActivityResult(requestCode, resultCode, resultData)
		
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		
		this.pref = Pref.pref(this)
		
		setContentView(R.layout.act_main)
		
		//		ActionBar bar = getSupportActionBar();
		//		if( bar != null ){
		//			bar.setDisplayShowHomeEnabled( true );
		//			bar.setLogo( R.drawable.ic_app_logo );
		//			bar.setDisplayUseLogoEnabled( true );
		//		}
		
		setupIabHelper()
		
		mAdView = findViewById(R.id.adView)
		if(Pref.purchasedRemoveAd(pref)) {
			(mAdView !!.parent as ViewGroup).removeView(mAdView)
			mAdView = null
		} else {
			MobileAds.initialize(
				applicationContext,
				resources.getString(R.string.banner_ad_unit_id)
			)
			val adRequest = AdRequest.Builder().build()
			mAdView !!.loadAd(adRequest)
		}
		
		handler = Handler()
		
		findViewById<View>(R.id.btnStop).setOnClickListener(this)
		findViewById<View>(R.id.btnOnce).setOnClickListener(this)
		findViewById<View>(R.id.btnRepeat).setOnClickListener(this)
		findViewById<View>(R.id.btnModeHelp).setOnClickListener(this)
		
		tvStatus = findViewById(R.id.tvStatus)
		
		pager = findViewById(R.id.pager)
		
		pager_adapter = PagerAdapterBase(this)
		page_idx_setting = pager_adapter.addPage(
			getString(R.string.setting),
			R.layout.page_setting,
			PageSetting::class.java
		)
		page_idx_log =
			pager_adapter.addPage(getString(R.string.log), R.layout.page_log, PageLog::class.java)
		page_idx_record = pager_adapter.addPage(
			getString(R.string.download_record),
			R.layout.page_record,
			PageRecord::class.java
		)
		page_idx_other = pager_adapter.addPage(
			getString(R.string.other),
			R.layout.page_other,
			PageOther::class.java
		)
		pager.adapter = pager_adapter
		pager.currentItem = Pref.uiLastPage(pref)
		
		
		if(savedInstanceState == null) {
			handleIntent(intent)
		}
	}
	
	public override fun onDestroy() {
		if(mAdView != null) {
			mAdView !!.destroy()
		}
		if(mIabHelper != null) {
			mIabHelper !!.dispose()
			mIabHelper = null
		}
		
		super.onDestroy()
	}
	
	override fun onNewIntent(intent : Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}
	
	private fun handleIntent(intent : Intent?) {
		if(intent == null) return
		val sv = intent.getStringExtra(EXTRA_TAB)
		if(TAB_RECORD == sv) pager.currentItem = page_idx_record
	}
	
	private fun permission_request() {
		val missing_permission_list = PermissionChecker.getMissingPermissionList(this)
		if(! missing_permission_list.isEmpty()) {
			var dialog : Dialog?
			
			// 既にダイアログを表示中なら何もしない
			if(permission_alert != null) {
				dialog = permission_alert !!.get()
				if(dialog != null && dialog.isShowing) return
			}
			
			dialog = AlertDialog.Builder(this)
				.setMessage(R.string.app_permission_required)
				.setPositiveButton(R.string.request) { _, _ ->
					ActivityCompat.requestPermissions(
						this@ActMain,
						missing_permission_list.toTypedArray(),
						REQUEST_CODE_PERMISSION
					)
				}
				.setNegativeButton(R.string.cancel) { _, _ -> this@ActMain.finish() }
				.setNeutralButton(R.string.setting) { _, _ ->
					val intent = Intent()
					intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
					intent.data = Uri.parse("package:$packageName")
					startActivity(intent)
				}
				.setOnCancelListener { this@ActMain.finish() }
				.create()
			dialog !!.show()
			permission_alert = WeakReference(dialog)
			
		}
	}
	
	////////////////////////////////////////////////////////////
	
	// 転送サービスを停止
	private fun download_stop_button() {
		pref.edit()
			.put(Pref.lastMode, Pref.LAST_MODE_STOP)
			.put(Pref.lastModeUpdate, System.currentTimeMillis())
			.apply()
		val intent = Intent(this, DownloadService::class.java)
		stopService(intent)
		
		try {
			val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
			am?.cancel(Receiver1.piAlarm(this))
		} catch(ex : Throwable) {
			log.trace(ex, "createAlarmPendingIntent failed.")
		}
		
	}
	
	// 転送サービスを開始
	private fun download_start_button(repeat : Boolean) {
		
		val e = pref.edit()
		
		//repeat引数の値は、LocationSettingの確認が終わるまで覚えておく必要がある
		e.put(Pref.uiRepeat, repeat)
		
		// UIフォームの値を設定に保存
		val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
		page?.ui_value_save(e)
		
		e.apply()
		
		when {
			// 位置情報を使わないオプションの時はLocationSettingをチェックしない
			Pref.uiLocationMode(pref) == LocationTracker.NO_LOCATION_UPDATE ->
				startDownloadService()
			
			else -> startLocationSettingCheck()
		}
	}
	
	private fun startLocationSettingCheck() {
		val UPDATE_INTERVAL_IN_MILLISECONDS : Long = 10000
		val FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
		
		val mLocationRequest = LocationRequest()
		mLocationRequest.interval = UPDATE_INTERVAL_IN_MILLISECONDS
		mLocationRequest.fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
		mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
		
		mLocationSettingsRequest = LocationSettingsRequest.Builder()
			.addLocationRequest(mLocationRequest)
			.build()
		
		LocationServices.getSettingsClient(this)
			.checkLocationSettings(mLocationSettingsRequest)
			.addOnCompleteListener(location_setting_callback2)
	}
	
	private fun startDownloadService() {
		
		// LocationSettingを確認する前のrepeat引数の値を思い出す
		val repeat = Pref.uiRepeat(pref)
		
		// 設定から値を読んでバリデーション
		
		val target_type = Pref.uiTargetType(pref)
		if(target_type < 0) {
			Utils.showToast(this, true, getString(R.string.target_type_invalid))
			return
		}
		
		val target_url = Pref.loadTargetUrl(pref, target_type)
		if(target_url.isEmpty()) {
			Utils.showToast(this, true, getString(R.string.target_url_not_ok))
			return
		}
		
		var folder_uri = ""
		val sv = Pref.uiFolderUri(pref)
		if(sv.isNotEmpty()) {
			if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
				val folder = DocumentFile.fromTreeUri(this, Uri.parse(sv))
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
			Utils.showToast(this, true, getString(R.string.local_folder_not_ok))
			return
		}
		
		val file_type = Pref.uiFileType(pref).trim()
		if(file_type.isEmpty()) {
			Utils.showToast(this, true, getString(R.string.file_type_empty))
			return
		}
		
		val location_mode = Pref.uiLocationMode(pref)
		if(location_mode < 0 || location_mode > LocationTracker.LOCATION_HIGH_ACCURACY) {
			Utils.showToast(this, true, getString(R.string.location_mode_invalid))
			return
		}
		
		
		fun validSeconds(v : Int?) : Boolean {
			return v != null && v > 0
		}
		
		if(repeat) {
			if(! validSeconds(Pref.uiInterval.getIntOrNull(pref))) {
				Utils.showToast(this, true, getString(R.string.repeat_interval_not_ok))
				return
			}
		}
		
		if(location_mode != LocationTracker.NO_LOCATION_UPDATE) {
			
			if(! validSeconds(Pref.uiLocationIntervalDesired.getIntOrNull(pref))) {
				Utils.showToast(this, true, getString(R.string.location_update_interval_not_ok))
				return
			}
			if(! validSeconds(Pref.uiLocationIntervalMin.getIntOrNull(pref))) {
				Utils.showToast(this, true, getString(R.string.location_update_interval_not_ok))
				return
			}
		}
		
		val force_wifi = Pref.uiForceWifi(pref)
		
		val ssid : String
		if(! force_wifi) {
			ssid = ""
		} else {
			ssid = Pref.uiSsid(pref).trim()
			if(ssid.isEmpty()) {
				Utils.showToast(this, true, getString(R.string.ssid_empty))
				return
			}
		}
		
		// 最後に押したボタンを覚えておく
		pref.edit()
			.put(Pref.lastMode, if(repeat) Pref.LAST_MODE_REPEAT else Pref.LAST_MODE_ONCE)
			.put(Pref.lastModeUpdate, System.currentTimeMillis())
			.apply()
		
		// 転送サービスを開始
		val intent = Intent(this, DownloadService::class.java)
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
		
		
		startService(intent)
	}
	
	internal fun openHelpLayout(layout_id : Int) {
		val v = layoutInflater.inflate(layout_id, null, false)
		val d = Dialog(this)
		d.requestWindowFeature(Window.FEATURE_NO_TITLE)
		d.setContentView(v)
		
		d.window !!.setLayout(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT
		)
		d.show()
		v.findViewById<View>(R.id.btnClose).setOnClickListener { d.dismiss() }
	}
	
	internal fun openHelp(text : String) {
		val v = layoutInflater.inflate(R.layout.help_single_text, null, false)
		(v.findViewById<View>(R.id.text) as TextView).text = text
		
		val d = Dialog(this)
		d.requestWindowFeature(Window.FEATURE_NO_TITLE)
		d.setContentView(v)
		
		d.window !!.setLayout(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.WRAP_CONTENT
		)
		d.show()
		v.findViewById<View>(R.id.btnClose).setOnClickListener { d.dismiss() }
	}
	
	// onCreateから呼ばれる
	private fun setupIabHelper() {
		
		bRemoveAdPurchased = Pref.purchasedRemoveAd(pref)
		if(bRemoveAdPurchased) return
		
		try {
			val iabHelper = IabHelper(this, APP_PUBLIC_KEY)
			mIabHelper = iabHelper
			iabHelper.startSetup(IabHelper.OnIabSetupFinishedListener { result ->
				// return if activity is destroyed
				if(mIabHelper == null) return@OnIabSetupFinishedListener
				
				if(! result.isSuccess) {
					log.e("onIabSetupFinished: ${result.response},${result.message}")
					return@OnIabSetupFinishedListener
				}
				
				bSetupCompleted = true
				
				// セットアップが終わったら購入済みアイテムの確認を開始する
				mIabHelper?.queryInventoryAsync(mGotInventoryListener)
			})
		} catch(ex : Throwable) {
			log.trace(ex, "IabHelper failed.")
			// 多分Google Playのない端末
		}
	}
	
	// 購入開始
	fun startRemoveAdPurchase() {
		try {
			val iabHelper = mIabHelper
			if(iabHelper == null) {
				Utils.showToast(this, false, getString(R.string.play_store_missing))
			} else {
				iabHelper.launchPurchaseFlow(
					this,
					REMOVE_AD_PRODUCT_ID,
					IabHelper.ITEM_TYPE_INAPP,
					REQUEST_PURCHASE,
					mPurchaseFinishedListener,
					null
				)
				
			}
			
		} catch(ex : Throwable) {
			log.trace(ex, "startRemoveAdPurchase failed.")
		}
		
	}
	
	internal fun remove_ad(isPurchased : Boolean) {
		bRemoveAdPurchased = isPurchased
		if(isPurchased && mAdView != null) {
			(mAdView?.parent as? ViewGroup)?.removeView(mAdView)
			mAdView?.destroy()
			mAdView = null
		}
		
		val page = pager_adapter.getPage<PageOther>(page_idx_other)
		page?.updatePurchaseButton()
	}
	
	fun reloadDownloadRecord() {
		val page = pager_adapter.getPage<PageRecord>(page_idx_record)
		page?.viewer?.reload()
	}
	
}
