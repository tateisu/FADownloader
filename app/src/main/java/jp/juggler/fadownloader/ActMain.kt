package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.TextView
import com.example.android.trivialdrivesample.util.IabHelper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.ResultCallback
import com.google.android.gms.location.*
import config.BuildVariant
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

open class ActMain : AppCompatActivity(), View.OnClickListener {
	
	companion object {
		
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
		internal const val TAG = "ActMain"
	}

	internal lateinit var tvStatus : TextView
	
	internal lateinit var pager : ViewPager
	private lateinit var pager_adapter : PagerAdapterBase
	private lateinit var mGoogleApiClient : GoogleApiClient
	
	internal lateinit var handler : Handler

	private var permission_alert : WeakReference<Dialog>? = null
	private var mLocationSettingsRequest : LocationSettingsRequest? = null
	
	private var is_resume = false
	
	internal var is_start = false
	
	private var mAdView : AdView? = null
	
	private var page_idx_setting : Int = 0
	private var page_idx_log : Int = 0
	private var page_idx_record : Int = 0
	private var page_idx_other : Int = 0
	
	/////////////////////////////////////////////////////////////////////////
	// アプリ権限の要求
	
	
	private val location_setting_callback : ResultCallback<LocationSettingsResult> =
		ResultCallback { locationSettingsResult ->
			val status = locationSettingsResult.status ?: return@ResultCallback
			val status_code = status.statusCode
			
			when(status_code) {
				LocationSettingsStatusCodes.SUCCESS -> startDownloadService()
				LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> if(Build.VERSION.SDK_INT <= 17) {
					
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
					try {
						// Show the dialog by calling startResolutionForResult(), and check the result
						// in onActivityResult().
						status.startResolutionForResult(this@ActMain, REQUEST_RESOLUTION)
					} catch(ex : IntentSender.SendIntentException) {
						ex.printStackTrace()
						Utils.showToast(this@ActMain, true, R.string.resolution_request_failed)
					}
					
				}
				LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> Utils.showToast(
					this@ActMain,
					true,
					R.string.location_setting_change_unavailable
				)
				else -> Utils.showToast(
					this@ActMain,
					true,
					R.string.location_setting_returns_unknown_status,
					status_code
				)
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
	
	
	private val connection_fail_callback : GoogleApiClient.OnConnectionFailedListener =
		GoogleApiClient.OnConnectionFailedListener { connectionResult ->
			val code = connectionResult.errorCode
			if(code == ConnectionResult.SERVICE_INVALID) {
				// Kindle端末で発生
				return@OnConnectionFailedListener
			}
			
			var msg = Utils.getConnectionResultErrorMessage(connectionResult)
			msg = getString(R.string.play_service_connection_failed, code, msg)
			Utils.showToast(this@ActMain, false, msg)
			
			if(code == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED) {
				try {
					val intent = Intent(Intent.ACTION_VIEW)
					intent.data = Uri.parse("market://details?id=com.google.android.gms")
					startActivity(intent)
				} catch(ex : Throwable) {
					ex.printStackTrace()
				}
				
			}
		}
	
	private val connection_callback : GoogleApiClient.ConnectionCallbacks =
		object : GoogleApiClient.ConnectionCallbacks {
			override fun onConnected(bundle : Bundle?) {
				permission_request()
			}
			
			// Playサービスとの接続が失われた
			override fun onConnectionSuspended(i : Int) {
				val msg = Utils.getConnectionSuspendedMessage(i)
				Utils.showToast(
					this@ActMain,
					false,
					R.string.play_service_connection_suspended,
					i,
					msg
				)
				// 再接続は自動で行われるらしい
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
				Log.d(
					TAG, "onQueryInventoryFinished: "
						+ result.response
						+ "," + result.message
				)
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
				Log.d(
					TAG, "onIabPurchaseFinished: "
						+ result.response
						+ "," + result.message
				)
				return@OnIabPurchaseFinishedListener
			}
			
			remove_ad(purchase.sku == REMOVE_AD_PRODUCT_ID)
		}
	
	override fun onClick(view : View) {
		when(view.id) {
			
			R.id.btnOnce -> download_start_button(false)
			
			R.id.btnRepeat -> download_start_button(true)
			
			R.id.btnStop -> download_stop_button()
			
			R.id.btnModeHelp -> openHelp(R.layout.help_mode)
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
		
		val e = Pref.pref(this).edit()
		e.putInt(Pref.UI_LAST_PAGE, pager.currentItem)
		
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
		
		mGoogleApiClient.connect()
		
		proc_status.run()
		
	}
	
	override fun onStop() {
		is_start = false
		
		if(mGoogleApiClient.isConnected) {
			mGoogleApiClient.disconnect()
		}
		
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
	public override fun onActivityResult(requestCode : Int, resultCode : Int, resultData : Intent) {
		// mIabHelper が結果を処理した
		if(mIabHelper != null && mIabHelper !!.handleActivityResult(
				requestCode,
				resultCode,
				resultData
			)) return
		
		if(requestCode == REQUEST_RESOLUTION) {
			if(resultCode == Activity.RESULT_OK) {
				startDownloadService()
			} else {
				Utils.showToast(this, true, "resolution request result: %s", resultCode)
			}
			
		} else if(requestCode == REQUEST_CODE_DOCUMENT) {
			if(resultCode == Activity.RESULT_OK) {
				if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
					try {
						val treeUri = resultData.data
						// 永続的な許可を取得
						contentResolver.takePersistableUriPermission(
							treeUri !!,
							Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						)
						// 覚えておく
						Pref.pref(this).edit()
							.putString(Pref.UI_FOLDER_URI, treeUri.toString())
							.apply()
					} catch(ex : Throwable) {
						ex.printStackTrace()
						Utils.showToast(this, ex, "folder access failed.")
					}
					
				}
			}
			val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
			page?.folder_view_update()
			return
		} else if(requestCode == REQUEST_FOLDER_PICKER) {
			if(resultCode == Activity.RESULT_OK) {
				try {
					val path = resultData.getStringExtra(FolderPicker.EXTRA_FOLDER)
					val dummy =
						Thread.currentThread().id.toString() + "." + android.os.Process.myPid()
					val test_dir = File(File(path), dummy)
					
					test_dir.mkdir()
					try {
						val test_file = File(test_dir, dummy)
						try {
							FileOutputStream(test_file).use { fos ->
								fos.write("TEST".encodeUTF8() )
							}
						} finally {
							test_file.delete()
						}
					} finally {
						test_dir.delete()
					}
					// 覚えておく
					Pref.pref(this).edit()
						.putString(Pref.UI_FOLDER_URI, path)
						.apply()
				} catch(ex : Throwable) {
					ex.printStackTrace()
					Utils.showToast(this, ex, "folder access failed.")
				}
				
			}
			val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
			page?.folder_view_update()
			return
		} else if(requestCode == REQUEST_SSID_PICKER) {
			if(resultCode == Activity.RESULT_OK) {
				val sv = resultData.getStringExtra(SSIDPicker.EXTRA_SSID)
				if(! TextUtils.isEmpty(sv)) {
					Pref.pref(this).edit()
						.putString(Pref.UI_SSID, sv)
						.apply()
				}
			}
			val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
			page?.ssid_view_update()
			return
		}
		
		super.onActivityResult(requestCode, resultCode, resultData)
		
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.act_main)
		
		//		ActionBar bar = getSupportActionBar();
		//		if( bar != null ){
		//			bar.setDisplayShowHomeEnabled( true );
		//			bar.setLogo( R.drawable.ic_app_logo );
		//			bar.setDisplayUseLogoEnabled( true );
		//		}
		
		setupIabHelper()
		
		mAdView = findViewById(R.id.adView)
		if(BuildVariant.AD_FREE) {
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
		pager.currentItem = Pref.pref(this).getInt(Pref.UI_LAST_PAGE, 0)
		
		mGoogleApiClient = GoogleApiClient.Builder(this)
			.addConnectionCallbacks(connection_callback)
			.addOnConnectionFailedListener(connection_fail_callback)
			.addApi(LocationServices.API)
			.build()
		
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
	
	override fun onCreateOptionsMenu(menu : Menu) : Boolean {
		menuInflater.inflate(R.menu.act_main, menu)
		return true
	}
	
	internal fun permission_request() {
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
		Pref.pref(this).edit()
			.putInt(Pref.LAST_MODE, Pref.LAST_MODE_STOP)
			.putLong(Pref.LAST_MODE_UPDATE, System.currentTimeMillis())
			.apply()
		val intent = Intent(this, DownloadService::class.java)
		stopService(intent)
		
		try {
			val pi = Utils.createAlarmPendingIntent(this)
			val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
			am.cancel(pi)
		} catch(ex : Throwable) {
			ex.printStackTrace()
		}
		
	}
	
	// 転送サービスを開始
	private fun download_start_button(repeat : Boolean) {
		
		val e = Pref.pref(this).edit()
		
		//repeat引数の値は、LocationSettingの確認が終わるまで覚えておく必要がある
		e.putBoolean(Pref.UI_REPEAT, repeat)
		
		// UIフォームの値を設定に保存
		val page = pager_adapter.getPage<PageSetting>(page_idx_setting)
		page?.ui_value_save(e)
		
		e.apply()
		
		when {
			// 位置情報を使わないオプションの時はLocationSettingをチェックしない
			Pref.pref(this).getInt(Pref.UI_LOCATION_MODE,- 1)
				== LocationTracker.NO_LOCATION_UPDATE -> startDownloadService()

			mGoogleApiClient.isConnected -> startLocationSettingCheck()

			else -> Utils.showToast(this, false, R.string.geo_tagging_not_enabled)
		}
	}
	
	private fun startLocationSettingCheck() {
		val UPDATE_INTERVAL_IN_MILLISECONDS : Long = 10000
		val FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
		
		val mLocationRequest = LocationRequest()
		mLocationRequest.interval = UPDATE_INTERVAL_IN_MILLISECONDS
		mLocationRequest.fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
		mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
		
		val builder = LocationSettingsRequest.Builder()
		builder.addLocationRequest(mLocationRequest)
		mLocationSettingsRequest = builder.build()
		
		val result = LocationServices.SettingsApi.checkLocationSettings(
			mGoogleApiClient,
			mLocationSettingsRequest
		)
		result.setResultCallback(location_setting_callback)
	}
	
	private fun startDownloadService() {
		val pref = Pref.pref(this)
		var sv : String?
		
		// LocationSettingを確認する前のrepeat引数の値を思い出す
		val repeat = pref.getBoolean(Pref.UI_REPEAT, false)
		
		// 設定から値を読んでバリデーション
		
		val target_type = pref.getInt(Pref.UI_TARGET_TYPE, - 1)
		if(target_type < 0) {
			Utils.showToast(this, true, getString(R.string.target_type_invalid))
			return
		}
		
		val target_url = Pref.loadTargetUrl(pref, target_type)
		if(TextUtils.isEmpty(target_url)) {
			Utils.showToast(this, true, getString(R.string.target_url_not_ok))
			return
		}
		
		var folder_uri : String? = null
		sv = pref.getString(Pref.UI_FOLDER_URI, null)
		if(! TextUtils.isEmpty(sv)) {
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
		if(TextUtils.isEmpty(folder_uri)) {
			Utils.showToast(this, true, getString(R.string.local_folder_not_ok))
			return
		}
		
		val interval = try {
			Integer.parseInt(pref.getString(Pref.UI_INTERVAL, "") !!.trim { it <= ' ' }, 10)
		} catch(ex : Throwable) {
			- 1
		}
		
		if(repeat && interval < 1) {
			Utils.showToast(this, true, getString(R.string.repeat_interval_not_ok))
			return
		}
		
		val file_type = pref.getString(Pref.UI_FILE_TYPE, "").trim { it <= ' ' }
		if(TextUtils.isEmpty(file_type)) {
			Utils.showToast(this, true, getString(R.string.file_type_empty))
			return
		}
		
		val location_mode = pref.getInt(Pref.UI_LOCATION_MODE, - 1)
		if(location_mode < 0 || location_mode > LocationTracker.LOCATION_HIGH_ACCURACY) {
			Utils.showToast(this, true, getString(R.string.location_mode_invalid))
			return
		}
		
		var location_update_interval_desired = LocationTracker.DEFAULT_INTERVAL_DESIRED
		var location_update_interval_min = LocationTracker.DEFAULT_INTERVAL_MIN
		
		if(location_mode != LocationTracker.NO_LOCATION_UPDATE) {
			location_update_interval_desired = try {
				1000L * java.lang.Long.parseLong(
					pref.getString(Pref.UI_LOCATION_INTERVAL_DESIRED, "") !!.trim { it <= ' ' }, 10
				)
			} catch(ex : Throwable) {
				- 1L
			}
			
			if(repeat && location_update_interval_desired < 1000L) {
				Utils.showToast(this, true, getString(R.string.location_update_interval_not_ok))
				return
			}
			
			location_update_interval_min = try {
				1000L * java.lang.Long.parseLong(
					pref.getString(Pref.UI_LOCATION_INTERVAL_MIN, "") !!.trim { it <= ' ' }, 10
				)
			} catch(ex : Throwable) {
				- 1L
			}
			
			if(repeat && location_update_interval_min < 1000L) {
				Utils.showToast(this, true, getString(R.string.location_update_interval_not_ok))
				return
			}
		}
		
		val force_wifi = pref.getBoolean(Pref.UI_FORCE_WIFI, false)
		
		val ssid : String
		if(! force_wifi) {
			ssid = ""
		} else {
			sv = pref.getString(Pref.UI_SSID, "")
			ssid = sv !!.trim { it <= ' ' }
			if(TextUtils.isEmpty(ssid)) {
				Utils.showToast(this, true, getString(R.string.ssid_empty))
				return
			}
		}
		
		val protected_only = pref.getBoolean(Pref.UI_PROTECTED_ONLY, false)
		val skip_already_download = pref.getBoolean(Pref.UI_SKIP_ALREADY_DOWNLOAD, false)
		
		// 最後に押したボタンを覚えておく
		pref.edit()
			.putInt(Pref.LAST_MODE, if(repeat) Pref.LAST_MODE_REPEAT else Pref.LAST_MODE_ONCE)
			.putLong(Pref.LAST_MODE_UPDATE, System.currentTimeMillis())
			.apply()
		
		// 転送サービスを開始
		val intent = Intent(this, DownloadService::class.java)
		intent.action = DownloadService.ACTION_START
		
		intent.putExtra(DownloadService.EXTRA_TARGET_TYPE, target_type)
		intent.putExtra(DownloadService.EXTRA_REPEAT, repeat)
		intent.putExtra(DownloadService.EXTRA_TARGET_URL, target_url)
		intent.putExtra(DownloadService.EXTRA_LOCAL_FOLDER, folder_uri)
		intent.putExtra(DownloadService.EXTRA_INTERVAL, interval)
		intent.putExtra(DownloadService.EXTRA_FILE_TYPE, file_type)
		
		intent.putExtra(
			DownloadService.EXTRA_LOCATION_INTERVAL_DESIRED,
			location_update_interval_desired
		)
		intent.putExtra(DownloadService.EXTRA_LOCATION_INTERVAL_MIN, location_update_interval_min)
		intent.putExtra(DownloadService.EXTRA_LOCATION_MODE, location_mode)
		intent.putExtra(DownloadService.EXTRA_FORCE_WIFI, force_wifi)
		intent.putExtra(DownloadService.EXTRA_SSID, ssid)
		intent.putExtra(DownloadService.EXTRA_PROTECTED_ONLY, protected_only)
		intent.putExtra(DownloadService.EXTRA_SKIP_ALREADY_DOWNLOAD, skip_already_download)
		
		startService(intent)
	}
	
	internal fun openHelp(layout_id : Int) {
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
		
		bRemoveAdPurchased = if(BuildVariant.AD_FREE) {
			true
		} else {
			Pref.pref(this).getBoolean(Pref.REMOVE_AD_PURCHASED, false)
		}
		
		
		if(! bRemoveAdPurchased) {
			try {
				mIabHelper = IabHelper(this, APP_PUBLIC_KEY)
				mIabHelper !!.startSetup(IabHelper.OnIabSetupFinishedListener { result ->
					// return if activity is destroyed
					if(mIabHelper == null) return@OnIabSetupFinishedListener
					
					if(! result.isSuccess) {
						Log.d(
							TAG, "onIabSetupFinished: "
								+ result.response
								+ "," + result.message
						)
						return@OnIabSetupFinishedListener
					}
					
					bSetupCompleted = true
					
					// セットアップが終わったら購入済みアイテムの確認を開始する
					mIabHelper !!.queryInventoryAsync(mGotInventoryListener)
				})
			} catch(ex : Throwable) {
				ex.printStackTrace()
				// 多分Google Playのない端末
			}
			
		}
	}
	
	// 購入開始
	fun startRemoveAdPurchase() {
		try {
			if(mIabHelper == null) {
				Utils.showToast(this, false, getString(R.string.play_store_missing))
			}
			
			mIabHelper !!.launchPurchaseFlow(
				this,
				REMOVE_AD_PRODUCT_ID,
				IabHelper.ITEM_TYPE_INAPP,
				REQUEST_PURCHASE,
				mPurchaseFinishedListener,
				null
			)
		} catch(ex : IllegalStateException) {
			ex.printStackTrace()
		}
		
	}
	
	internal fun remove_ad(isPurchased : Boolean) {
		bRemoveAdPurchased = isPurchased
		if(isPurchased) {
			if(mAdView != null) {
				(mAdView !!.parent as ViewGroup).removeView(mAdView)
				mAdView !!.destroy()
				mAdView = null
			}
		}
		
		val page = pager_adapter.getPage<PageOther>(page_idx_other)
		page?.updatePurchaseButton()
	}
	
	fun reloadDownloadRecord() {
		val page = pager_adapter.getPage<PageRecord>(page_idx_record)
		page?.viewer?.reload()
	}

}
