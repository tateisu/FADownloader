package jp.juggler.fadownloader

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat

class NewFileService : IntentService("DownloadCountService") {
	
	companion object {
		
		internal const val NOTIFICATION_ID_DOWNLOAD_COMPLETE = 2
		
		internal const val ACTION_TAP = "tap"
		private const val ACTION_DELETE = "delete"
		
		fun hasHiddenDownloadCount(context : Context) : Boolean {
			val previous_count = Pref.pref(context).getLong(Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, 0L)
			return previous_count > 0L
		}
		
		fun addHiddenDownloadCount(context : Context, delta : Long) {
			val pref = Pref.pref(context)
			var hidden_count = pref.getLong(Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, 0L)
			if(hidden_count < 0L) hidden_count = 0L
			
			if(delta > 0L) {
				hidden_count += delta
				pref.edit().putLong(Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, hidden_count).apply()
				return
			} else if(hidden_count > 0) {
				var count = pref.getLong(Pref.DOWNLOAD_COMPLETE_COUNT, 0L)
				if(count < 0L) count = 0L
				count += hidden_count
				hidden_count = 0L
				pref.edit()
					.putLong(Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, hidden_count)
					.putLong(Pref.DOWNLOAD_COMPLETE_COUNT, count)
					.apply()
				
				showCount(context, count)
			}
		}
		
		private fun showCount(context : Context, count : Long) {
			if(count <= 0L) return
			
			val builder = NotificationCompat.Builder(context)
			builder.setSmallIcon(R.drawable.ic_service)
			builder.setContentTitle(context.getString(R.string.app_name))
			builder.setContentText(
				context.getString(
					R.string.download_complete_notification,
					count
				)
			)
			builder.setTicker(context.getString(R.string.download_complete_notification, count))
			builder.setWhen(System.currentTimeMillis())
			builder.setDefaults(NotificationCompat.DEFAULT_ALL)
			builder.setAutoCancel(true)
			
			run {
				val intent = Intent(context, NewFileService::class.java)
				intent.action = ACTION_TAP
				val pi = PendingIntent.getService(
					context,
					567,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT
				)
				builder.setContentIntent(pi)
			}
			run {
				val intent = Intent(context, NewFileService::class.java)
				intent.action = ACTION_DELETE
				val pi = PendingIntent.getService(
					context,
					568,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT
				)
				builder.setDeleteIntent(pi)
			}
			
			NotificationManagerCompat.from(context)
				.notify(NOTIFICATION_ID_DOWNLOAD_COMPLETE, builder.build())
			NewFileWidget.update(context)
		}
	}
	
	override fun onHandleIntent(intentArg : Intent?) {
		if(ACTION_TAP == intentArg?.action) {
			val intent = Intent(this, ActMain::class.java)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
			intent.putExtra(ActMain.EXTRA_TAB, ActMain.TAB_RECORD)
			startActivity(intent)
		}
		NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_DOWNLOAD_COMPLETE)
		Pref.pref(this).edit().putLong(Pref.DOWNLOAD_COMPLETE_COUNT, 0).apply()
		NewFileWidget.update(this)
	}
	
	
}
