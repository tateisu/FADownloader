package jp.juggler.fadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public class Pref{


	public static SharedPreferences pref( Context context ){
		return context.getSharedPreferences( "app_pref", Context.MODE_PRIVATE );
	}

	// UI画面に表示されている情報の永続化
	public static final String UI_REPEAT = "ui_repeat";
	public static final String UI_LAST_PAGE = "ui_last_page";
	public static final String UI_FLASHAIR_URL = "ui_flashair_url";
	public static final String UI_FOLDER_URI = "ui_folder_uri";
	public static final String UI_INTERVAL = "ui_interval";
	public static final String UI_FILE_TYPE = "ui_file_type";
	public static final String UI_LOCATION_MODE = "ui_location_mode";
	public static final String UI_LOCATION_INTERVAL_DESIRED = "ui_location_interval_desired";
	public static final String UI_LOCATION_INTERVAL_MIN = "ui_location_interval_min";
	public static final String UI_FORCE_WIFI = "ui_force_wifi";
	public static final String UI_SSID = "ui_ssid";
	public static final String UI_THUMBNAIL_AUTO_ROTATE = "ui_thumbnail_auto_rotate";
	public static final String UI_COPY_BEFORE_VIEW_SEND = "ui_copy_before_view_send";

	public static final boolean DEFAULT_THUMBNAIL_AUTO_ROTATE = true;

	public static void initialize( Context context ){
		SharedPreferences pref = pref( context );

		SharedPreferences.Editor e = pref.edit();
		boolean bChanged = false;
		String sv;
		int iv;

		//
		sv = pref.getString( Pref.UI_FLASHAIR_URL, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_FLASHAIR_URL, "http://192.168.0.1/" );
		}
		//
		sv = pref.getString( Pref.UI_INTERVAL, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_INTERVAL, "30" );
		}
		//
		sv = pref.getString( Pref.UI_FILE_TYPE, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_FILE_TYPE, ".jp*" );
		}
		//
		iv = pref.getInt( Pref.UI_LOCATION_MODE, - 1 );
		if( iv < 0 || iv > LocationTracker.LOCATION_HIGH_ACCURACY ){
			bChanged = true;
			e.putInt( Pref.UI_LOCATION_MODE, LocationTracker.DEFAULT_MODE );
		}
		//
		sv = pref.getString( Pref.UI_LOCATION_INTERVAL_DESIRED, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_LOCATION_INTERVAL_DESIRED
				, Long.toString( LocationTracker.DEFAULT_INTERVAL_DESIRED / 1000L ) );
		}
		//
		sv = pref.getString( Pref.UI_LOCATION_INTERVAL_MIN, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_LOCATION_INTERVAL_MIN
				, Long.toString( LocationTracker.DEFAULT_INTERVAL_MIN / 1000L ) );
		}
		//
		if( bChanged ) e.apply();
	}

	// 最後に押したボタン
	public static final String LAST_MODE_UPDATE = "last_mode_update";
	public static final String LAST_MODE = "last_mode";
	public static final int LAST_MODE_STOP = 0;
	public static final int LAST_MODE_ONCE = 1;
	public static final int LAST_MODE_REPEAT = 2;

	// 最後にWorkerを手動開始した時の設定
	public static final String WORKER_REPEAT = "worker_repeat";
	public static final String WORKER_FLASHAIR_URL = "worker_flashair_url";
	public static final String WORKER_FOLDER_URI = "worker_folder_uri";
	public static final String WORKER_INTERVAL = "worker_interval";
	public static final String WORKER_FILE_TYPE = "worker_file_type";
	public static final String WORKER_LOCATION_INTERVAL_DESIRED = "worker_location_interval_desired";
	public static final String WORKER_LOCATION_INTERVAL_MIN = "worker_location_interval_min";
	public static final String WORKER_LOCATION_MODE = "worker_location_mode";
	public static final String WORKER_FORCE_WIFI = "worker_force_wifi";
	public static final String WORKER_SSID = "worker_ssid";

	// ファイルスキャンが完了した時刻
	public static final String LAST_IDLE_START = "last_idle_start";
	public static final String FLASHAIR_UPDATE_STATUS_OLD = "flashair_update_status_old";

	public static final String REMOVE_AD_PURCHASED = "remove_ad_purchased";

}
