package jp.juggler.fadownloader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class NewFileWidget : AppWidgetProvider() {
	
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
	
	companion object {
		
		fun update(context : Context) {
			val c_name = ComponentName(context, NewFileWidget::class.java)
			val appWidgetManager = AppWidgetManager.getInstance(context)
			val appWidgetIds = appWidgetManager.getAppWidgetIds(c_name)
			if(appWidgetIds == null || appWidgetIds.isEmpty()) return
			
			val count = Pref.downloadCompleteCount( Pref.pref(context) )
			
			appWidgetManager.updateAppWidget(c_name, createRemoteViews(context, count))
		}
		
		private fun createRemoteViews(context : Context, count : Long) : RemoteViews {
			// NewFileService を ACTION_TAP つきで呼び出すPendingIntent
			val intent = Intent(context, NewFileService::class.java)
			intent.action = NewFileService.ACTION_TAP
			val pendingIntent = PendingIntent.getService(context, 569, intent, 0)
			
			// RemoteViewsを調整
			val views = RemoteViews(context.packageName, R.layout.new_file_widget)
			views.setTextViewText(R.id.tvCount, java.lang.Long.toString(count))
			views.setOnClickPendingIntent(R.id.llWidget, pendingIntent)
			
			return views
		}
	}
}
