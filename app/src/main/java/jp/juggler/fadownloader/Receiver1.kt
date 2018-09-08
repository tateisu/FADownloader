package jp.juggler.fadownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.content.ContextCompat

class Receiver1 : BroadcastReceiver() {
	
	override fun onReceive(context : Context, broadcast_intent : Intent) {
		try {
			
			val action = broadcast_intent.action
			
			if(Intent.ACTION_BOOT_COMPLETED == action) {
				val last_mode = Pref.pref(context).getInt(Pref.LAST_MODE, Pref.LAST_MODE_STOP)
				if(last_mode != Pref.LAST_MODE_REPEAT) {
					// 繰り返しモード以外では起動時の常駐は行わない
					return
				}
			} else if(ACTION_ALARM == action) {
				val last_mode = Pref.pref(context).getInt(Pref.LAST_MODE, Pref.LAST_MODE_STOP)
				if(last_mode == Pref.LAST_MODE_STOP) {
					// 停止ボタンを押した後はアラームによる起動は行わない
					return
				}
			}
			
			val service_intent = Intent(context, DownloadService::class.java)
			service_intent.action = DownloadService.ACTION_BROADCAST_RECEIVED
			service_intent.putExtra(DownloadService.EXTRA_BROADCAST_INTENT, broadcast_intent)
			ContextCompat.startForegroundService(context,service_intent)
		} catch(ex : Throwable) {
			ex.printStackTrace()
		}
		
	}
	
	companion object {
		
		val ACTION_ALARM = "alarm"
	}
	
}
