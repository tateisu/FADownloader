package jp.juggler.fadownloader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.LayoutRes;
import android.support.v4.provider.DocumentFile;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class Page0 extends PagerAdapterBase.PageViewHolder implements View.OnClickListener{

	EditText etURL;
	TextView tvFolder;
	EditText etInterval;
	EditText etFileType;
	Spinner spLocaitonMode;
	EditText etLocationIntervalDesired;
	EditText etLocationIntervalMin;

	public Page0( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{

		etURL = (EditText) root.findViewById( R.id.etURL );
		tvFolder = (TextView) root.findViewById( R.id.tvFolder );
		etInterval = (EditText) root.findViewById( R.id.etInterval );
		etFileType = (EditText) root.findViewById( R.id.etFileType );
		spLocaitonMode = (Spinner) root.findViewById( R.id.spLocaitonMode );
		etLocationIntervalDesired = (EditText) root.findViewById( R.id.etLocationIntervalDesired );
		etLocationIntervalMin = (EditText) root.findViewById( R.id.etLocationIntervalMin );

		root.findViewById( R.id.btnFolderPicker ).setOnClickListener( this );
		root.findViewById( R.id.btnFolderPickerHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnFlashAirURLHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnIntervalHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnFileTypeHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnLocationModeHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnLocationIntervalDesiredHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnLocationIntervalMinHelp ).setOnClickListener( this );
		root.findViewById( R.id.btnOSSLicence ).setOnClickListener( this );


		ArrayAdapter<CharSequence> locatiom_mode_adapter = new ArrayAdapter<>(
			activity
			,android.R.layout.simple_spinner_item
		);
		locatiom_mode_adapter.setDropDownViewResource( R.layout.spinner_dropdown );


		locatiom_mode_adapter.addAll(
			activity.getString(R.string.location_mode_0),
			activity.getString(R.string.location_mode_1),
			activity.getString(R.string.location_mode_2),
			activity.getString(R.string.location_mode_3),
			activity.getString(R.string.location_mode_4)
		);
		spLocaitonMode.setAdapter( locatiom_mode_adapter );
		spLocaitonMode.setOnItemSelectedListener( new AdapterView.OnItemSelectedListener(){
			@Override public void onItemSelected( AdapterView<?> parent, View view, int position, long id ){
				updateFormEnabled();
			}

			@Override public void onNothingSelected( AdapterView<?> parent ){
				updateFormEnabled();
			}
		} );

		ui_value_load();
		folder_view_update();
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
		ui_value_save();
	}

	@Override public void onClick( View view ){
		switch( view.getId() ){
		case R.id.btnFolderPicker:
			folder_pick();
			break;
		case R.id.btnFolderPickerHelp:
			( (ActMain) activity ).openHelp( R.layout.help_local_folder );
			break;
		case R.id.btnFlashAirURLHelp:
			( (ActMain) activity ).openHelp( R.layout.help_flashair_url );
			break;
		case R.id.btnIntervalHelp:
			( (ActMain) activity ).openHelp( R.layout.help_interval );
			break;
		case R.id.btnFileTypeHelp:
			( (ActMain) activity ).openHelp( R.layout.help_file_type );
			break;
		case R.id.btnLocationModeHelp:
			( (ActMain) activity ).openHelp( R.layout.help_location_mode );
			break;
		case R.id.btnLocationIntervalDesiredHelp:
			( (ActMain) activity ).openHelp( R.layout.help_location_interval_desired );
			break;
		case R.id.btnLocationIntervalMinHelp:
			( (ActMain) activity ).openHelp( R.layout.help_location_interval_min );
			break;

		case R.id.btnOSSLicence:
			( (ActMain) activity ).openHelp( R.layout.help_oss_license );
			break;

		}
	}

	// UIフォームの値を設定から読み出す
	void ui_value_load(){
		SharedPreferences pref = Pref.pref( activity );
		String sv;
		int iv;
		//
		sv = pref.getString( Pref.UI_FLASHAIR_URL, null );
		if( sv != null ) etURL.setText( sv );
		//
		sv = pref.getString( Pref.UI_INTERVAL, null );
		if( sv != null ) etInterval.setText( sv );
		//
		sv = pref.getString( Pref.UI_FILE_TYPE, null );
		if( sv != null ) etFileType.setText( sv );
		//
		iv = pref.getInt( Pref.UI_LOCATION_MODE,-1);
		if( iv >= 0 && iv < spLocaitonMode.getCount() ) spLocaitonMode.setSelection( iv );
		//
		sv = pref.getString( Pref.UI_LOCATION_INTERVAL_DESIRED, null );
		if( sv != null ) etLocationIntervalDesired.setText( sv );
		//
		sv = pref.getString( Pref.UI_LOCATION_INTERVAL_MIN, null );
		if( sv != null ) etLocationIntervalMin.setText( sv );

		updateFormEnabled();
	}
	private void updateFormEnabled(){
		boolean location_enabled = (spLocaitonMode.getSelectedItemPosition() > 0);
		etLocationIntervalDesired.setEnabled( location_enabled );
		etLocationIntervalMin.setEnabled( location_enabled );
	}

	// UIフォームの値を設定ファイルに保存
	void ui_value_save(){
		Pref.pref( activity ).edit()
			.putString( Pref.UI_FLASHAIR_URL, etURL.getText().toString() )
			.putString( Pref.UI_INTERVAL, etInterval.getText().toString() )
			.putString( Pref.UI_FILE_TYPE, etFileType.getText().toString() )
			.putInt( Pref.UI_LOCATION_MODE,spLocaitonMode.getSelectedItemPosition() )
			.putString( Pref.UI_LOCATION_INTERVAL_DESIRED,etLocationIntervalDesired.getText().toString() )
			.putString( Pref.UI_LOCATION_INTERVAL_MIN,etLocationIntervalMin.getText().toString() )
			.apply();
	}

	// 転送先フォルダの選択を開始
	void folder_pick(){
		Intent intent = new Intent( Intent.ACTION_OPEN_DOCUMENT_TREE );
		activity.startActivityForResult( intent, ActMain.REQUEST_CODE_DOCUMENT );
	}

	// フォルダの表示を更新
	String folder_view_update(){
		String folder_uri = null;

		String sv = Pref.pref( activity ).getString( Pref.UI_FOLDER_URI, null );
		if( ! TextUtils.isEmpty( sv ) ){
			DocumentFile folder = DocumentFile.fromTreeUri( activity, Uri.parse( sv ) );
			if( folder != null ){
				if( folder.exists() && folder.canWrite() ){
					folder_uri = sv;
					tvFolder.setText( folder.getName() );
				}
			}
		}

		if( folder_uri == null ){
			tvFolder.setText( R.string.not_selected );
		}

		return folder_uri;
	}
}
