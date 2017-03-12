package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.Dialog;
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
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.apache.commons.io.CopyUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import it.sephiroth.android.library.exif2.ExifInterface;

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

	class RecordAdapter extends CursorAdapter{

		final DownloadRecord.ColIdx colIdx = new DownloadRecord.ColIdx();
		final DownloadRecord data = new DownloadRecord();
		final LayoutInflater inflater = activity.getLayoutInflater();
		final int thumbnail_size;

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
		}

		@Override public View newView( Context context, Cursor cursor, ViewGroup viewGroup ){
			View root = inflater.inflate( R.layout.lv_download_record, viewGroup, false );
			root.setTag( new ViewHolder( root ) );
			return root;
		}

		@Override public void bindView( View view, Context context, Cursor cursor ){
			data.loadFrom( cursor, colIdx );

			ViewHolder holder = (ViewHolder) view.getTag();

			holder.bind( activity, view, data, thumbnail_size );

		}
	}

	static final SimpleDateFormat date_fmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault() );
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

		ViewHolder( View root ){

			tvName = (TextView) root.findViewById( R.id.tvName );
			tvTime = (TextView) root.findViewById( R.id.tvTime );
			tvStateCode = (TextView) root.findViewById( R.id.tvStateCode );
			ivThumbnail = (ImageView) root.findViewById( R.id.ivThumbnail );
		}

		public void bind( final Activity activity, View root, DownloadRecord data, final int thumbnail_size ){

			tvTime.setText( date_fmt.format( data.time )+" ("+ Utils.formatTimeDuration( data.lap_time )+")");
			tvName.setText( new File( data.air_path ).getName() );
			tvStateCode.setText( DownloadRecord.formatStateText(activity,data.state_code,data.state_message) );
			tvStateCode.setTextColor( DownloadRecord.getStateCodeColor( data.state_code ) );

			if( this.last_task == null
				&& this.last_bitmap != null
				&& this.last_image_uri.equals( data.local_file )
				){
				// 画像を再ロードする必要がない
			}else{
				ivThumbnail.setImageDrawable( default_thumbnail );

				if( last_task != null ){
					last_task.cancel( true );
					last_task = null;
				}

				if( last_bitmap != null ){
					last_bitmap.recycle();
					last_bitmap = null;
				}

				this.last_image_uri = data.local_file;

				if( ! TextUtils.isEmpty( last_image_uri )
					&& reJPEG.matcher( data.air_path ).find()
					){
					last_task = new AsyncTask<Void, Void, Bitmap>(){
						final String image_uri = last_image_uri;
						Integer orientation;

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
									try{
										if( isCancelled() ) return null;
										ExifInterface exif = new ExifInterface();
										exif.readExif( is, ExifInterface.Options.OPTION_ALL );
										orientation = exif.getTagIntValue( ExifInterface.TAG_ORIENTATION );
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
		View v = activity.getLayoutInflater().inflate( R.layout.download_record_detail_dialog, null, false );

		TextView tvStateCode = ( (TextView) v.findViewById( R.id.tvStateCode ) );
		tvStateCode.setText(  DownloadRecord.formatStateText(activity,data.state_code,data.state_message));
		tvStateCode.setTextColor( DownloadRecord.getStateCodeColor( data.state_code ) );

		( (TextView) v.findViewById( R.id.tvName ) ).setText( name );
		( (TextView) v.findViewById( R.id.tvTime ) ).setText( date_fmt.format( data.time )+" ("+ Utils.formatTimeDuration( data.lap_time )+")" );
		( (TextView) v.findViewById( R.id.tvAirPath ) ).setText( "air_path: " + data.air_path );
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
			String name = new File( data.air_path ).getName();

			if( data.local_file == null ){
				( (ActMain) activity ).showToast( false, "missing local file uri." );
				return null;
			}
			Utils.FileInfo tmp_info = new Utils.FileInfo( activity.getContentResolver(), data.local_file );

			// 適当なフォルダにコピーする
			File tmp_dir = Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_DOWNLOADS );
			if( tmp_dir == null ){
				( (ActMain) activity ).showToast( false, "can not find temporary directory." );
				return null;
			}
			if( ! tmp_dir.exists() ){
				if( ! tmp_dir.mkdir() ){
					( (ActMain) activity ).showToast( false, "temporary directory not exist." );
					return null;
				}
			}
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
			tmp_info.uri = Uri.fromFile( tmp_file );
			return tmp_info;
		}catch( Throwable ex ){
			ex.printStackTrace();
			( (ActMain) activity ).showToast( false, LogWriter.formatError( ex, "failed to copy to temporary folder." ) );
			return null;
		}

	}



	void action_view( DownloadRecord data ){
		Utils.FileInfo tmp_info = copyToLocal( data );
		if( tmp_info == null ) return;

		try{
			Intent intent = new Intent( Intent.ACTION_VIEW );
			if( tmp_info.mime_type != null ){
				intent.setDataAndType( tmp_info.uri, tmp_info.mime_type );
			}else{
				intent.setData( tmp_info.uri );
			}
			activity.startActivity( intent );
		}catch( Throwable ex ){
			ex.printStackTrace();
			((ActMain)activity).showToast(true,LogWriter.formatError( ex,"view failed." ));
		}
	}

	void action_send( DownloadRecord data ){
		try{
			Utils.FileInfo tmp_info = copyToLocal( data );
			if( tmp_info == null ) return;

			Intent intent = new Intent( Intent.ACTION_SEND );
			if( tmp_info.mime_type != null ){
				intent.setType( tmp_info.mime_type );
			}
			intent.putExtra( Intent.EXTRA_STREAM, tmp_info.uri );
			activity.startActivity( Intent.createChooser( intent, activity.getString( R.string.send ) ) );
		}catch( Throwable ex ){
			ex.printStackTrace();
			((ActMain)activity).showToast(true,LogWriter.formatError( ex,"send failed." ));
		}
	}
}
