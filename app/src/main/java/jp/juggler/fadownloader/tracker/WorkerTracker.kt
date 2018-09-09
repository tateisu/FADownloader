package jp.juggler.fadownloader.tracker

import android.content.Intent
import android.location.Location
import android.os.Handler
import jp.juggler.fadownloader.*
import jp.juggler.fadownloader.util.LogWriter
import jp.juggler.fadownloader.util.Utils

/*
	DownloadWorkerのスレッド終了を待ってから次のスレッドを作る
	作成要求が来てから作成するまではメインスレッド上で定期的に状態確認を行う
 */
// サービス開始時に作られる
class WorkerTracker(
	internal val service : DownloadService,
	internal val log :
	LogWriter
) {
	
	internal val handler : Handler = Handler()
	internal var tracker_disposed : Boolean = false
	internal var tracker_dispose_complete : Boolean = false
	
	internal var worker : DownloadWorker? = null
	internal var worker_disposed : Boolean = false
	
	// パラメータ指定付きでのスレッド作成フラグ
	internal var will_restart : Boolean = false
	internal var start_param : Intent? = null
	
	// 何かのイベントでのスレッド再生成フラグ
	internal var will_wakeup : Boolean = false
	internal var wakeup_cause : String? = null
	
	internal val proc_check : Runnable = object : Runnable {
		override fun run() {
			handler.removeCallbacks(this)
			
			if(tracker_disposed) {
				if(! worker_disposed) {
					worker?.cancel(service.getString(R.string.service_end))
					handler.postDelayed(this, 3000L)
				} else {
					tracker_dispose_complete = true
				}
				return
			}
			
			val start_param = this@WorkerTracker.start_param
			if(will_restart && start_param != null) {
				if(! worker_disposed) {
					worker?.cancel(service.getString(R.string.manual_restart))
					handler.postDelayed(this, 3000L)
					return
				}
				
				Pref.pref(service).edit()
					.remove(Pref.lastIdleStart)
					.remove(Pref.flashAirUpdateStatusOld)
					.apply()
				
				try {
					will_restart = false
					worker_disposed = false
					worker = DownloadWorker(
						service,
						start_param,
						worker_callback
					)
					worker !!.start()
				} catch(ex : Throwable) {
					log.trace(ex,"thread start failed.")
					log.e(ex, "thread start failed.")
				}
				
			}
			
			if(will_wakeup) {
				var worker = this@WorkerTracker.worker
				when {
					worker == null -> {
					}
					
					! worker.isCancelled -> {
						// キャンセルされていないなら通知して終わり
						will_wakeup = false
						worker.notifyEx()
						return
					}
					
					! worker_disposed -> {
						// dispose 完了を待つ
						log.d("waiting dispose previous thread..")
						handler.postDelayed(this, 3000L)
						return
					}
				}
				// worker is null or disposed
				
				try {
					will_wakeup = false
					worker_disposed = false
					worker =
						DownloadWorker(
							service,
							wakeup_cause ?: "will_wakeup",
							worker_callback
						)
					this@WorkerTracker.worker = worker
					worker.start()
				} catch(ex : Throwable) {
					log.trace(ex,"thread start failed.")
					log.e(ex, "thread start failed.")
				}
				
			}
		}
	}
	
	internal val worker_callback : DownloadWorker.Callback = object :
		DownloadWorker.Callback {
		
		override val location : Location?
			get() = if(tracker_disposed) null else service.location_tracker.location
		
		override fun onThreadEnd(complete_and_no_repeat : Boolean) {
			Utils.runOnMainThread {
				worker_disposed = true
				service.onThreadEnd(complete_and_no_repeat)
				proc_check.run()
			}
		}
		
		override fun onThreadStart() {
			service.onThreadStart()
		}
		
		override fun releaseWakeLock() {
			if(will_restart || will_wakeup) return
			service.releaseWakeLock()
		}
		
		override fun acquireWakeLock() {
			service.acquireWakeLock()
		}
		
		override fun onAllFileCompleted(count : Long) {
			service.addHiddenDownloadCount(count,log)
		}
		
		override fun hasHiddenDownloadCount() : Boolean {
			return service.hasHiddenDownloadCount()
		}
	}
	
	// サービス終了時に破棄される
	fun dispose() {
		tracker_disposed = true
		proc_check.run()
	}
	
	internal fun start(intent : Intent) {
		if(tracker_disposed) return
		this.will_restart = true
		this.start_param = intent
		proc_check.run()
	}
	
	internal fun wakeup(cause : String) {
		if(tracker_disposed) return
		this.will_wakeup = true
		this.wakeup_cause = cause
		proc_check.run()
	}
}
