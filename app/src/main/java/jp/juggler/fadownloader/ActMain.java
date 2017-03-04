package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class ActMain
	extends AppCompatActivity
	implements View.OnClickListener
{

	static final int REQUEST_CODE_PERMISSION = 1;
	static final int REQUEST_CODE_DOCUMENT = 2;

	TextView tvStatus;

	ViewPager pager;
	PagerAdapterBase pager_adapter;

	Handler handler;

	@Override public void onClick( View view ){
		switch( view.getId() ){

		case R.id.btnOnce:
			download_start( false );
			break;

		case R.id.btnRepeat:
			download_start( true );
			break;

		case R.id.btnStop:
			download_stop();
			break;

		case R.id.btnModeHelp:
			openHelp( R.layout.help_mode );
			break;

		}
	}

	boolean is_resume = false;

	@Override protected void onResume(){
		super.onResume();
		is_resume = true;

		Page0 page = pager_adapter.getPage( 0 );
		if( page != null ){
			page.ui_value_load();
			page.folder_view_update();
		}

		permission_request();
	}

	@Override protected void onPause(){
		is_resume = false;

		Page0 page = pager_adapter.getPage( 0 );
		if( page != null ) page.ui_value_save();

		super.onPause();
	}

	boolean is_start = false;

	@Override protected void onStart(){
		super.onStart();
		is_start = true;
		Page1 page = pager_adapter.getPage( 1 );
		if( page != null ) page.onStart();

		proc_status.run();
	}

	@Override protected void onStop(){
		is_start = false;
		handler.removeCallbacks( proc_status );
		Page1 page = pager_adapter.getPage( 1 );
		if( page != null ) page.onStop();
		super.onStop();
	}

	@Override public void onRequestPermissionsResult( int requestCode, @NonNull String permissions[], @NonNull int[] grantResults ){
		switch( requestCode ){
		case REQUEST_CODE_PERMISSION:
			permission_request();
			break;
		}
	}

	@Override public void onActivityResult( int requestCode, int resultCode, Intent resultData ){
		if( requestCode == REQUEST_CODE_DOCUMENT ){
			if( resultCode == Activity.RESULT_OK ){
				Uri treeUri = resultData.getData();
				// 永続的な許可を取得
				getContentResolver().takePersistableUriPermission( treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
				// 覚えておく
				Pref.pref( this ).edit()
					.putString( Pref.UI_FOLDER_URI, treeUri.toString() )
					.apply();
			}

			Page0 page = pager_adapter.getPage( 0 );
			if( page != null ) page.folder_view_update();

		}else{
			super.onActivityResult( requestCode, resultCode, resultData );
		}
	}

	@Override
	protected void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );
		setContentView( R.layout.act_main );

		handler = new Handler();

		findViewById( R.id.btnStop ).setOnClickListener( this );
		findViewById( R.id.btnOnce ).setOnClickListener( this );
		findViewById( R.id.btnRepeat ).setOnClickListener( this );
		findViewById( R.id.btnModeHelp ).setOnClickListener( this );

		tvStatus = (TextView) findViewById( R.id.tvStatus );

		pager = (ViewPager) findViewById( R.id.pager );

		pager_adapter = new PagerAdapterBase( this );
		pager_adapter.addPage( getString( R.string.setting ), R.layout.page0, Page0.class );
		pager_adapter.addPage( getString( R.string.log ), R.layout.page1, Page1.class );
		pager.setAdapter( pager_adapter );
	}

	/////////////////////////////////////////////////////////////////////////
	// アプリ権限の要求

	WeakReference<Dialog> permission_alert;

	void permission_request(){
		final ArrayList<String> missing_permission_list = PermissionChecker.getMissingPermissionList( this );
		if( ! missing_permission_list.isEmpty() ){
			Dialog dialog;

			// 既にダイアログを表示中なら何もしない
			if( permission_alert != null ){
				dialog = permission_alert.get();
				if( dialog != null && dialog.isShowing() ) return;
			}

			dialog = new AlertDialog.Builder( this )
				.setMessage( R.string.app_permission_required )
				.setPositiveButton( R.string.request_permission, new DialogInterface.OnClickListener(){
					@Override public void onClick( DialogInterface dialogInterface, int i ){
						ActivityCompat.requestPermissions( ActMain.this, missing_permission_list.toArray( new String[ missing_permission_list.size() ] ), REQUEST_CODE_PERMISSION );
					}
				} )
				.setNegativeButton( R.string.cancel, new DialogInterface.OnClickListener(){
					@Override public void onClick( DialogInterface dialogInterface, int i ){
						ActMain.this.finish();
					}
				} )
				.setNeutralButton( R.string.setting, new DialogInterface.OnClickListener(){
					@Override public void onClick( DialogInterface dialogInterface, int i ){
						Intent intent = new Intent();
						intent.setAction( Settings.ACTION_APPLICATION_DETAILS_SETTINGS );
						intent.setData( Uri.parse( "package:" + getPackageName() ) );
						startActivity( intent );
					}
				} )
				.setOnCancelListener( new DialogInterface.OnCancelListener(){
					@Override public void onCancel( DialogInterface dialogInterface ){
						ActMain.this.finish();
					}
				} )
				.create();
			dialog.show();
			permission_alert = new WeakReference<>( dialog );
		}
	}

	////////////////////////////////////////////////////////////

	// 転送サービスを停止
	private void download_stop(){
		Pref.pref( this ).edit()
			.putInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP )
			.putLong( Pref.LAST_MODE_UPDATE, System.currentTimeMillis() )
			.apply();
		Intent intent = new Intent( this, DownloadService.class );
		stopService( intent );

		try{
			PendingIntent pi = Utils.createAlarmPendingIntent( this );
			AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );
			am.cancel( pi );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	// 転送サービスを開始
	void download_start( boolean repeat ){

		// UIフォームの値を設定に保存
		Page0 page = pager_adapter.getPage( 0 );
		if( page != null ){
			page.ui_value_save();
		}

		// 設定から値を読んでバリデーション
		SharedPreferences pref = Pref.pref( this );
		String sv;

		String flashair_url = pref.getString( Pref.UI_FLASHAIR_URL, "" ).trim();
		if( TextUtils.isEmpty( flashair_url ) ){
			Toast.makeText( this, getString( R.string.url_not_ok ), Toast.LENGTH_SHORT ).show();
			return;
		}

		String folder_uri = null;
		sv = pref.getString( Pref.UI_FOLDER_URI, null );
		if( ! TextUtils.isEmpty( sv ) ){
			DocumentFile folder = DocumentFile.fromTreeUri( this, Uri.parse( sv ) );
			if( folder != null ){
				if( folder.exists() && folder.canWrite() ){
					folder_uri = sv;
				}
			}
		}
		if( folder_uri == null ){
			Toast.makeText( this, getString( R.string.folder_not_ok ), Toast.LENGTH_SHORT ).show();
			return;
		}

		int interval;
		try{
			interval = Integer.parseInt( pref.getString( Pref.UI_INTERVAL, "" ).trim(), 10 );
		}catch( Throwable ex ){
			interval = - 1;
		}
		if( repeat && interval < 1 ){
			Toast.makeText( this, getString( R.string.interval_not_ok ), Toast.LENGTH_SHORT ).show();
			return;
		}

		sv = pref.getString( Pref.UI_FILE_TYPE, "" );
		String file_type = sv.trim();
		if( TextUtils.isEmpty( file_type ) ){
			Toast.makeText( this, getString( R.string.file_type_empty ), Toast.LENGTH_SHORT ).show();
			return;
		}

		// 最後に押したボタンを覚えておく
		Pref.pref( this ).edit()
			.putInt( Pref.LAST_MODE, repeat ? Pref.LAST_MODE_REPEAT : Pref.LAST_MODE_ONCE )
			.putLong( Pref.LAST_MODE_UPDATE, System.currentTimeMillis() )
			.apply();

		// 転送サービスを開始
		Intent intent = new Intent( this, DownloadService.class );
		intent.setAction( DownloadService.ACTION_START );
		intent.putExtra( DownloadService.EXTRA_REPEAT, repeat );
		intent.putExtra( DownloadService.EXTRA_URI, flashair_url );
		intent.putExtra( DownloadService.EXTRA_FOLDER_URI, folder_uri );
		intent.putExtra( DownloadService.EXTRA_INTERVAL, interval );
		intent.putExtra( DownloadService.EXTRA_FILE_TYPE, file_type );
		startService( intent );
	}

	final Runnable proc_status = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( proc_status );
			handler.postDelayed( proc_status, 1000L );

			String status = DownloadService.getStatusForActivity(ActMain.this);
			tvStatus.setText( status );
		}
	};

	void openHelp( int layout_id ){
		View v = getLayoutInflater().inflate( layout_id, null, false );
		final Dialog d = new Dialog( this );
		d.requestWindowFeature( Window.FEATURE_NO_TITLE );
		d.setContentView( v );
		d.getWindow().setLayout( WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT );
		d.show();
		v.findViewById( R.id.btnClose ).setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View view ){
				d.dismiss();
			}
		} );
	}
}
