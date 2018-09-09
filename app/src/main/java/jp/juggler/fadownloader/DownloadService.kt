package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.app.NotificationCompat
import jp.juggler.fadownloader.tracker.LocationTracker
import jp.juggler.fadownloader.tracker.MediaScannerTracker
import jp.juggler.fadownloader.tracker.NetworkTracker
import jp.juggler.fadownloader.tracker.WorkerTracker
import jp.juggler.fadownloader.util.LogWriter
import jp.juggler.fadownloader.util.NotificationHelper
import jp.juggler.fadownloader.util.Utils

class DownloadService : Service() {

	companion object {
		
		internal const val ACTION_BROADCAST_RECEIVED = "broadcast_received"
		internal const val EXTRA_BROADCAST_INTENT = "broadcast_intent"
		
		internal const val ACTION_START = "start"
		
		internal const val EXTRA_REPEAT = "repeat"
		internal const val EXTRA_TARGET_URL = "uri"
		internal const val EXTRA_LOCAL_FOLDER = "folder_uri"
		internal const val EXTRA_INTERVAL = "intervalSeconds"
		internal const val EXTRA_FILE_TYPE = "file_type"
		internal const val EXTRA_LOCATION_INTERVAL_DESIRED = "location_interval_desired"
		internal const val EXTRA_LOCATION_INTERVAL_MIN = "location_interval_min"
		internal const val EXTRA_LOCATION_MODE = "location_mode"
		internal const val EXTRA_FORCE_WIFI = "force_wifi"
		internal const val EXTRA_SSID = "ssid"
		internal const val EXTRA_TARGET_TYPE = "target_type"
		
		internal const val NOTIFICATION_ID_SERVICE = 1
		const val EXTRA_PROTECTED_ONLY = "protected_only"
		const val EXTRA_SKIP_ALREADY_DOWNLOAD = "skip_already_download"
		
		internal var service_instance : DownloadService? = null
		
		fun getStatusForActivity(context : Context) : String {
			val service = service_instance
				?: return context.getString(R.string.service_not_running)
			
			val sb = StringBuilder()
			sb.append(context.getString(R.string.service_running))
			sb.append("WakeLock=")
				.append(if(service.wake_lock !!.isHeld) "ON" else "OFF")
				.append(", ")
				.append("WiFiLock=")
				.append(if(service.wifi_lock !!.isHeld) "ON" else "OFF")
				.append(", ")
				.append("Location=")
				.append(service.location_tracker.status)
				.append(", ")
				.append("Network=")
			service.wifi_tracker.getStatus(sb)
			sb.append('\n')
			
			val worker = service.worker_tracker.worker
			if(worker == null || ! worker.isAlive) {
				sb.append(context.getString(R.string.thread_not_running_status))
			} else {
				sb.append(worker.status)
			}
			
			return sb.toString()
		}
		
		var location : Location? = null
			internal set
	}
	
	lateinit var log : LogWriter
	
	private var is_alive : Boolean = false
	private var cancel_alarm_on_destroy : Boolean = false
	internal var wake_lock : PowerManager.WakeLock? = null
	
	internal var wifi_lock : WifiManager.WifiLock? = null
	private lateinit var handler : Handler
	
	internal lateinit var location_tracker : LocationTracker
	lateinit var wifi_tracker : NetworkTracker
	
	private lateinit var mNotificationManager : NotificationManager
	internal lateinit var worker_tracker : WorkerTracker
	internal lateinit var media_tracker : MediaScannerTracker
	
	override fun onCreate() {
		super.onCreate()
		
		service_instance = this
		handler = Handler()
		
		is_alive = true
		cancel_alarm_on_destroy = false
		
		val log = LogWriter(this)
		this.log = log
		log.d(getString(R.string.service_start))
		
		val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
		wake_lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName)
		wake_lock !!.setReferenceCounted(false)
		
		val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
		wifi_lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, packageName)
		wifi_lock !!.setReferenceCounted(false)
		
		mNotificationManager =
			applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		
		setServiceNotification(getString(R.string.service_idle))
		
		location_tracker = LocationTracker(this, log) { location ->
			DownloadService.location = location
		}
		
		media_tracker = MediaScannerTracker(this, log)
		
		wifi_tracker =
			NetworkTracker(this, log) { is_connected, cause ->
				if(is_connected) {
					val last_mode = Pref.lastMode(Pref.pref(this@DownloadService))
					if(last_mode != Pref.LAST_MODE_STOP) {
						worker_tracker.wakeup(cause)
					}
				}
			}
		
		worker_tracker = WorkerTracker(this, log)
	}
	
	override fun onDestroy() {
		
		is_alive = false
		
		worker_tracker.dispose()
		
		
		media_tracker.dispose()
		
		location_tracker.dispose()
		
		wifi_tracker.dispose()
		
		if(cancel_alarm_on_destroy) {
			try {
				val pi = Utils.createAlarmPendingIntent(this)
				val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
				am?.cancel(pi)
			} catch(ex : Throwable) {
				log.trace(ex,"cancel_alarm_on_destroy failed.")
			}
			
		}
		
		wake_lock?.release()
		wake_lock = null
		
		wifi_lock?.release()
		wifi_lock = null
		
		stopForeground(true)
		
		log.d(getString(R.string.service_end))
		log.dispose()
		
		service_instance = null
		
		super.onDestroy()
	}
	
	override fun onStartCommand(intent : Intent?, flags : Int, startId : Int) : Int {
		if(intent != null) {
			var action = intent.action
			when(action) {
				ACTION_BROADCAST_RECEIVED -> {
					val broadcast_intent = intent.getParcelableExtra<Intent>(EXTRA_BROADCAST_INTENT)
					if(broadcast_intent != null) {
						action = broadcast_intent.action
						
						when(action) {
							Receiver1.ACTION_ALARM -> worker_tracker.wakeup("Alarm")
							Intent.ACTION_BOOT_COMPLETED -> worker_tracker.wakeup("Boot completed")
							else -> log.d(getString(R.string.broadcast_received, action))
						}
					}
				}
				
				ACTION_START -> {
					worker_tracker.start(intent)
				}
				
				else -> log.d(getString(R.string.unsupported_intent_received, action))
			}
		}
		
		return super.onStartCommand(intent, flags, startId)
	}
	
	override fun onBind(intent : Intent) : IBinder? {
		return null
	}
	
	//////////////////////////////////////////////////////////////////////////////////
	
	internal fun releaseWakeLock() {
		if(! is_alive) return
		try {
			wake_lock !!.release()
		} catch(ex : Throwable) {
			log.trace(ex, "WakeLock release failed.")
			log.e(ex, "WakeLock release failed.")
		}
		
		try {
			wifi_lock !!.release()
		} catch(ex : Throwable) {
			log.trace(ex, "WifiLock release failed.")
			log.e(ex, "WifiLock release failed.")
		}
		
	}
	
	@SuppressLint("WakelockTimeout")
	internal fun acquireWakeLock() {
		if(! is_alive) return
		try {
			wake_lock?.acquire()
		} catch(ex : Throwable) {
			log.trace(ex, "WakeLock acquire failed.")
			log.e(ex, "WakeLock acquire failed.")
		}
		
		try {
			wifi_lock !!.acquire()
		} catch(ex : Throwable) {
			log.trace(ex, "WifiLock acquire failed.")
			log.e(ex, "WifiLock acquire failed.")
		}
		
	}
	
	fun onThreadStart() {
		if(! is_alive) return
		setServiceNotification(getString(R.string.thread_running))
	}
	
	internal fun onThreadEnd(complete_and_no_repeat : Boolean) {
		if(! is_alive) return
		
		if(complete_and_no_repeat) {
			this@DownloadService.cancel_alarm_on_destroy = true
			stopSelf()
		} else {
			setServiceNotification(getString(R.string.service_idle))
		}
	}
	
	internal fun addHiddenDownloadCount(count : Long, log : LogWriter) {
		NewFileService.addHiddenDownloadCount(this, count, log)
	}
	
	fun hasHiddenDownloadCount() : Boolean {
		return NewFileService.hasHiddenDownloadCount(this)
	}
	
	private fun setServiceNotification(status : String) {
		if(! is_alive) return
		
		val builder = if(Build.VERSION.SDK_INT >= 26) {
			// Android 8 から、通知のスタイルはユーザが管理することになった
			// NotificationChannel を端末に登録しておけば、チャネルごとに管理画面が作られる
			val channel = NotificationHelper.createNotificationChannel(
				this,
				"ServiceRunning",
				"FA Downloader service",
				"this notification is shown while FA Downloader service is active.",
				NotificationManager.IMPORTANCE_DEFAULT,
				log
			)
			NotificationCompat.Builder(this, channel.id)
		} else {
			NotificationCompat.Builder(this, "not_used")
		}
		
		builder.setSmallIcon(R.drawable.ic_service)
		builder.setContentTitle(getString(R.string.app_name))
		builder.setContentText(status)
		builder.setOngoing(true)
		
		val intent = Intent(this, ActMain::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
		val pi = PendingIntent.getActivity(applicationContext, 567, intent, 0)
		builder.setContentIntent(pi)
		
		startForeground(NOTIFICATION_ID_SERVICE, builder.build())
	}
	
}
