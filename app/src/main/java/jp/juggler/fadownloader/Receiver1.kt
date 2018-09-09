package jp.juggler.fadownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.content.ContextCompat
import jp.juggler.fadownloader.util.LogTag

class Receiver1 : BroadcastReceiver() {

	companion object {
		private val log = LogTag("Receiver1")
		const val ACTION_ALARM = "alarm"
	}
	
	override fun onReceive(context : Context, broadcast_intent : Intent) {
		try {
			val last_mode = Pref.lastMode(Pref.pref(context))

			when(broadcast_intent.action) {
				Intent.ACTION_BOOT_COMPLETED -> {
					// 繰り返しモード以外では起動時の常駐は行わない
					if(last_mode != Pref.LAST_MODE_REPEAT) return
				}
				
				ACTION_ALARM -> {
					// 停止ボタンを押した後はアラームによる起動は行わない
					if(last_mode == Pref.LAST_MODE_STOP) return
				}
			}
			
			val service_intent = Intent(context, DownloadService::class.java)
			service_intent.action = DownloadService.ACTION_BROADCAST_RECEIVED
			service_intent.putExtra(DownloadService.EXTRA_BROADCAST_INTENT, broadcast_intent)
			ContextCompat.startForegroundService(context, service_intent)
		} catch(ex : Throwable) {
			log.trace(ex,"onReceive failed.")
		}
	}
}
