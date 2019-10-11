package jp.juggler.fadownloader

import android.app.AlarmManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

import org.apache.commons.io.IOUtils

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

import it.sephiroth.android.library.exif2.ExifInterface
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.model.ScanItem
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.targets.FlashAir
import jp.juggler.fadownloader.targets.PentaxKP
import jp.juggler.fadownloader.targets.PqiAirCard
import jp.juggler.fadownloader.tracker.LocationTracker
import jp.juggler.fadownloader.tracker.NetworkTracker
import jp.juggler.fadownloader.util.*

class DownloadWorker : WorkerBase {
	
	companion object {
		
		@Suppress("ConstantCondition")
		const val RECORD_QUEUED_STATE = false
		
		private val reJPEG = Pattern.compile("\\.jp(g|eg?)\\z", Pattern.CASE_INSENSITIVE)
		internal val reFileType = Pattern.compile("(\\S+)")
		
		internal const val MACRO_WAIT_UNTIL = "%WAIT_UNTIL%"
		
		fun x1000Safe(v : Int) : Long {
			return if(v > 0) {
				v.toLong() * 1000L
			} else {
				1000L
			}
		}
	}
	
	interface Callback {
		
		val location : Location?
		
		fun releaseWakeLock()
		
		fun acquireWakeLock()
		
		fun onThreadStart()
		
		fun onThreadEnd(worker : DownloadWorker, complete_and_no_repeat : Boolean)
		
		fun onAllFileCompleted(count : Long)
		
		fun hasHiddenDownloadCount() : Boolean
	}
	
	val callback : Callback
	
	internal var disposed = false
	
	private val service : DownloadService
	
	private val repeat : Boolean
	var target_url : String = ""
	val folder_uri : String
	val intervalSeconds : Int
	private val file_type : String
	val log : LogWriter
	val file_type_list : ArrayList<Pattern>
	val target_type : Int
	private val location_setting : LocationTracker.Setting
	private val network_setting : NetworkTracker.Setting
	val protected_only : Boolean
	private val skip_already_download : Boolean
	
	val client = HTTPClient(30000, 4, "HTTP Client", this)
	
	val isTetheringType : Boolean
		get() = when(target_type) {
			Pref.TARGET_TYPE_FLASHAIR_STA, Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER -> true
			else -> false
		}
	
	// SSID強制が指定されていない、または接続中のWi-FiのSSIDが指定されたものと同じなら真
	private val isValidSsid : Boolean
		get() = if(! network_setting.force_wifi) {
			true
		} else {
			val wm =
				service.applicationContext.getSystemService(Context.WIFI_SERVICE)
					as? WifiManager
			network_setting.target_ssid == wm?.connectionInfo?.ssid?.filterSsid()
		}
	
	@Suppress("DEPRECATION")
	val wifiNetwork : Any?
		get() {
			val cm =
				service.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
			when {
				cm == null -> {
				}
				
				Build.VERSION.SDK_INT >= 21 -> for(n in cm.allNetworks) {
					try {
						val info = cm.getNetworkInfo(n)
						if(info.isConnected && info.type == ConnectivityManager.TYPE_WIFI) {
							if(isValidSsid) return n
						}
					} catch(ex : Throwable) {
						// クラッシュレポートによるとこのあたりで java.lang.IllegalStateException が発生するらしい
						// Platform type の利用でnull例外が起きてるのだろう
						log.trace(ex)
					}
				}
				
				else -> for(info in cm.allNetworkInfo) {
					if(info.isConnected && info.type == ConnectivityManager.TYPE_WIFI) {
						if(isValidSsid) return info
					}
				}
			}
			return null
		}
	
	private val cv = ContentValues()
	var file_error = false
	
	private var complete_and_no_repeat = false
	var job_queue : ScanItem.Queue? = null
	
	private var queued_byte_count_max = AtomicLong()
	
	private var queued_file_count = AtomicLong()
	private var queued_byte_count = AtomicLong()
	
	private val _status = AtomicReference("?")
	private val wait_until = AtomicLong()
	
	val status : String?
		get() {
			val fc = queued_file_count.get()
			val s = _status.get()
			return when {
				fc > 0L -> {
					val bc = queued_byte_count.get()
					val bcm = queued_byte_count_max.get()
					"$s\n${service.getString(R.string.progress)} ${if(bcm <= 0) 0 else 100L * (bcm - bc) / bcm}%, ${service.getString(
						R.string.remain
					)} ${fc}file ${Utils.formatBytes(bc)}byte"
					
				}
				
				s?.contains(MACRO_WAIT_UNTIL) == true -> {
					var remain = wait_until.get() - SystemClock.elapsedRealtime()
					if(remain < 0L) remain = 0L
					s.replace(MACRO_WAIT_UNTIL, Utils.formatTimeDuration(remain))
				}
				
				else -> s
			}
		}
	
	constructor(service : DownloadService, intent : Intent, callback : Callback) {
		this.service = service
		this.callback = callback
		this.log = LogWriter(service)
		
		log.i(R.string.thread_ctor_params)
		
		this.target_type = Pref.uiTargetType(intent)
		this.target_url = intent.getStringExtra(DownloadService.EXTRA_TARGET_URL)
		this.file_type = Pref.uiFileType(intent).trim()
		
		this.folder_uri = Pref.uiFolderUri(intent)
		this.repeat = Pref.uiRepeat(intent)
		this.intervalSeconds = Pref.uiInterval.getInt(intent)
		this.protected_only = Pref.uiProtectedOnly(intent)
		this.skip_already_download = Pref.uiSkipAlreadyDownload(intent)
		
		this.network_setting = NetworkTracker.Setting(
			force_wifi = Pref.uiForceWifi(intent),
			target_ssid = Pref.uiSsid(intent),
			target_type = this.target_type,
			target_url = this.target_url,
			
			tetherSprayInterval = x1000Safe(Pref.uiTetherSprayInterval.getInt(intent)),
			tetherTestConnectionTimeout = x1000Safe(Pref.uiTetherTestConnectionTimeout.getInt(intent)),
			wifiChangeApInterval = x1000Safe(Pref.uiWifiChangeApInterval.getInt(intent)),
			wifiScanInterval = x1000Safe(Pref.uiWifiScanInterval.getInt(intent)),
			
			stopWhenTetheringOff = Pref.uiStopWhenTetheringOff(intent)
		)
		
		this.location_setting = LocationTracker.Setting(
			mode = Pref.uiLocationMode(intent),
			interval_desired = x1000Safe(Pref.uiLocationIntervalDesired.getInt(intent)),
			interval_min = x1000Safe(Pref.uiLocationIntervalMin.getInt(intent))
		)
		
		Pref.pref(service).edit()
			.put(Pref.workerRepeat, repeat)
			.put(Pref.workerTargetType, target_type)
			.put(Pref.workerTargetUrl, target_url)
			.put(Pref.workerFolderUri, folder_uri)
			.put(Pref.workerInterval, intervalSeconds)
			.put(Pref.workerFileType, file_type)
			.put(Pref.workerLocationIntervalDesired, location_setting.interval_desired)
			.put(Pref.workerLocationIntervalMin, location_setting.interval_min)
			.put(Pref.workerLocationMode, location_setting.mode)
			.put(Pref.workerForceWifi, network_setting.force_wifi)
			.put(Pref.workerSsid, network_setting.target_ssid)
			.put(Pref.workerProtectedOnly, protected_only)
			.put(Pref.workerSkipAlreadyDownload, skip_already_download)
			.put(Pref.workerTetherSprayInterval, network_setting.tetherSprayInterval)
			.put(
				Pref.workerTetherTestConnectionTimeout,
				network_setting.tetherTestConnectionTimeout
			)
			.put(Pref.workerWifiChangeApInterval, network_setting.wifiChangeApInterval)
			.put(Pref.workerWifiScanInterval, network_setting.wifiScanInterval)
			.put(Pref.workerStopWhenTetheringOff, network_setting.stopWhenTetheringOff)
			.apply()
		
		this.file_type_list = file_type_parse()
		
		init()
		
	}
	
	constructor(service : DownloadService, cause : String, callback : Callback) {
		this.service = service
		this.callback = callback
		this.log = LogWriter(service)
		
		log.i(R.string.thread_ctor_restart, cause)
		val pref = Pref.pref(service)
		this.repeat = Pref.workerRepeat(pref)
		this.target_url = Pref.workerTargetUrl(pref)
		this.folder_uri = Pref.workerFolderUri(pref)
		this.intervalSeconds = Pref.workerInterval(pref)
		this.file_type = Pref.workerFileType(pref)
		this.target_type = Pref.workerTargetType(pref)
		this.protected_only = Pref.workerProtectedOnly(pref)
		this.skip_already_download = Pref.workerSkipAlreadyDownload(pref)
		
		this.network_setting = NetworkTracker.Setting(
			force_wifi = Pref.workerForceWifi(pref),
			target_ssid = Pref.workerSsid(pref),
			target_type = this.target_type,
			target_url = this.target_url,
			
			tetherSprayInterval = Pref.workerTetherSprayInterval(pref),
			tetherTestConnectionTimeout = Pref.workerTetherTestConnectionTimeout(pref),
			wifiChangeApInterval = Pref.workerWifiChangeApInterval(pref),
			wifiScanInterval = Pref.workerWifiScanInterval(pref),
			
			stopWhenTetheringOff = Pref.workerStopWhenTetheringOff(pref)
		)
		
		this.location_setting = LocationTracker.Setting(
			mode = Pref.workerLocationMode(pref),
			interval_desired = Pref.workerLocationIntervalDesired(pref),
			interval_min = Pref.workerLocationIntervalMin(pref)
		)
		
		this.file_type_list = file_type_parse()
		
		init()
		
	}
	
	private fun init() {
		service.wifi_tracker.updateSetting(network_setting)
		service.location_tracker.updateSetting(location_setting)
	}
	
	override fun cancel(reason : String) : Boolean {
		val rv = super.cancel(reason)
		if(rv) log.i(R.string.thread_cancelled, reason)
		try {
			client.cancel(log)
		} catch(ex : Throwable) {
			log.trace(ex, "cancel failed")
		}
		
		return rv
	}
	
	private fun file_type_parse() : ArrayList<Pattern> {
		val list = ArrayList<Pattern>()
		val m = reFileType.matcher(file_type)
		while(m.find()) {
			try {
				val spec = m.group(1).replace("(\\W)".toRegex(), "\\\\$1")
					.replace("\\\\\\*".toRegex(), ".*?")
				list.add(Pattern.compile("$spec\\z", Pattern.CASE_INSENSITIVE))
			} catch(ex : Throwable) {
				log.trace(ex, "file_type_parse failed.")
				log.e(
					R.string.file_type_parse_error,
					m.group(1),
					ex.javaClass.simpleName,
					ex.message
				)
			}
			
		}
		return list
	}
	
	fun setShortWait(remain : Long) {
		wait_until.set(SystemClock.elapsedRealtime() + remain)
		setStatus(false, service.getString(R.string.wait_short, MACRO_WAIT_UNTIL))
		waitEx(remain)
	}
	
	fun setAlarm(now : Long, remain : Long) {
		wait_until.set(SystemClock.elapsedRealtime() + remain)
		
		try {
			val pi = Receiver1.piAlarm(service)
			val am =
				service.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
			when {
				am == null -> throw IllegalStateException("missing AlarmManager")
				Build.VERSION.SDK_INT >= 21 -> am.setAlarmClock(
					AlarmManager.AlarmClockInfo(
						now + remain,
						pi
					), pi
				)
				Build.VERSION.SDK_INT >= 19 -> am.setExact(
					AlarmManager.RTC_WAKEUP,
					now + remain,
					pi
				)
				else -> am.set(AlarmManager.RTC_WAKEUP, now + remain, pi)
			}
		} catch(ex : Throwable) {
			log.trace(ex, "待機の設定に失敗")
			log.e(ex, "待機の設定に失敗")
		}
		
		cancel(service.getString(R.string.wait_alarm, Utils.formatTimeDuration(remain)))
		
	}
	
	class ErrorAndMessage internal constructor(var bError : Boolean, var message : String)
	
	private fun updateFileLocation(location : Location, item : ScanItem) : ErrorAndMessage {
		var em : ErrorAndMessage? = null
		var bDeleteTempFile = false
		var local_temp : LocalFile? = null
		try {
			val local_file = item.local_file
			val local_parent = local_file.parent
			
			if(local_parent == null) {
				log.e("can't get folder of local file.")
				return ErrorAndMessage(false, "can't get folder of local file.")
			}
			
			// MediaScannerが一時ファイルを勝手にスキャンしてしまう
			// DocumentFile はMIME TYPE に合わせてファイル拡張子を変えてしまう
			// なので、一時ファイルの拡張子とMIME TYPE は無害なものに設定するしかない
			val tmp_name =
				local_file.name + ".tmp-" + Thread.currentThread().id + "-" + android.os.Process.myPid()
			
			local_temp = LocalFile(local_parent, tmp_name)
				.prepareFile(log, true, Utils.MIME_TYPE_APPLICATION_OCTET_STREAM)
			
			if(local_temp == null) {
				log.e("prepareFile() failed.")
				return ErrorAndMessage(false, "prepareFile() failed.")
			}
			
			
			bDeleteTempFile = true
			
			try {
				val os = local_temp.openOutputStream(service)
				try {
					val `is` = item.local_file.openInputStream(service)
					try {
						ExifInterface.modifyExifTag(
							`is`,
							ExifInterface.Options.OPTION_ALL,
							os,
							ExifInterface.ModifyExifTagCallback { ei ->
								val s = ei.latitude
								if(s != null) return@ModifyExifTagCallback false
								
								val latitude = location.latitude
								val longitude = location.longitude
								
								ei.addGpsTags(latitude, longitude)
								
								true
							})
					} finally {
						try {
							`is`?.close()
						} catch(ignored : Throwable) {
						}
						
					}
				} finally {
					try {
						os?.close()
					} catch(ignored : Throwable) {
					}
					
				}
			} catch(ignored : ExifInterface.ModifyExifTagFailedException) {
				// 既にEXIF情報があった
				em = ErrorAndMessage(false, "既に位置情報が含まれています")
			} catch(ex : Throwable) {
				log.trace(ex, "exif mangling failed.")
				log.e(ex, "exif mangling failed.")
				
				// 変更失敗
				em = ErrorAndMessage(true, ex.withCaption("exif mangling failed."))
			}
			
			if(em != null) return em
			
			// 更新後の方がファイルが小さいことがあるのか？
			if(local_temp.length(log) < local_file.length(log)) {
				log.e("EXIF付与したファイルの方が小さい!付与前後のファイルを残しておく")
				// この場合両方のファイルを残しておく
				bDeleteTempFile = false
				return ErrorAndMessage(true, "EXIF付与したファイルの方が小さい")
			}
			
			// DocumentFile にはsetMimeType が存在しないから
			// 一時ファイルをrename してもMIME TYPEを補正できない
			// 仕方ないので元のファイルを上書きする
			// 更新後に再度MediaScannerでスキャンし直すのでなんとかなるだろう。。。
			
			try {
				val `is` = local_temp.openInputStream(service)
				try {
					val os = local_file.openOutputStream(service)
					try {
						IOUtils.copy(`is` !!, os)
					} finally {
						try {
							os !!.close()
						} catch(ignored : Throwable) {
						}
						
					}
				} finally {
					try {
						`is` !!.close()
					} catch(ignored : Throwable) {
					}
					
				}
			} catch(ex : Throwable) {
				log.trace(ex, "file copy failed.")
				log.e(ex, "file copy failed.")
				return ErrorAndMessage(false, "file copy failed.")
			}
			
			log.i("%s に位置情報を付与しました", local_file.name)
			return ErrorAndMessage(false, "embedded")
			
		} catch(ex : Throwable) {
			log.trace(ex, "exif mangling failed.")
			log.e(ex, "exif mangling failed.")
			return ErrorAndMessage(true, ex.withCaption("exif mangling failed."))
		} finally {
			if(local_temp != null && bDeleteTempFile) {
				try {
					
					local_temp.delete()
				} catch(ignored : Throwable) {
				}
				
			}
		}
	}
	
	// ダウンロードをスキップするなら真を返す
	fun checkSkip(local_file : LocalFile, log : LogWriter, size : Long) : Boolean {
		
		// ローカルにあるファイルのサイズが指定以上ならスキップする
		val localLength = local_file.length(log)
		if(localLength >= size) {
			return true
		}
		
		if(skip_already_download) {
			val name = local_file.name
			if(name?.isNotEmpty() == true) {
				service.contentResolver.query(
					DownloadRecord.meta.content_uri, null,
					DownloadRecord.COL_NAME + "=?",
					arrayOf(name), null
				)?.use { cursor ->
					// ダウンロード履歴に同じ名前のファイルがあるのでスキップする
					if(cursor.moveToFirst()) return true
				}
			}
		}
		
		return false
	}
	
	fun record(
		item : ScanItem,
		lap_time : Long,
		state : Int,
		state_message : String
	) {
		if(! RECORD_QUEUED_STATE) {
			if(state == DownloadRecord.STATE_QUEUED) return
		}
		var local_uri = item.local_file.getFileUri(log)
		if(local_uri == null) local_uri = ""
		DownloadRecord.insert(
			service.contentResolver,
			cv,
			item.name,
			item.remote_path,
			local_uri,
			state,
			state_message,
			lap_time,
			item.size
		)
	}
	
	fun afterDownload(time_start : Long, ex : Throwable, item : ScanItem) {
		log.trace(ex, "error.")
		log.e(ex, "error.")
		
		file_error = true
		
		record(
			item,
			SystemClock.elapsedRealtime() - time_start,
			DownloadRecord.STATE_DOWNLOAD_ERROR,
			ex.withCaption()
		)
		
		item.local_file.delete()
	}
	
	fun afterDownload(time_start : Long, data : ByteArray?, item : ScanItem) {
		
		val lap_time = SystemClock.elapsedRealtime() - time_start
		
		when {
			isCancelled -> {
				record(
					item,
					lap_time,
					DownloadRecord.STATE_CANCELLED,
					"download cancelled."
				)
				item.local_file.delete()
			}
			
			data == null -> {
				checkHostError()
				val error = client.last_error ?: "?"
				log.e("FILE %s :HTTP error %s", item.name, error)
				file_error = true
				record(item, lap_time, DownloadRecord.STATE_DOWNLOAD_ERROR, error)
				item.local_file.delete()
			}
			
			else -> {
				log.i("FILE %s :download complete. %dms", item.name, lap_time)
				
				// 位置情報を取得する時にファイルの日時が使えないかと思ったけど
				// タイムゾーンがわからん…
				
				val location = callback.location
				if(location != null && reJPEG.matcher(item.name).find()) {
					
					val em = updateFileLocation(location, item)
					if(item.time > 0L) item.local_file.setFileTime(service, log, item.time)
					
					record(
						item,
						lap_time,
						if(em.bError) DownloadRecord.STATE_EXIF_MANGLING_ERROR else DownloadRecord.STATE_COMPLETED,
						"GeoTagging: " + em.message
					)
					
					service.media_tracker.addFile(
						item.local_file.getFile(service, log),
						"image/jpeg"
					)
					
				} else {
					if(item.time > 0L) item.local_file.setFileTime(service, log, item.time)
					
					record(
						item,
						lap_time,
						DownloadRecord.STATE_COMPLETED,
						"OK"
					)
					
					service.media_tracker.addFile(
						item.local_file.getFile(service, log),
						item.mime_type
					)
				}
			}
		}
	}
	
	fun onFileScanStart() {
		job_queue = ScanItem.Queue()
		file_error = false
		queued_byte_count_max.set(java.lang.Long.MAX_VALUE)
	}
	
	fun onFileScanComplete(file_count : Long) {
		log.i("ファイルスキャン完了")
		
		callback.onAllFileCompleted(file_count)
		
		if(! repeat) {
			callback.onAllFileCompleted(0)
			
			Pref.pref(service).edit()
				.put(Pref.lastMode, Pref.LAST_MODE_STOP)
				.apply()
			cancel(service.getString(R.string.repeat_off))
			complete_and_no_repeat = true
		}
	}
	
	fun checkHostError() {
		if(client.last_error?.contains("UnknownHostException") == true) {
			client.last_error = service.getString(R.string.target_host_error)
			cancel(service.getString(R.string.target_host_error_short))
		} else if(client.last_error?.contains("ENETUNREACH") == true) {
			client.last_error = service.getString(R.string.target_unreachable)
			cancel(service.getString(R.string.target_unreachable))
		}
	}
	
	fun setStatus(bShowQueueCount : Boolean, s : String) {
		if(! bShowQueueCount) {
			queued_file_count.set(0L)
			queued_byte_count.set(0L)
		} else {
			var fc = 0L
			var bc = 0L
			if(job_queue != null) {
				for(item in job_queue !!.queue_file) {
					if(item.is_file) {
						++ fc
						bc += item.size
					}
				}
			}
			queued_file_count.set(fc)
			queued_byte_count.set(bc)
			if(bc > 0 && queued_byte_count_max.get() == java.lang.Long.MAX_VALUE) {
				queued_byte_count_max.set(bc)
			}
		}
		_status.set(s)
	}
	
	override fun run() {
		setStatus(false, service.getString(R.string.thread_start))
		callback.onThreadStart()
		
		// 古いアラームがあれば除去
		Receiver1.cancelAlarm(service)
		
		// 位置情報を得られるまで待機
		run {
			val timeEnd = SystemClock.elapsedRealtime() + 5000L
			while(! isCancelled
				&& service.location_tracker.isUpdateRequired
				&& service.location_tracker.location == null
			) {
				val remain = timeEnd - SystemClock.elapsedRealtime()
				if(remain <= 0L) {
					log.w(R.string.location_wait_timeout)
					break
				}
				sleep(1000L)
			}
		}
		
		
		when(target_type) {
			Pref.TARGET_TYPE_FLASHAIR_AP, Pref.TARGET_TYPE_FLASHAIR_STA -> FlashAir(
				service,
				this
			).run()
			Pref.TARGET_TYPE_PENTAX_KP -> PentaxKP(service, this).run()
			Pref.TARGET_TYPE_PQI_AIR_CARD, Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER -> PqiAirCard(
				service,
				this
			).run()
			else -> log.e("unsupported target type %s", target_type)
		}
		
		// 未取得状態のファイルを履歴から消す
		if(RECORD_QUEUED_STATE) {
			service.contentResolver.delete(
				DownloadRecord.meta.content_uri,
				DownloadRecord.COL_STATE_CODE + "=?",
				arrayOf(DownloadRecord.STATE_QUEUED.toString())
			)
		}
		setStatus(false, service.getString(R.string.thread_end))
		callback.releaseWakeLock()
		callback.onThreadEnd(this@DownloadWorker, complete_and_no_repeat)
	}
	
}
