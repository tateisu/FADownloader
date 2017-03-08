package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

	void showToast(boolean bLong,String s){
		Toast.makeText( this
			, s
			, bLong ?Toast.LENGTH_LONG:Toast.LENGTH_SHORT
		).show();
	}

	void showToast(Throwable ex,String s){
		Toast.makeText( this
			, s+String.format(":%s %s",ex.getClass().getSimpleName(),ex.getMessage())
			, Toast.LENGTH_LONG
		).show();
	}

	TextView tvCurrentFolder;
	View btnFolderUp;
	View btnSubFolder;
	ListView lvFileList;
	Button btnSelectFolder;
	File showing_folder;
	ArrayAdapter<String> list_adapter;

	@Override public void onClick( View v ){
		switch( v.getId() ){
		case R.id.btnFolderUp:
			loadFolder( showing_folder.getParentFile() );
			break;
		case R.id.btnSelectFolder:
			Intent intent = new Intent();
			intent.putExtra( EXTRA_FOLDER, showing_folder.getAbsolutePath() );
			setResult( Activity.RESULT_OK, intent );
			finish();
			break;
		case R.id.btnSubFolder:
			openFolderCreateDialog();
			break;
		}
	}

	@Override public void onItemClick( AdapterView<?> parent, View view, int position, long id ){
		String name = list_adapter.getItem( position );
		if( name != null ){
			File folder = new File( showing_folder, name );
			if( ! folder.isDirectory() ){
				showToast(false,getString( R.string.folder_not_directory ));
			}else if( ! folder.canWrite() ){
				showToast(false,getString( R.string.folder_not_writable ));
			}else{
				loadFolder( folder );
			}
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
		btnSubFolder = findViewById( R.id.btnSubFolder );
		lvFileList = (ListView) findViewById( R.id.lvFileList );
		btnSelectFolder = (Button) findViewById( R.id.btnSelectFolder );

		btnFolderUp.setOnClickListener( this );
		btnSelectFolder.setOnClickListener( this );
		btnSubFolder.setOnClickListener( this );
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
		tvCurrentFolder.setText( R.string.loading );
		btnFolderUp.setEnabled( false );
		btnSubFolder.setEnabled( false );
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
					btnSubFolder.setEnabled( true );
					btnSelectFolder.setText( getString( R.string.folder_select, folder.getAbsolutePath() ) );
					btnSelectFolder.setEnabled( true );
					list_adapter.addAll( result );
				}
			}
		}.execute();
	}

	private void openFolderCreateDialog(){
		View root = getLayoutInflater().inflate(R.layout.folder_create_dialog,null,false);
		final View btnCancel = root.findViewById( R.id.btnCancel );
		final View btnOk = root.findViewById( R.id.btnOk );
		final EditText etName = (EditText) root.findViewById( R.id.etName );
		final Dialog d = new Dialog(this);
		d.setTitle( getString(R.string.create_sub_folder) );
		d.setContentView( root );
		//noinspection ConstantConditions
		d.getWindow().setLayout( WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT );
		d.show();
		etName.setOnEditorActionListener( new TextView.OnEditorActionListener(){
			@Override public boolean onEditorAction( TextView v, int actionId, KeyEvent event ){
				if( actionId == EditorInfo.IME_ACTION_DONE ){
					btnOk.performClick();
					return true;
				}
				return false;
			}
		} );
		btnCancel.setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View v ){
				d.dismiss();
			}
		} );
		btnOk.setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View v ){
				try{
					String name = etName.getText().toString().trim();
					if( TextUtils.isEmpty( name ) ){
						showToast( false, getString( R.string.folder_name_empty ) );
					}else{
						File folder = new File( showing_folder, name );
						if( folder.exists() ){
							showToast( false, getString( R.string.folder_already_exist ) );
						}else if( ! folder.mkdir() ){
							showToast( false, getString( R.string.folder_creation_failed ) );
						}else{
							d.dismiss();
							loadFolder( showing_folder );
						}
					}
				}catch(Throwable ex){
					showToast(ex,"folder creation failed.");
				}
			}
		} );
	}
}
