package jp.juggler.fadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public class Pref{


	public static SharedPreferences pref( Context context ){
		return context.getSharedPreferences( "app_pref", Context.MODE_PRIVATE );
	}

	// UI画面に表示されている情報の永続化
	static final String UI_FLASHAIR_URL = "ui_flashair_url";
	static final String UI_FOLDER_URI = "ui_folder_uri";
	static final String UI_INTERVAL = "ui_interval";
	static final String UI_FILE_TYPE = "ui_file_type";

	public static void initialize( Context context ){
		SharedPreferences pref = pref( context );

		SharedPreferences.Editor e = pref.edit();
		boolean bChanged = false;
		String sv;

		//
		sv = pref.getString( Pref.UI_FLASHAIR_URL, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_FLASHAIR_URL, "http://flashair/" );
		}
		//
		sv = pref.getString( Pref.UI_INTERVAL, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_INTERVAL, "10" );
		}
		//
		sv = pref.getString( Pref.UI_FILE_TYPE, null );
		if( TextUtils.isEmpty( sv ) ){
			bChanged = true;
			e.putString( Pref.UI_FILE_TYPE, ".jp*" );
		}

		if( bChanged ) e.apply();
	}

	// 最後に押したボタン
	static final String LAST_MODE_UPDATE = "last_mode_update";
	static final String LAST_MODE = "last_mode";
	static final int LAST_MODE_STOP = 0;
	static final int LAST_MODE_ONCE = 1;
	static final int LAST_MODE_REPEAT = 2;

	// 処理を前回開始した時刻
	static final String LAST_START = "last_start";

	// 最後にWorkerを手動開始した時の設定
	static final String WORKER_REPEAT = "worker_repeat";
	static final String WORKER_FLASHAIR_URL = "worker_flashair_url";
	static final String WORKER_FOLDER_URI = "worker_folder_uri";
	static final String WORKER_INTERVAL = "worker_interval";
	static final String WORKER_FILE_TYPE = "worker_file_type";

	// ファイルスキャンが完了した時刻
	public static final String LAST_SCAN_COMPLETE = "last_scan_complete";

}
