package jp.juggler.fadownloader;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import java.util.Locale;

public class PageOther extends PagerAdapterBase.PageViewHolder implements View.OnClickListener{

	View btnRemoveAd;

	public PageOther( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{
		btnRemoveAd = root.findViewById( R.id.btnRemoveAd );

		root.findViewById( R.id.btnLogClear ).setOnClickListener( this );
		root.findViewById( R.id.btnOSSLicence ).setOnClickListener( this );
		root.findViewById( R.id.btnWifiSetting ).setOnClickListener( this );
		root.findViewById( R.id.btnOpenMap ).setOnClickListener( this );
		root.findViewById( R.id.btnRecordClear ).setOnClickListener( this );
		root.findViewById( R.id.btnRemoveAd ).setOnClickListener( this );

		updatePurchaseButton();
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
	}

	@Override public void onClick( View view ){
		switch( view.getId() ){

		case R.id.btnLogClear:
			activity.getContentResolver().delete( LogData.meta.content_uri, null, null );
			break;

		case R.id.btnRecordClear:
			activity.getContentResolver().delete( DownloadRecord.meta.content_uri, null, null );
			break;


		case R.id.btnOSSLicence:
			( (ActMain) activity ).openHelp( activity.getString( R.string.help_oss_license_long ) );
			break;

		case R.id.btnRemoveAd:
			( (ActMain) activity ).startRemoveAdPurchase();
			break;

		case R.id.btnWifiSetting:
			try{
				activity.startActivity( new Intent( Settings.ACTION_WIFI_SETTINGS ) );
			}catch( Throwable ex ){
				ex.printStackTrace();
			}
			break;

		case R.id.btnOpenMap:
			try{
				Location location = DownloadService.getLocation();
				if( location != null){
					Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format( Locale.JAPAN,"geo:%f,%f",location.getLatitude(),location.getLongitude())));
					activity.startActivity( intent );
				}else{
					Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:"));
					activity.startActivity( intent );
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
			}
			break;

		}
	}

	public void updatePurchaseButton(){
		ActMain act = (ActMain) activity;
		//noinspection ConstantConditions
		btnRemoveAd.setVisibility( act.bSetupCompleted && ! act.bRemoveAdPurchased ? View.VISIBLE : View.GONE );
	}
}
