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

		long previous_count = Pref.pref( context ).getLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0L );

		appWidgetManager
			.updateAppWidget(
				new ComponentName( context,NewFileWidget.class )
				, createRemoteViews(context,previous_count)
			);

	}

	public static void update( Context context ){

		long previous_count = Pref.pref( context ).getLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0L );

		AppWidgetManager
			.getInstance( context )
			.updateAppWidget(
				new ComponentName( context,NewFileWidget.class )
				, createRemoteViews(context,previous_count)
			);
	}

	private static RemoteViews createRemoteViews(Context context,long count){
		// Create an Intent to launch ExampleActivity
		Intent intent = new Intent(context, NewFileService.class);
		intent.setAction( NewFileService.ACTION_TAP );
		PendingIntent pendingIntent = PendingIntent.getService( context, 569, intent, 0);

		// Get the layout for the App Widget and attach an on-click listener
		// to the button
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.new_file_widget );
		views.setTextViewText( R.id.tvCount, Long.toString(count));
		views.setOnClickPendingIntent(R.id.llWidget, pendingIntent);

		return views;
	}
}
