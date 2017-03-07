package jp.juggler.fadownloader;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

public class FolderPicker extends AppCompatActivity implements View.OnClickListener, AdapterView.OnItemClickListener{

	static File parseExistPath( String path ){
		for( ; ; ){
			try{
				if( TextUtils.isEmpty( path ) ) path = Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_PICTURES ).getAbsolutePath();
			}catch( Throwable ignored ){
			}
			try{
				if( TextUtils.isEmpty( path ) ) path = Environment.getExternalStorageDirectory().getAbsolutePath();
			}catch( Throwable ignored ){
			}
			try{
				if( TextUtils.isEmpty( path ) ) path = "/";
			}catch( Throwable ignored ){
			}
			@SuppressWarnings( "ConstantConditions" )
			File f = new File( path );
			if( f.isDirectory() ) return f;
			path = null;
		}
	}

	public static final String EXTRA_FOLDER = "folder";

	public static void open( Activity activity, int request_code, String path ){
		try{
			Intent intent = new Intent( activity, FolderPicker.class );
			intent.putExtra( FolderPicker.EXTRA_FOLDER, path );
			activity.startActivityForResult( intent, request_code );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	TextView tvCurrentFolder;
	View btnFolderUp;
	ListView lvFileList;
	Button btnSelectFolder;
	File showing_folder;
	ArrayAdapter<CharSequence> list_adapter;

	@Override public void onClick( View v ){
		switch( v.getId() ){
		case R.id.btnFolderUp:
			loadFolder( showing_folder.getParentFile() );
			break;
		case R.id.btnSelectFolder:
			Intent intent = new Intent();
			intent.putExtra( EXTRA_FOLDER, showing_folder.getAbsolutePath() );
			setResult( Activity.RESULT_OK ,intent);
			finish();
			break;
		}
	}

	@Override public void onItemClick( AdapterView<?> parent, View view, int position, long id ){
		File folder = new File( showing_folder, list_adapter.getItem( position ).toString() );
		if( ! folder.isDirectory() ){
			Toast.makeText( this, getString( R.string.folder_not_directory ), Toast.LENGTH_SHORT ).show();
		}else if( ! folder.canWrite() ){
			Toast.makeText( this, getString( R.string.folder_not_writable ), Toast.LENGTH_SHORT ).show();
		}else{
			loadFolder( folder );
		}
	}

	@Override protected void onSaveInstanceState( Bundle outState ){
		super.onSaveInstanceState( outState );
		outState.putString( EXTRA_FOLDER, showing_folder.getAbsolutePath() );
	}

	@Override protected void onCreate( @Nullable Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );
		setContentView( R.layout.folder_picker );

		tvCurrentFolder = (TextView) findViewById( R.id.tvCurrentFolder );
		btnFolderUp = findViewById( R.id.btnFolderUp );
		lvFileList = (ListView) findViewById( R.id.lvFileList );
		btnSelectFolder = (Button) findViewById( R.id.btnSelectFolder );

		btnFolderUp.setOnClickListener( this );
		btnSelectFolder.setOnClickListener( this );

		list_adapter = new ArrayAdapter<>( this, android.R.layout.simple_list_item_1 );
		lvFileList.setAdapter( list_adapter );
		lvFileList.setOnItemClickListener( this );

		if( savedInstanceState == null ){
			showing_folder = parseExistPath( getIntent().getStringExtra( EXTRA_FOLDER ) );
		}else{
			showing_folder = parseExistPath( savedInstanceState.getString( EXTRA_FOLDER ) );
		}
		loadFolder( showing_folder );
	}

	private void loadFolder( final File folder ){
		tvCurrentFolder.setText( "(loading..)" );
		btnFolderUp.setEnabled( false );
		btnSelectFolder.setEnabled( false );
		list_adapter.clear();
		new AsyncTask<Void, Void, ArrayList<String>>(){
			@Override protected ArrayList<String> doInBackground( Void... params ){
				ArrayList<String> result = new ArrayList<>();
				try{
					for( File sub : folder.listFiles() ){
						if( ! sub.isDirectory() ) continue;
						String name = sub.getName();
						if( "..".equals( name ) ) continue;
						if( ".".equals( name ) ) continue;
						result.add( name );
					}
				}catch( Throwable ex ){
					ex.printStackTrace();
				}
				Collections.sort( result, String.CASE_INSENSITIVE_ORDER );
				return result;
			}

			@Override protected void onPostExecute( ArrayList<String> result ){
				if( result != null ){
					showing_folder = folder;
					tvCurrentFolder.setText( folder.getAbsolutePath() );
					btnFolderUp.setEnabled( ! folder.getAbsolutePath().equals( "/" ) );
					btnSelectFolder.setText( getString( R.string.folder_select, folder.getAbsolutePath() ) );
					btnSelectFolder.setEnabled( true );
					list_adapter.addAll( result );
				}
			}
		}.execute();
	}
}
