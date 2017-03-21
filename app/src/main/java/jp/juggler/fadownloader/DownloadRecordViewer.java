package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import it.sephiroth.android.library.exif2.ExifInterface;
import it.sephiroth.android.library.exif2.Rational;

public class DownloadRecordViewer implements LoaderManager.LoaderCallbacks<Cursor>, AdapterView.OnItemClickListener{

	AppCompatActivity activity;
	ListView listview;
	int last_view_start;
	Loader<Cursor> loader;

	public DownloadRecordViewer(){
	}

	void onStart( AppCompatActivity activity, ListView listview, int loader_id ){
		this.activity = activity;
		this.listview = listview;
		listview.setOnItemClickListener( this );
		loader = activity.getSupportLoaderManager().initLoader( loader_id, null, this );
	}

	void onStop(){
		if( loader != null ){
			activity.getSupportLoaderManager().destroyLoader( loader.getId() );
			loader = null;
		}
		this.last_view_start = listview.getFirstVisiblePosition();
		listview.setAdapter( null );
		this.listview = null;
		this.activity = null;
	}

	@Override public Loader<Cursor> onCreateLoader( int id, Bundle args ){
		return activity == null ? null : new CursorLoader( activity, DownloadRecord.meta.content_uri, null, null, null, LogData.COL_TIME + " desc" );
	}

	@Override public void onLoadFinished( Loader<Cursor> loader, Cursor cursor ){
		if( listview == null ) return;
		RecordAdapter adapter = (RecordAdapter) listview.getAdapter();
		if( adapter == null ){
			listview.setAdapter( new RecordAdapter( activity, cursor ) );
		}else{
			adapter.swapCursor( cursor );
		}
	}

	@Override public void onLoaderReset( Loader<Cursor> loader ){
		if( listview == null ) return;
		RecordAdapter adapter = (RecordAdapter) listview.getAdapter();
		if( adapter != null ){
			adapter.swapCursor( null );
		}
	}

	public void reload(){
		if( listview == null ) return;
		RecordAdapter adapter = (RecordAdapter) listview.getAdapter();
		if( adapter != null ){
			adapter.reload();
		}
	}

	class RecordAdapter extends CursorAdapter{

		final DownloadRecord.ColIdx colIdx = new DownloadRecord.ColIdx();
		final DownloadRecord data = new DownloadRecord();
		final LayoutInflater inflater = activity.getLayoutInflater();
		final int thumbnail_size;
		boolean bThumbnailAutoRotate;

		DownloadRecord loadAt( int position ){
			Cursor cursor = getCursor();
			if( cursor.moveToPosition( position ) ){
				DownloadRecord result = new DownloadRecord();
				result.loadFrom( cursor, colIdx );
				return result;
			}
			return null;
		}

		public RecordAdapter( Context context, Cursor c ){
			super( context, c, false );

			this.thumbnail_size = (int) ( 0.5f + 64f * context.getResources().getDisplayMetrics().density );
			this.bThumbnailAutoRotate = Pref.pref( activity ).getBoolean( Pref.UI_THUMBNAIL_AUTO_ROTATE, Pref.DEFAULT_THUMBNAIL_AUTO_ROTATE );
		}

		public void reload(){
			this.bThumbnailAutoRotate = Pref.pref( activity ).getBoolean( Pref.UI_THUMBNAIL_AUTO_ROTATE, Pref.DEFAULT_THUMBNAIL_AUTO_ROTATE );
			notifyDataSetChanged();
		}

		@Override public View newView( Context context, Cursor cursor, ViewGroup viewGroup ){
			View root = inflater.inflate( R.layout.lv_download_record, viewGroup, false );
			root.setTag( new ViewHolder( root ) );
			return root;
		}

		@Override public void bindView( View view, Context context, Cursor cursor ){
			data.loadFrom( cursor, colIdx );

			ViewHolder holder = (ViewHolder) view.getTag();

			holder.bind( activity, data, thumbnail_size, bThumbnailAutoRotate );

		}

	}

	static final SimpleDateFormat date_fmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm", Locale.getDefault() );
	static final Drawable default_thumbnail = new ColorDrawable( 0xff808080 );
	static final Pattern reJPEG = Pattern.compile( "\\.jp(g|eg?)\\z", Pattern.CASE_INSENSITIVE );

	static class ViewHolder{

		final TextView tvName;
		final TextView tvTime;
		final TextView tvStateCode;
		final ImageView ivThumbnail;
		String last_image_uri;
		AsyncTask<Void, Void, Bitmap> last_task;
		Bitmap last_bitmap;
		final Matrix matrix = new Matrix();
		String time_str;
		String exif_info;
		boolean bThumbnailAutoRotate;

		ViewHolder( View root ){

			tvName = (TextView) root.findViewById( R.id.tvName );
			tvTime = (TextView) root.findViewById( R.id.tvTime );
			tvStateCode = (TextView) root.findViewById( R.id.tvStateCode );
			ivThumbnail = (ImageView) root.findViewById( R.id.ivThumbnail );
		}

		public void bind(
			final Activity activity
			, DownloadRecord data
			, final int thumbnail_size
			, final boolean bThumbnailAutoRotate
		){
			this.time_str = date_fmt.format( data.time );
			tvName.setText( new File( data.air_path ).getName() );
			tvStateCode.setText( DownloadRecord.formatStateText( activity, data.state_code, data.state_message ) );
			tvStateCode.setTextColor( DownloadRecord.getStateCodeColor( data.state_code ) );

			if( last_image_uri != null
				&& last_image_uri.equals( data.local_file )
				&& this.bThumbnailAutoRotate == bThumbnailAutoRotate
				){
				// 画像を再ロードする必要がない
				showTimeAndExif();
			}else{
				ivThumbnail.setImageDrawable( default_thumbnail );
				exif_info = null;
				showTimeAndExif();

				if( last_task != null ){
					last_task.cancel( true );
					last_task = null;
				}

				if( last_bitmap != null ){
					last_bitmap.recycle();
					last_bitmap = null;
				}

				this.bThumbnailAutoRotate = bThumbnailAutoRotate;
				this.last_image_uri = data.local_file;

				if( ! TextUtils.isEmpty( last_image_uri )
					&& reJPEG.matcher( data.air_path ).find()
					){
					last_task = new AsyncTask<Void, Void, Bitmap>(){
						final String image_uri = last_image_uri;
						Integer orientation;
						LinkedList<String> exif_list = new LinkedList<>();

						@Override protected Bitmap doInBackground( Void... params ){
							// たくさんキューイングされるので、開始した時点で既にキャンセルされていることがありえる
							if( isCancelled() ) return null;
							try{
								InputStream is;
								if( image_uri.startsWith( "/" ) ){
									is = new FileInputStream( image_uri );
								}else{
									is = activity.getContentResolver().openInputStream( Uri.parse( image_uri ) );
								}
								if( is != null ){
									Rational rv;
									String sv;
									try{
										if( isCancelled() ) return null;
										ExifInterface exif = new ExifInterface();
										exif.readExif( is, ExifInterface.Options.OPTION_ALL );

										if( bThumbnailAutoRotate ){
											orientation = exif.getTagIntValue( ExifInterface.TAG_ORIENTATION );
										}

										rv = exif.getTagRationalValue( ExifInterface.TAG_FOCAL_LENGTH );
										if( rv != null ){
											sv = String.format( "%.1f", rv.toDouble() ).replaceAll( "\\.0*$", "" );
											exif_list.add( sv + "mm" );
										}

										rv = exif.getTagRationalValue( ExifInterface.TAG_F_NUMBER );
										if( rv != null ){
											sv = String.format( "%.1f", rv.toDouble() ).replaceAll( "\\.0*$", "" );
											exif_list.add( "F" + sv );
										}

										rv = exif.getTagRationalValue( ExifInterface.TAG_EXPOSURE_TIME );
										if( rv != null ){
											double dv = rv.toDouble();
											if( dv > 0.25d ){
												sv = String.format( "%.1f", dv ).replaceAll( "\\.0*$", "" );
												exif_list.add( sv + "s" );
											}else{
												sv = String.format( "%.1f", 1 / dv ).replaceAll( "\\.0*$", "" );
												exif_list.add( "1/" + sv + "s" );
											}
										}

										boolean iso_done = false;
										Integer iv = exif.getTagIntValue( ExifInterface.TAG_SENSITIVITY_TYPE );
										if( iv != null && iv == ExifInterface.SensitivityType.SOS ){
											Long lv = exif.getTagLongValue( ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY );
											if( lv != null ){
												exif_list.add( "ISO" + lv );
												iso_done = true;
											}
										}
										if( ! iso_done ){
											iv = exif.getTagIntValue( ExifInterface.TAG_ISO_SPEED_RATINGS /*旧形式*/ );
											if( iv != null ){
												exif_list.add( "ISO" + iv );
											}
										}

										rv = exif.getTagRationalValue( ExifInterface.TAG_EXPOSURE_BIAS_VALUE );
										if( rv != null ){
											double d = rv.toDouble();
											if( d == 0f ){
												exif_list.add( String.format( "\u00b1%.1f", d ) );
											}else if( d > 0f ){
												exif_list.add( String.format( "+%.1f", d ) );
											}else{
												exif_list.add( String.format( "%.1f", d ) );
											}
										}

										sv = exif.getTagStringValue( ExifInterface.TAG_MODEL );
										if( ! TextUtils.isEmpty( sv ) ){
											exif_list.add( trimModelName( sv ) );
										}

										if( isCancelled() ) return null;
										return exif.getThumbnailBitmap();
									}finally{
										try{
											is.close();
										}catch( Throwable ignored ){
										}
									}
								}
							}catch( Throwable ex ){
								ex.printStackTrace();
							}
							return null;
						}

						@Override protected void onPostExecute( Bitmap bitmap ){
							if( bitmap == null ) return;

							int bitmap_w = bitmap.getWidth();
							int bitmap_h = bitmap.getHeight();
							if( bitmap_w < 1 || bitmap_h < 1 ){
								bitmap.recycle();
								return;
							}
							if( isCancelled() || ! this.image_uri.equals( last_image_uri ) ){
								bitmap.recycle();
								return;
							}
							last_task = null;

							exif_info = joinList( " ", exif_list );
							showTimeAndExif();

							last_bitmap = bitmap;
							ivThumbnail.setImageDrawable( new BitmapDrawable( activity.getResources(), bitmap ) );

							float scale;
							if( bitmap_w >= bitmap_h ){
								scale = thumbnail_size / (float) bitmap_w;
							}else{
								scale = thumbnail_size / (float) bitmap_h;
							}
							matrix.reset();
							// 画像の中心が原点に来るようにして
							matrix.postTranslate( bitmap_w * - 0.5f, bitmap_h * - 0.5f );
							// スケーリング
							matrix.postScale( scale, scale );
							// 回転情報があれば回転
							if( orientation != null ){
								switch( orientation.shortValue() ){
								default:
									break;
								case 2:
									matrix.postScale( 1f, - 1f );
									break; // 上限反転
								case 3:
									matrix.postRotate( 180f );
									break; // 180度回転
								case 4:
									matrix.postScale( - 1f, 1f );
									break; // 左右反転
								case 5:
									matrix.postScale( 1f, - 1f );
									matrix.postRotate( - 90f );
									break;
								case 6:
									matrix.postRotate( 90f );
									break;
								case 7:
									matrix.postScale( 1f, - 1f );
									matrix.postRotate( 90f );
									break;
								case 8:
									matrix.postRotate( - 90f );
									break;
								}
							}
							// 表示領域に埋まるように平行移動
							matrix.postTranslate( thumbnail_size * 0.5f, thumbnail_size * 0.5f );
							// ImageView にmatrixを設定
							ivThumbnail.setScaleType( ImageView.ScaleType.MATRIX );
							ivThumbnail.setImageMatrix( matrix );
						}
					};
					last_task.executeOnExecutor( AsyncTask.SERIAL_EXECUTOR );
				}
			}

//			int fg;
//			if( level >= LogData.LEVEL_FLOOD ){
//				fg = 0xffbbbbbb;
//			}else if( level >= LogData.LEVEL_HEARTBEAT ){
//				fg = 0xff999999;
//			}else if( level >= LogData.LEVEL_DEBUG ){
//				fg = 0xff777777;
//			}else if( level >= LogData.LEVEL_VERBOSE ){
//				fg = 0xff555555;
//			}else if( level >= LogData.LEVEL_INFO ){
//				fg = 0xff000000;
//			}else if( level >= LogData.LEVEL_WARNING ){
//				fg = 0xffff8000;
//			}else{
//				fg = 0xffff0000;
//			}
//			holder.tvTime.setTextColor( fg );
//			holder.tvMessage.setTextColor( fg );
		}

		private String trimModelName( String sv ){
			// 制御文字は空白に置き換える
			StringBuilder sb = new StringBuilder( sv );
			for( int i = sb.length() - 1 ; i >= 0 ; -- i ){
				char c = sb.charAt( i );
				if( c < 0x20 || c == 0x7f ){
					sb.setCharAt( i, ' ' );
				}
			}
			// 連続する空白を１文字にする。始端と終端の空白を除去する。
			return sb.toString().replaceAll( "\\s+", " " ).trim();
		}

		private String joinList( String delimiter, LinkedList<String> exif_list ){
			if( exif_list == null || exif_list.isEmpty() ) return null;
			StringBuilder sb = new StringBuilder();
			for( String s : exif_list ){
				if( TextUtils.isEmpty( s ) ) continue;
				if( sb.length() > 0 ) sb.append( delimiter );
				sb.append( s );
			}
			return sb.toString();
		}

		void showTimeAndExif(){
			if( TextUtils.isEmpty( exif_info ) ){
				tvTime.setText( time_str );
			}else{
				tvTime.setText( time_str + "\n" + exif_info );
			}
		}

	}

	@Override public void onItemClick( AdapterView<?> parent, View view, int position, long id ){
		try{
			RecordAdapter adapter = (RecordAdapter) parent.getAdapter();
			final DownloadRecord data = adapter.loadAt( position );
			if( data == null ){
				( (ActMain) activity ).showToast( false, "missing record data at clicked position." );
				return;
			}
			String name = new File( data.air_path ).getName();
			openDetailDialog( data, name );

		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	private void openDetailDialog( final DownloadRecord data, String name ){
		View v = activity.getLayoutInflater().inflate( R.layout.dlg_download_record, null, false );

		TextView tvStateCode = ( (TextView) v.findViewById( R.id.tvStateCode ) );
		tvStateCode.setText( DownloadRecord.formatStateText( activity, data.state_code, data.state_message ) );
		tvStateCode.setTextColor( DownloadRecord.getStateCodeColor( data.state_code ) );

		( (TextView) v.findViewById( R.id.tvName ) ).setText( name );
		( (TextView) v.findViewById( R.id.tvTime ) ).setText(
			"update: " + date_fmt.format( data.time )
				+ "\nsize: " + Utils.formatBytes( data.size ) + "bytes"
				+ "\ndownload time: " + Utils.formatTimeDuration( data.lap_time )
				+ "\ndownload speed: " + Utils.formatBytes( (long) ( data.size * 1000L / (float) data.lap_time ) ) + "bytes/seconds"
		);
		( (TextView) v.findViewById( R.id.tvAirPath ) ).setText( "remote_path: " + data.air_path );
		( (TextView) v.findViewById( R.id.tvLocalFile ) ).setText( "local_file: " + data.local_file );

		final Dialog d = new Dialog( activity );
		d.setTitle( name );
		d.setContentView( v );
		//noinspection ConstantConditions
		d.getWindow().setLayout( WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT );
		d.show();
		v.findViewById( R.id.btnClose ).setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View view ){
				d.dismiss();
			}
		} );
		v.findViewById( R.id.btnView ).setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View view ){
				action_view( data );
			}
		} );
		v.findViewById( R.id.btnSend ).setOnClickListener( new View.OnClickListener(){
			@Override public void onClick( View view ){
				action_send( data );
			}
		} );
	}

	private Utils.FileInfo copyToLocal( DownloadRecord data ){
		try{

			if( data.local_file == null ){
				( (ActMain) activity ).showToast( false, "missing local file uri." );
				return null;
			}
			Utils.FileInfo tmp_info = new Utils.FileInfo( data.local_file );

			// 端末のダウンロードフォルダ
			File tmp_dir = Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_DOWNLOADS );
			if( tmp_dir == null ){
				( (ActMain) activity ).showToast( false, "can not find temporary directory." );
				return null;
			}

			// フォルダがなければ作成する
			if( ! tmp_dir.exists() ){
				if( ! tmp_dir.mkdir() ){
					( (ActMain) activity ).showToast( false, "temporary directory not exist." );
					return null;
				}
			}

			// ファイルをコピー
			String name = new File( data.air_path ).getName();
			File tmp_file = new File( tmp_dir, name );
			FileOutputStream os = new FileOutputStream( tmp_file );
			try{
				InputStream is;
				if( data.local_file.startsWith( "/" ) ){
					is = new FileInputStream( data.local_file );
				}else{
					is = activity.getContentResolver().openInputStream( Uri.parse( data.local_file ) );
				}
				if( is != null ){
					try{
						IOUtils.copy( is, os );
					}finally{
						try{
							is.close();
						}catch( Throwable ignored ){
						}
					}
				}
			}finally{
				try{
					os.close();
				}catch( Throwable ignored ){
				}
			}
			// 正常終了
			tmp_info.uri = Uri.fromFile( tmp_file );
			return tmp_info;
		}catch( Throwable ex ){
			ex.printStackTrace();
			( (ActMain) activity ).showToast( false, LogWriter.formatError( ex, "failed to copy to temporary folder." ) );
			return null;
		}

	}

	public static boolean isExternalStorageDocument( Uri uri ){
		return "com.android.externalstorage.documents".equals( uri.getAuthority() );
	}

	private Utils.FileInfo fixFileURL( DownloadRecord data ){
		try{
			if( data.local_file == null ){
				( (ActMain) activity ).showToast( false, "missing local file uri." );
				return null;
			}

			Utils.FileInfo tmp_info = new Utils.FileInfo( data.local_file );

			if( Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION ){

				if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ){
					if( DocumentsContract.isDocumentUri( activity, tmp_info.uri ) ){
						if( isExternalStorageDocument( tmp_info.uri ) ){
							final String docId = DocumentsContract.getDocumentId( tmp_info.uri );
							final String[] split = docId.split( ":" );
							if( split.length >= 2 ){
								final String uuid = split[ 0 ];
								if( "primary".equalsIgnoreCase( uuid ) ){
									tmp_info.uri = Uri.fromFile( new File( Environment.getExternalStorageDirectory() + "/" + split[ 1 ] ) );
									return tmp_info;
								}else{
									Map<String, String> volume_map = Utils.getSecondaryStorageVolumesMap( activity );
									String volume_path = volume_map.get( uuid );
									if( volume_path != null ){
										tmp_info.uri = Uri.fromFile( new File( volume_path + "/" + split[ 1 ] ) );
										return tmp_info;
									}
								}
							}
						}
					}
				}

				Cursor cursor = activity.getContentResolver().query( tmp_info.uri, null, null, null, null );
				if( cursor != null ){
					try{
						if( cursor.moveToFirst() ){
							int col_count = cursor.getColumnCount();
							for( int i = 0 ; i < col_count ; ++ i ){
								int type = cursor.getType( i );
								if( type != Cursor.FIELD_TYPE_STRING ) continue;
								String name = cursor.getColumnName( i );
								String value = cursor.isNull( i ) ? null : cursor.getString( i );
								Log.d( "DownloadRecordViewer", String.format( "%s %s", name, value ) );
								if( ! TextUtils.isEmpty( value ) ){
									if( "filePath".equals( name ) ){
										tmp_info.uri = Uri.fromFile( new File( value ) );
									}
								}
							}
						}
					}catch( Throwable ex ){
						ex.printStackTrace();
					}finally{
						cursor.close();
					}
				}
			}

			return tmp_info;
		}catch( Throwable ex ){
			ex.printStackTrace();
			( (ActMain) activity ).showToast( false, LogWriter.formatError( ex, "failed to fix file URI." ) );
			return null;
		}

	}

	void action_view( DownloadRecord data ){

		try{
			Utils.FileInfo tmp_info;
			if( Pref.pref( activity ).getBoolean( Pref.UI_COPY_BEFORE_VIEW_SEND, false ) ){
				tmp_info = copyToLocal( data );
			}else{
				tmp_info = fixFileURL( data );
			}
			if( tmp_info == null ) return;
			registMediaURI( tmp_info );

			Intent intent = new Intent( Intent.ACTION_VIEW );
			if( tmp_info.mime_type != null ){
				intent.setDataAndType( tmp_info.uri, tmp_info.mime_type );
			}else{
				intent.setData( tmp_info.uri );
			}
			activity.startActivity( intent );
		}catch( Throwable ex ){
			ex.printStackTrace();
			( (ActMain) activity ).showToast( true, LogWriter.formatError( ex, "view failed." ) );
		}
	}

	void action_send( DownloadRecord data ){
		try{
			Utils.FileInfo tmp_info;
			if( Pref.pref( activity ).getBoolean( Pref.UI_COPY_BEFORE_VIEW_SEND, false ) ){
				tmp_info = copyToLocal( data );
			}else{
				tmp_info = fixFileURL( data );
			}
			if( tmp_info == null ) return;
			registMediaURI( tmp_info );

			Intent intent = new Intent( Intent.ACTION_SEND );
			if( tmp_info.mime_type != null ){
				intent.setType( tmp_info.mime_type );
			}
			intent.putExtra( Intent.EXTRA_STREAM, tmp_info.uri );
			activity.startActivity( Intent.createChooser( intent, activity.getString( R.string.send ) ) );
		}catch( Throwable ex ){
			ex.printStackTrace();
			( (ActMain) activity ).showToast( true, LogWriter.formatError( ex, "send failed." ) );
		}
	}

	private void registMediaURI( Utils.FileInfo tmp_info ){
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ){
			if( ! "file".equals( tmp_info.uri.getScheme() ) ) return;
			String path = tmp_info.uri.getPath();
			Uri files_uri = MediaStore.Files.getContentUri( "external" );
			ContentResolver cr = activity.getContentResolver();
			Cursor cursor = cr.query(
				files_uri
				, null
				, MediaStore.Files.FileColumns.DATA + "=?"
				, new String[]{ path }
				, null
			);
			if( cursor != null ){
				try{
					if( cursor.moveToFirst() ){
						int colidx_id = cursor.getColumnIndex( BaseColumns._ID );
						long id = cursor.getLong( colidx_id );
						tmp_info.uri = Uri.parse( files_uri.toString() + "/" + id );
						return;
					}
				}finally{
					cursor.close();
				}
			}
			ContentValues cv = new ContentValues(  );
			String name = new File(path).getName();
			cv.put(MediaStore.Files.FileColumns.DATA,path);
			cv.put(MediaStore.Files.FileColumns.DISPLAY_NAME,name);
			cv.put(MediaStore.Files.FileColumns.TITLE,name);
			cv.put(MediaStore.Files.FileColumns.MIME_TYPE,tmp_info.mime_type);
			Uri new_uri = cr.insert(files_uri,cv);
			if(new_uri !=null ) tmp_info.uri = new_uri;
		}
	}

}
