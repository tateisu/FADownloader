package jp.juggler.fadownloader

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.widget.RemoteViews
import jp.juggler.fadownloader.util.NotificationHelper

class NewFileWidget : AppWidgetProvider() {
	
	
	companion object {
		private const val NOTIFICATION_ID_DOWNLOAD_COMPLETE = 2
		
		@Synchronized
		fun hasHiddenDownloadCount(context : Context) : Boolean {
			val previous_count = Pref.downloadCompleteCountHidden(Pref.pref(context))
			return previous_count > 0L
		}
		
		@Synchronized
		fun addHiddenDownloadCount(context : Context, delta : Long) {
			val pref = Pref.pref(context)
			var hidden_count = Pref.downloadCompleteCountHidden(pref)
			if(hidden_count < 0L) hidden_count = 0L
			
			when {
				// ダウンロードしたファイルが増えた際に呼ばれる
				delta > 0L -> {
					hidden_count += delta
					pref.edit().put(Pref.downloadCompleteCountHidden, hidden_count).apply()
					return
				}
				// スキャン完了時にdelta==0で呼ばれる
				// 通知を更新する
				hidden_count > 0 -> {
					// 表示中のカウント値
					var count = Pref.downloadCompleteCount(pref)
					if(count < 0L) count = 0L
					// 増えた分を追加する
					count += hidden_count
					hidden_count = 0L
					// 値を保存する
					pref.edit()
						.put(Pref.downloadCompleteCountHidden, hidden_count)
						.put(Pref.downloadCompleteCount, count)
						.apply()
					
					// 通知を表示する
					showNotification(context, count)
					
					// ウィジェットを更新する
					NewFileWidget.showWidget(context, count)
				}
			}
		}
		
		private fun showNotification(context : Context, count : Long) {
			if(count <= 0L) return
			
			val builder = if(Build.VERSION.SDK_INT >= 26) {
				// Android 8 から、通知のスタイルはユーザが管理することになった
				// NotificationChannel を端末に登録しておけば、チャネルごとに管理画面が作られる
				val channel = NotificationHelper.createNotificationChannel(
					context,
					"NewFileDownloaded",
					"New file downloaded",
					"this notification is shown when new file was downloaded.",
					NotificationManager.IMPORTANCE_DEFAULT
				)
				NotificationCompat.Builder(context, channel.id)
			} else {
				NotificationCompat.Builder(context, "not_used")
			}
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
			
			builder.setContentIntent( Receiver1.piNewFileTap(context))
			
			
			builder.setDeleteIntent( Receiver1.piNewFileNotificationDelete(context))
			
			
			
			NotificationManagerCompat
				.from(context)
				.notify(NOTIFICATION_ID_DOWNLOAD_COMPLETE, builder.build())
			
		}
		
		private fun showWidget(context : Context, count : Long) {
			val c_name = ComponentName(context, NewFileWidget::class.java)
			val appWidgetManager = AppWidgetManager.getInstance(context)
			val appWidgetIds = appWidgetManager.getAppWidgetIds(c_name)
			if(appWidgetIds == null || appWidgetIds.isEmpty()) return
			
			appWidgetManager.updateAppWidget(c_name, createRemoteViews(context, count))
		}
		
		private fun createRemoteViews(context : Context, count : Long) : RemoteViews {
			// RemoteViewsを調整
			val views = RemoteViews(context.packageName, R.layout.new_file_widget)
			views.setTextViewText(R.id.tvCount, java.lang.Long.toString(count))
			views.setOnClickPendingIntent(R.id.llWidget, Receiver1.piNewFileTap(context))
			
			return views
		}
		
		// レシーバーから呼ばれる
		fun clearDownloadCount(context : Context) {
			
			// カウント表示した分をクリアする
			Pref.pref(context).edit().put(Pref.downloadCompleteCount, 0).apply()
			
			// 通知を消去する
			NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_DOWNLOAD_COMPLETE)
			
			// ウィジェットがあれば表示を更新する
			NewFileWidget.showWidget(context, 0)
		}
	}
	
	override fun onUpdate(
		context : Context,
		appWidgetManager : AppWidgetManager,
		appWidgetIds : IntArray
	) {
		
		if(appWidgetIds.isEmpty()) return
		val c_name = ComponentName(context, NewFileWidget::class.java)
		
		val count = Pref.downloadCompleteCount(Pref.pref(context))
		
		appWidgetManager.updateAppWidget(c_name, createRemoteViews(context, count))
	}
	
}
