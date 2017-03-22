package jp.juggler.fadownloader;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class NewFileWidget extends AppWidgetProvider{

	@Override public void onUpdate( Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds ){

		if( appWidgetIds.length == 0 ) return;
		ComponentName c_name = new ComponentName( context, NewFileWidget.class );

		long count = Pref.pref( context ).getLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0L );

		appWidgetManager.updateAppWidget( c_name, createRemoteViews( context, count ) );
	}

	public static void update( Context context ){
		ComponentName c_name = new ComponentName( context, NewFileWidget.class );
		AppWidgetManager appWidgetManager = AppWidgetManager.getInstance( context );
		int[] appWidgetIds = appWidgetManager.getAppWidgetIds( c_name );
		if( appWidgetIds == null || appWidgetIds.length == 0 ) return;

		long count = Pref.pref( context ).getLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0L );

		appWidgetManager.updateAppWidget( c_name, createRemoteViews( context, count ) );
	}

	private static RemoteViews createRemoteViews( Context context, long count ){
		// NewFileService を ACTION_TAP つきで呼び出すPendingIntent
		Intent intent = new Intent( context, NewFileService.class );
		intent.setAction( NewFileService.ACTION_TAP );
		PendingIntent pendingIntent = PendingIntent.getService( context, 569, intent, 0 );

		// RemoteViewsを調整
		RemoteViews views = new RemoteViews( context.getPackageName(), R.layout.new_file_widget );
		views.setTextViewText( R.id.tvCount, Long.toString( count ) );
		views.setOnClickPendingIntent( R.id.llWidget, pendingIntent );

		return views;
	}
}
