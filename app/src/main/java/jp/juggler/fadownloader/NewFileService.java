package jp.juggler.fadownloader;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

public class NewFileService extends IntentService{

	static final int NOTIFICATION_ID_DOWNLOAD_COMPLETE = 2;

	static final String ACTION_TAP = "tap";
	static final String ACTION_DELETE = "delete";

	public static boolean hasHiddenDownloadCount( Context context ){
		long previous_count = Pref.pref( context ).getLong( Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, 0L );
		return previous_count > 0L;
	}

	public static void addHiddenDownloadCount( Context context, long delta ){
		SharedPreferences pref = Pref.pref( context );
		long hidden_count = pref.getLong( Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, 0L );
		if( hidden_count < 0L ) hidden_count = 0L;

		if( delta > 0L ){
			hidden_count += delta;
			pref.edit().putLong( Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, hidden_count ).apply();
			return;
		}else if( hidden_count > 0 ){
			long count = pref.getLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0L );
			if( count < 0L ) count = 0L;
			count += hidden_count;
			hidden_count = 0L;
			pref.edit()
				.putLong( Pref.DOWNLOAD_COMPLETE_COUNT_HIDDEN, hidden_count )
				.putLong( Pref.DOWNLOAD_COMPLETE_COUNT, count )
				.apply();

			showCount( context,count );
		}
	}

	private static void showCount(Context context,long count){
		if( count <=0L ) return;

		NotificationCompat.Builder builder = new NotificationCompat.Builder( context );
		builder.setSmallIcon( R.drawable.ic_service );
		builder.setContentTitle( context.getString( R.string.app_name ) );
		builder.setContentText( context.getString( R.string.download_complete_notification, count ) );
		builder.setTicker( context.getString( R.string.download_complete_notification, count ) );
		builder.setWhen( System.currentTimeMillis() );
		builder.setDefaults( NotificationCompat.DEFAULT_ALL );
		builder.setAutoCancel( true );

		{
			Intent intent = new Intent( context, NewFileService.class );
			intent.setAction( ACTION_TAP );
			PendingIntent pi = PendingIntent.getService( context, 567, intent, PendingIntent.FLAG_UPDATE_CURRENT );
			builder.setContentIntent( pi );
		}
		{
			Intent intent = new Intent( context, NewFileService.class );
			intent.setAction( ACTION_DELETE );
			PendingIntent pi = PendingIntent.getService( context, 568, intent, PendingIntent.FLAG_UPDATE_CURRENT );
			builder.setDeleteIntent( pi );
		}

		NotificationManagerCompat.from( context ).notify( NOTIFICATION_ID_DOWNLOAD_COMPLETE, builder.build() );
		NewFileWidget.update( context );
	}

	public NewFileService(){
		super( "DownloadCountService" );
	}

	@Override protected void onHandleIntent( @Nullable Intent intent ){
		if( intent != null ){
			if( ACTION_TAP.equals( intent.getAction() ) ){
				intent = new Intent( this, ActMain.class );
				intent.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY );
				intent.putExtra( ActMain.EXTRA_TAB, ActMain.TAB_RECORD );
				startActivity( intent );
			}
			NotificationManagerCompat.from( this ).cancel( NOTIFICATION_ID_DOWNLOAD_COMPLETE );
			Pref.pref( this ).edit().putLong( Pref.DOWNLOAD_COMPLETE_COUNT, 0 ).apply();
			NewFileWidget.update( this );
		}
	}

}
