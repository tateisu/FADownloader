package jp.juggler.fadownloader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.support.annotation.NonNull;
import android.support.v4.provider.DocumentFile;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/*

 端末上にあるかもしれないファイルの抽象化

 機能1
 OSバージョンによってFileとDocumentFileを使い分ける

 機能2
 転送対象ファイルが存在しないフォルダを作成したくない
 prepareFile()した時点で親フォルダまで遡って作成したい
 しかし DocumentFile だと作成する前のフォルダを表現できない
 親フォルダがまだ作成されてなくても「親フォルダ＋名前」の形式でファイルパスを保持する

*/

public class LocalFile{

	public static final int DOCUMENT_FILE_VERSION = 21;

	private Object local_file;

	public LocalFile( Context context, String folder_uri ){
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			local_file = DocumentFile.fromTreeUri( context, Uri.parse( folder_uri ) );
		}else{
			local_file = new File( folder_uri );
		}
	}

	private LocalFile parent;
	private String name;

	public LocalFile( LocalFile parent, String name ){
		this.parent = parent;
		this.name = name;
	}

	public LocalFile getParent(){
		return parent;
	}

	public String getName(){
		return name;
	}

	// local_fileで表現されたフォルダ中に含まれるエントリの一覧
	// 適当にキャッシュする
	private ArrayList<Object> child_list;

	// エントリを探索
	private Object findChild( LogWriter log, boolean bCreate, String target_name ){
		if( prepareFileList( log, bCreate ) ){
			int start = 0;
			int end = child_list.size();
			while( ( end - start ) > 0 ){
				int mid = ( ( start + end ) >> 1 );
				Object x = child_list.get( mid );
				int i;
				if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
					i = target_name.compareTo( ( (DocumentFile) x ).getName() );
				}else{
					i = target_name.compareTo( ( (File) x ).getName() );
				}
				if( i < 0 ){
					end = mid;
				}else if( i > 0 ){
					start = mid + 1;
				}else{
					return x;
				}
			}
		}
		return null;
	}

	private boolean prepareFileList( LogWriter log, boolean bCreate ){
		if( child_list == null ){
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log, bCreate ) ){
					local_file = parent.findChild( log, bCreate, name );
				}
			}
			if( local_file != null ){
				try{
					ArrayList<Object> result = new ArrayList<>();
					if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
						Collections.addAll( result, ( (DocumentFile) local_file ).listFiles() );
						Collections.sort( result, new Comparator<Object>(){
							@Override public int compare( Object a, Object b ){
								return ( (DocumentFile) a ).getName().compareTo( ( (DocumentFile) b ).getName() );
							}
						} );
					}else{
						Collections.addAll( result, ( (File) local_file ).listFiles() );
						Collections.sort( result, new Comparator<Object>(){
							@Override public int compare( Object a, Object b ){
								return ( (File) a ).getName().compareTo( ( (File) b ).getName() );
							}
						} );
					}
					child_list = result;
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( ex, "listFiles() failed." );
				}
			}
		}
		return child_list != null;
	}

	private boolean prepareDirectory( LogWriter log, boolean bCreate ){
		try{
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log, bCreate ) ){
					local_file = parent.findChild( log, bCreate, name );
					if( local_file == null && bCreate ){
						log.i( R.string.folder_create, name );
						if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
							local_file = ( (DocumentFile) parent.local_file ).createDirectory( name );
						}else{
							local_file = new File( (File) parent.local_file, name );
							if( ! ( (File) local_file ).mkdir() ){
								local_file = null;
							}
						}
						if( local_file == null ){
							log.e( R.string.folder_create_failed );
						}
					}
				}
			}
		}catch( Throwable ex ){
			log.e( ex, R.string.folder_create_failed );
		}
		return local_file != null;
	}

	@SuppressWarnings( "BooleanMethodIsAlwaysInverted" )
	public boolean prepareFile( LogWriter log, boolean bCreate ,String mime_type ){
		try{
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log, bCreate ) ){
					local_file = parent.findChild( log, bCreate, name );
					if( local_file == null && bCreate ){
						if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
							if( TextUtils.isEmpty( mime_type ) ) mime_type =  "application/octet-stream";
							local_file = ( (DocumentFile) parent.local_file ).createFile(mime_type , name );
						}else{
							local_file = new File( (File) parent.local_file, name );
						}
						if( local_file == null ){
							log.e( R.string.file_create_failed );
						}
					}
				}
			}

		}catch( Throwable ex ){
			log.e( ex, R.string.file_create_failed );
		}
		return local_file != null;
	}

	public long length( LogWriter log ){
		if( prepareFile( log, false,null ) ){
			if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
				return ( (DocumentFile) local_file ).length();
			}else{
				return ( (File) local_file ).length();
			}
		}
		return 0L;
	}

	public OutputStream openOutputStream( Context context ) throws FileNotFoundException{
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			Uri file_uri = ( (DocumentFile) local_file ).getUri();
			return context.getContentResolver().openOutputStream( file_uri );
		}else{
			return new FileOutputStream( ( (File) local_file ) );
		}
	}

	public InputStream openInputStream( Context context ) throws FileNotFoundException{
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			Uri file_uri = ( (DocumentFile) local_file ).getUri();
			return context.getContentResolver().openInputStream( file_uri );
		}else{
			return new FileInputStream( ( (File) local_file ) );
		}
	}

	public boolean delete(){
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			return ( (DocumentFile) local_file ).delete();
		}else{
			return ( (File) local_file ).delete();
		}
	}

	public boolean renameTo( String name ){
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			return ( (DocumentFile) local_file ).renameTo( name );
		}else{
			return ( (File) local_file ).renameTo(
				new File( ( (File) local_file ).getParentFile(), name )
			);
		}
	}

	public String getFileUri( LogWriter log ){
		if( ! prepareFile( log,false,null ) ) return null;
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			return ( (DocumentFile) local_file ).getUri().toString();
		}else{
			return ( (File) local_file ).getAbsolutePath();
		}
	}

	public static boolean isExternalStorageDocument( Uri uri ){
		return "com.android.externalstorage.documents".equals( uri.getAuthority() );
	}

	private File fixFilePath( Context context, LogWriter log, @NonNull DocumentFile df ){
		try{
			if( Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION ){
				Uri uri = df.getUri();

				if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ){
					if( DocumentsContract.isDocumentUri( context, uri ) ){
						if( isExternalStorageDocument( uri ) ){
							final String docId = DocumentsContract.getDocumentId( uri );
							final String[] split = docId.split( ":" );
							if( split.length >= 2 ){
								final String uuid = split[ 0 ];
								if( "primary".equalsIgnoreCase( uuid ) ){
									return new File( Environment.getExternalStorageDirectory() + "/" + split[ 1 ] );
								}else{
									Map<String, String> volume_map = Utils.getSecondaryStorageVolumesMap( context );
									String volume_path = volume_map.get( uuid );
									if( volume_path != null ){
										return new File( volume_path + "/" + split[ 1 ] );
									}
								}
							}
						}
					}
				}

				Cursor cursor = context.getContentResolver().query( uri, null, null, null, null );
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
										return new File( value );
									}
								}
							}
						}
					}finally{
						cursor.close();
					}
				}
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "failed to fix file URI." );
		}
		return null;

	}

	public void setFileTime( Context context, LogWriter log, long time ){
		try{
			if( ! prepareFile( log, false ,null) ) return;
			File path;
			if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
				DocumentFile df = (DocumentFile) local_file;
				if( df == null || ! df.isFile() ) return;
				path = fixFilePath( context, log, df );
			}else{
				path = (File) local_file;
				if( path == null ) return;
			}
			if( path == null || ! path.isFile() ) return;
			//noinspection ResultOfMethodCallIgnored
			path.setLastModified( time );
		}catch( Throwable ex ){
			log.e( "setLastModified() failed." );
		}
	}

}
