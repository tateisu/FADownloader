package jp.juggler.fadownloader;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

public class Page2 extends PagerAdapterBase.PageViewHolder implements View.OnClickListener{

	View btnRemoveAd;


	public Page2( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{
		btnRemoveAd = root.findViewById( R.id.btnRemoveAd );
		btnRemoveAd.setOnClickListener( this );

		root.findViewById( R.id.btnLogClear ).setOnClickListener( this );


		root.findViewById( R.id.btnOSSLicence ).setOnClickListener( this );
		root.findViewById( R.id.btnWifiSetting ).setOnClickListener( this );

		updatePurchaseButton();
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
	}

	@Override public void onClick( View view ){
		switch( view.getId() ){
		case R.id.btnLogClear:
			activity.getContentResolver().delete( LogData.meta.content_uri, null, null );
			break;

		case R.id.btnOSSLicence:
			( (ActMain) activity ).openHelp( activity.getString(R.string.help_oss_license_long ) );
			break;

		case R.id.btnRemoveAd:
			( (ActMain) activity ).startRemoveAdPurchase();
			break;
		case R.id.btnWifiSetting:
			try{
				activity.startActivity( new Intent( Settings.ACTION_WIFI_SETTINGS ) );
			}catch(Throwable ex){
				ex.printStackTrace(  );
			}

		}
	}

	public void updatePurchaseButton(){
		ActMain act = (ActMain) activity;
		//noinspection ConstantConditions
		btnRemoveAd.setVisibility( act.bSetupCompleted && ! act.bRemoveAdPurchased ? View.VISIBLE : View.GONE );
	}
}
