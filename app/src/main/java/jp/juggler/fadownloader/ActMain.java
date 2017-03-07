package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.example.android.trivialdrivesample.util.IabHelper;
import com.example.android.trivialdrivesample.util.IabResult;
import com.example.android.trivialdrivesample.util.Inventory;
import com.example.android.trivialdrivesample.util.Purchase;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import config.BuildVariant;

public class ActMain
	extends AppCompatActivity
	implements View.OnClickListener
{

	static final int REQUEST_CODE_PERMISSION = 1;
	static final int REQUEST_CODE_DOCUMENT = 2;
	static final int REQUEST_CHECK_SETTINGS = 3;
	static final int REQUEST_PURCHASE = 4;
	static final int REQUEST_FOLDER_PICKER = 5;

	TextView tvStatus;

	ViewPager pager;
	PagerAdapterBase pager_adapter;

	Handler handler;

	@Override public void onClick( View view ){
		switch( view.getId() ){

		case R.id.btnOnce:
			download_start_button( false );
			break;

		case R.id.btnRepeat:
			download_start_button( true );
			break;

		case R.id.btnStop:
			download_stop_button();
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

		if( mAdView != null ){
			mAdView.resume();
		}

		Page0 page = pager_adapter.getPage( 0 );
		if( page != null ){
			page.ui_value_load();
			page.folder_view_update();
		}

		permission_request();
	}

	@Override protected void onPause(){
		is_resume = false;

		if( mAdView != null ){
			mAdView.pause();
		}

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

		mGoogleApiClient.connect();

		proc_status.run();

	}

	@Override protected void onStop(){
		is_start = false;

		if( mGoogleApiClient.isConnected() ){
			mGoogleApiClient.disconnect();
		}

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
		// mIabHelper が結果を処理した
		if( mIabHelper != null && mIabHelper.handleActivityResult( requestCode, resultCode, resultData ) ) return;

		if( requestCode == REQUEST_CODE_DOCUMENT ){
			if( resultCode == Activity.RESULT_OK ){
				if( Build.VERSION.SDK_INT >= 21){
					try{
						Uri treeUri = resultData.getData();
						// 永続的な許可を取得
						getContentResolver().takePersistableUriPermission( treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
						// 覚えておく
						Pref.pref( this ).edit()
							.putString( Pref.UI_FOLDER_URI, treeUri.toString() )
							.apply();
					}catch(Throwable ex){
						ex.printStackTrace(  );
						Toast.makeText(this,String.format("folder access failed. %s %s",ex.getClass().getSimpleName(),ex.getMessage()),Toast.LENGTH_LONG).show();
					}
				}
			}
			Page0 page = pager_adapter.getPage( 0 );
			if( page != null ) page.folder_view_update();
			return;
		}else if ( requestCode == REQUEST_FOLDER_PICKER ){
			if( resultCode == Activity.RESULT_OK ){
				try{
					String path = resultData.getStringExtra( FolderPicker.EXTRA_FOLDER );
					String dummy = Thread.currentThread().getId()+"."+android.os.Process.myPid();
					File test_dir = new File( new File( path ), dummy );
					test_dir.mkdir();
					try{
						File test_file = new File( test_dir, dummy );
						try{
							FileOutputStream fos = new FileOutputStream( test_file );
							try{
								fos.write( Utils.encodeUTF8( "TEST" ) );
							}finally{
								fos.close();
							}
						}finally{
							test_file.delete();
						}
					}finally{
						test_dir.delete();
					}
					// 覚えておく
					Pref.pref( this ).edit()
						.putString( Pref.UI_FOLDER_URI, path )
						.apply();
				}catch(Throwable ex){
					ex.printStackTrace(  );
					Toast.makeText(this,String.format("folder access failed. %s %s",ex.getClass().getSimpleName(),ex.getMessage()),Toast.LENGTH_LONG).show();
				}
			}
			Page0 page = pager_adapter.getPage( 0 );
			if( page != null ) page.folder_view_update();
			return;
		}

		super.onActivityResult( requestCode, resultCode, resultData );
	}

	AdView mAdView;

	@Override
	protected void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );
		setContentView( R.layout.act_main );

		setupIabHelper();

		mAdView = (AdView) findViewById( R.id.adView );
		if( BuildVariant.IS_ADFREE ){
			( (ViewGroup) mAdView.getParent() ).removeView( mAdView );
			mAdView = null;
		}else{
			MobileAds.initialize( getApplicationContext(), getResources().getString( R.string.banner_ad_unit_id ) );
			AdRequest adRequest = new AdRequest.Builder().build();
			mAdView.loadAd( adRequest );
		}

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
		pager_adapter.addPage( getString( R.string.other ), R.layout.page2, Page2.class );
		pager.setAdapter( pager_adapter );

		mGoogleApiClient = new GoogleApiClient.Builder( this )
			.addConnectionCallbacks( connection_callback )
			.addOnConnectionFailedListener( connection_fail_callback )
			.addApi( LocationServices.API )
			.build();
	}

	@Override
	public void onDestroy(){
		if( mAdView != null ){
			mAdView.destroy();
		}
		if( mIabHelper != null ){
			mIabHelper.dispose();
			mIabHelper = null;
		}

		super.onDestroy();
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
	private void download_stop_button(){
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
	void download_start_button( boolean repeat ){

		// UIフォームの値を設定に保存
		Page0 page = pager_adapter.getPage( 0 );
		if( page != null ){
			page.ui_value_save();
		}

		//repeat引数の値は、LocationSettingの確認が終わるまで覚えておく必要がある
		Pref.pref( this ).edit().putBoolean( Pref.UI_REPEAT, repeat ).apply();

		if( Pref.pref( this ).getInt( Pref.UI_LOCATION_MODE, - 1 ) == LocationTracker.NO_LOCATION_UPDATE ){
			// 位置情報を使わないオプションの時はLocationSettingをチェックしない
			startDownloadService();
		}else if( mGoogleApiClient.isConnected() ){
			startLocationSettingCheck();
		}else{
			Toast.makeText( this, getString( R.string.google_api_not_connected ), Toast.LENGTH_SHORT ).show();
		}
	}

	protected LocationSettingsRequest mLocationSettingsRequest;

	void startLocationSettingCheck(){
		long UPDATE_INTERVAL_IN_MILLISECONDS = 10000;
		long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

		LocationRequest mLocationRequest = new LocationRequest();
		mLocationRequest.setInterval( UPDATE_INTERVAL_IN_MILLISECONDS );
		mLocationRequest.setFastestInterval( FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS );
		mLocationRequest.setPriority( LocationRequest.PRIORITY_HIGH_ACCURACY );

		LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
		builder.addLocationRequest( mLocationRequest );
		mLocationSettingsRequest = builder.build();

		PendingResult<LocationSettingsResult> result =
			LocationServices.SettingsApi.checkLocationSettings(
				mGoogleApiClient,
				mLocationSettingsRequest
			);
		result.setResultCallback( location_setting_callback );
	}

	final ResultCallback<LocationSettingsResult> location_setting_callback = new ResultCallback<LocationSettingsResult>(){
		@Override public void onResult( @NonNull LocationSettingsResult locationSettingsResult ){
			Status status = locationSettingsResult.getStatus();
			if( status == null ) return;
			int status_code = status.getStatusCode();
			switch( status_code ){
			case LocationSettingsStatusCodes.SUCCESS:
				startDownloadService();
				break;
			case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
				try{
					// Show the dialog by calling startResolutionForResult(), and check the result
					// in onActivityResult().
					status.startResolutionForResult( ActMain.this, REQUEST_CHECK_SETTINGS );
				}catch( IntentSender.SendIntentException ex ){
					ex.printStackTrace();
					Toast.makeText( ActMain.this, getString( R.string.resolution_request_failed ), Toast.LENGTH_LONG ).show();
				}
				break;
			case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
				Toast.makeText( ActMain.this, getString( R.string.location_setting_change_unavailable ), Toast.LENGTH_LONG ).show();
				break;
			default:
				Toast.makeText( ActMain.this, getString( R.string.location_setting_returns_unknown_status, status_code ), Toast.LENGTH_LONG ).show();
				break;

			}
		}
	};

	void startDownloadService(){
		SharedPreferences pref = Pref.pref( this );
		String sv;

		// LocationSettingを確認する前のrepeat引数の値を思い出す
		boolean repeat = pref.getBoolean( Pref.UI_REPEAT, false );

		// 設定から値を読んでバリデーション
		String flashair_url = pref.getString( Pref.UI_FLASHAIR_URL, "" ).trim();
		if( TextUtils.isEmpty( flashair_url ) ){
			Toast.makeText( this, getString( R.string.url_not_ok ), Toast.LENGTH_SHORT ).show();
			return;
		}

		String folder_uri = null;
		sv = pref.getString( Pref.UI_FOLDER_URI, null );
		if( ! TextUtils.isEmpty( sv ) ){
			if( Build.VERSION.SDK_INT >= 21 ){
				DocumentFile folder = DocumentFile.fromTreeUri( this, Uri.parse( sv ) );
				if( folder != null ){
					if( folder.exists() && folder.canWrite() ){
						folder_uri = sv;
					}
				}
			}else{
				folder_uri = sv;
			}
		}
		if( TextUtils.isEmpty( folder_uri ) ){
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

		int location_mode = pref.getInt( Pref.UI_LOCATION_MODE, - 1 );
		if( location_mode < 0 || location_mode > LocationTracker.LOCATION_HIGH_ACCURACY ){
			Toast.makeText( this, getString( R.string.location_mode_invalid ), Toast.LENGTH_SHORT ).show();
			return;
		}

		long location_update_interval_desired = LocationTracker.DEFAULT_INTERVAL_DESIRED;
		long location_update_interval_min = LocationTracker.DEFAULT_INTERVAL_MIN;

		if( location_mode != LocationTracker.NO_LOCATION_UPDATE ){
			try{
				location_update_interval_desired = 1000L * Long.parseLong(
					pref.getString( Pref.UI_LOCATION_INTERVAL_DESIRED, "" ).trim(), 10 );
			}catch( Throwable ex ){
				location_update_interval_desired = - 1L;
			}
			if( repeat && location_update_interval_desired < 1000L ){
				Toast.makeText( this, getString( R.string.location_update_interval_not_ok ), Toast.LENGTH_SHORT ).show();
				return;
			}

			try{
				location_update_interval_min = 1000L * Long.parseLong(
					pref.getString( Pref.UI_LOCATION_INTERVAL_MIN, "" ).trim(), 10 );
			}catch( Throwable ex ){
				location_update_interval_min = - 1L;
			}
			if( repeat && location_update_interval_min < 1000L ){
				Toast.makeText( this, getString( R.string.location_update_interval_not_ok ), Toast.LENGTH_SHORT ).show();
				return;
			}
		}

		boolean force_wifi = pref.getBoolean( Pref.UI_FORCE_WIFI, false );

		String ssid;
		if( !force_wifi){
			ssid = "";
		}else{
			sv = pref.getString( Pref.UI_SSID, "" );
			ssid = sv.trim();
			if( TextUtils.isEmpty( ssid ) ){
				Toast.makeText( this, getString( R.string.ssid_empty ), Toast.LENGTH_SHORT ).show();
				return;
			}
		}

		// 最後に押したボタンを覚えておく
		pref.edit()
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

		intent.putExtra( DownloadService.EXTRA_LOCATION_INTERVAL_DESIRED, location_update_interval_desired );
		intent.putExtra( DownloadService.EXTRA_LOCATION_INTERVAL_MIN, location_update_interval_min );
		intent.putExtra( DownloadService.EXTRA_LOCATION_MODE, location_mode );
		intent.putExtra( DownloadService.EXTRA_FORCE_WIFI, force_wifi );
		intent.putExtra( DownloadService.EXTRA_SSID, ssid );

		startService( intent );
	}

	final Runnable proc_status = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( proc_status );
			handler.postDelayed( proc_status, 1000L );

			String status = DownloadService.getStatusForActivity( ActMain.this );
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
	void openHelp( String text ){
		View v = getLayoutInflater().inflate( R.layout.help_single_text, null, false );
		((TextView)v.findViewById( R.id.text )).setText(text);

		final Dialog d = new Dialog( this );
		d.requestWindowFeature( Window.FEATURE_NO_TITLE );
		d.setContentView( v );
		d.getWindow().setLayout( WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT );
		d.show();
		v.findViewById( R.id.btnClose ).setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View view ){
				d.dismiss();
			}
		} );
	}
	GoogleApiClient mGoogleApiClient;

	final GoogleApiClient.OnConnectionFailedListener connection_fail_callback = new GoogleApiClient.OnConnectionFailedListener(){
		@Override public void onConnectionFailed( @NonNull ConnectionResult connectionResult ){
			int code = connectionResult.getErrorCode();
			String msg = connectionResult.getErrorMessage();
			if( TextUtils.isEmpty( msg )){
				switch(code){
				case ConnectionResult.SUCCESS: msg="SUCCESS";break;
				case ConnectionResult.SERVICE_MISSING: msg="SERVICE_MISSING";break;
				case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED: msg="SERVICE_VERSION_UPDATE_REQUIRED";break;
				case ConnectionResult.SERVICE_DISABLED: msg="SERVICE_DISABLED";break;
				case ConnectionResult.SIGN_IN_REQUIRED: msg="SIGN_IN_REQUIRED";break;
				case ConnectionResult.INVALID_ACCOUNT: msg="INVALID_ACCOUNT";break;
				case ConnectionResult.RESOLUTION_REQUIRED: msg="RESOLUTION_REQUIRED";break;
				case ConnectionResult.NETWORK_ERROR: msg="NETWORK_ERROR";break;
				case ConnectionResult.INTERNAL_ERROR: msg="INTERNAL_ERROR";break;
				case ConnectionResult.SERVICE_INVALID: msg="SERVICE_INVALID";break;
				case ConnectionResult.DEVELOPER_ERROR: msg="DEVELOPER_ERROR";break;
				case ConnectionResult.LICENSE_CHECK_FAILED: msg="LICENSE_CHECK_FAILED";break;
				case ConnectionResult.CANCELED: msg="CANCELED";break;
				case ConnectionResult.TIMEOUT: msg="TIMEOUT";break;
				case ConnectionResult.INTERRUPTED: msg="INTERRUPTED";break;
				case ConnectionResult.API_UNAVAILABLE: msg="API_UNAVAILABLE";break;
				case ConnectionResult.SIGN_IN_FAILED: msg="SIGN_IN_FAILED";break;
				case ConnectionResult.SERVICE_UPDATING: msg="SERVICE_UPDATING";break;
				case ConnectionResult.SERVICE_MISSING_PERMISSION: msg="SERVICE_MISSING_PERMISSION";break;
				case ConnectionResult.RESTRICTED_PROFILE: msg="RESTRICTED_PROFILE";break;

				}
			}

			msg = getString( R.string.play_service_connection_failed,code,  msg);
			Toast.makeText( ActMain.this, msg, Toast.LENGTH_SHORT ).show();

			if( code == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ){
				try{
					Intent intent = new Intent( Intent.ACTION_VIEW );
					intent.setData( Uri.parse( "market://details?id=com.google.android.gms" ) );
					startActivity( intent );
				}catch(Throwable ex){
					ex.printStackTrace(  );
				}

			}
		}
	};
	final GoogleApiClient.ConnectionCallbacks connection_callback = new GoogleApiClient.ConnectionCallbacks(){
		@Override public void onConnected( @Nullable Bundle bundle ){
			permission_request();
		}

		// Playサービスとの接続が失われた
		@Override public void onConnectionSuspended( int i ){
			Toast.makeText( ActMain.this, getString( R.string.play_service_connection_suspended
				, i ), Toast.LENGTH_SHORT ).show();
			mGoogleApiClient.connect();
		}
	};

	static final String APP_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkTbDT+kbberoRK6QHAKNzuKsFh0zSVJk97trga30ZHHyQHPsHtIJCvIibgHmm5QL6xr9TualN5iYMfNKA4bZM3x25kNiJ0NVuP86sravHdTyVuZyIu2WUI1CNdGRun5GYSGtxXNOuZujRkPtIMGjl750Z18CirrXYkl85KHDLgiOAu+d7HjssQ215+Qfo7iJIl30CYgcBl+szfH42MQK2Jd03LeTMf+5MA/ve/6iL2I1nyZrtWrC6Sw1uqOqjB9jx8cJALOrX+CmDa+si9krAI7gcOV/E8CJvVyC7cPxxooB425S8xHTr/MPjkEmwnu7ppMk5MyO+G1XP927fVg0ywIDAQAB";
	static final String REMOVE_AD_PRODUCT_ID = "remove_ad";
	static final String TAG = "ActMain";

	IabHelper mIabHelper;
	boolean bSetupCompleted;
	boolean bRemoveAdPurchased;

	// onCreateから呼ばれる
	void setupIabHelper(){
		//noinspection SimplifiableIfStatement
		if( BuildVariant.IS_ADFREE ){
			bRemoveAdPurchased = true;
		}else{
			bRemoveAdPurchased = Pref.pref( this ).getBoolean( Pref.REMOVE_AD_PURCHASED, false );
		}
		if( ! bRemoveAdPurchased ){
			mIabHelper = new IabHelper( this, APP_PUBLIC_KEY );
			mIabHelper.startSetup( new IabHelper.OnIabSetupFinishedListener(){
				public void onIabSetupFinished( IabResult result ){
					// return if activity is destroyed
					if( mIabHelper == null ) return;

					if( ! result.isSuccess() ){
						Log.d( TAG, "onIabSetupFinished: "
							+ result.getResponse()
							+ "," + result.getMessage()
						);
						return;
					}

					bSetupCompleted = true;

					// セットアップが終わったら購入済みアイテムの確認を開始する
					mIabHelper.queryInventoryAsync( mGotInventoryListener );
				}
			} );
		}
	}

	// 購入済みアイテム取得のコールバック
	final IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener(){
		public void onQueryInventoryFinished( IabResult result, Inventory inventory ){

			// return if activity is destroyed
			if( mIabHelper == null ) return;

			if( result.isFailure() ){
				Log.d( TAG, "onQueryInventoryFinished: "
					+ result.getResponse()
					+ "," + result.getMessage()
				);
				return;
			}

			// 広告除去アイテムがあれば広告を非表示にする
			remove_ad( inventory.getPurchase( REMOVE_AD_PRODUCT_ID ) != null );
		}
	};

	// 購入開始
	public void startRemoveAdPurchase(){
		try{
			mIabHelper.launchPurchaseFlow(
				this
				, REMOVE_AD_PRODUCT_ID
				, IabHelper.ITEM_TYPE_INAPP
				, REQUEST_PURCHASE
				, mPurchaseFinishedListener
				, null
			);
		}catch( IllegalStateException ex ){
			ex.printStackTrace();
		}
	}

	// 購入結果の受け取り用メソッド
	final IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener
		= new IabHelper.OnIabPurchaseFinishedListener(){
		public void onIabPurchaseFinished( IabResult result, Purchase purchase ){
			// return if activity destroyed
			if( mIabHelper == null ) return;

			if( result.isFailure() ){
				Log.d( TAG, "onIabPurchaseFinished: "
					+ result.getResponse()
					+ "," + result.getMessage()
				);
				return;
			}

			remove_ad( purchase.getSku().equals( REMOVE_AD_PRODUCT_ID ) );
		}
	};

	void remove_ad( boolean isPurchased ){
		bRemoveAdPurchased = isPurchased;
		if( isPurchased ){
			if( mAdView != null ){
				( (ViewGroup) mAdView.getParent() ).removeView( mAdView );
				mAdView.destroy();
				mAdView = null;
			}
		}

		Page2 page = pager_adapter.getPage( 2 );
		if( page != null ) page.updatePurchaseButton();
	}
}
