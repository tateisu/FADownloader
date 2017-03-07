package jp.juggler.fadownloader;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.support.v4.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

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
	private Object findChild( LogWriter log, String target_name ){
		if( prepareFileList( log ) ){
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

	private boolean prepareFileList( LogWriter log ){
		if( child_list == null ){
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log ) ){
					local_file = parent.findChild( log, name );
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

	private boolean prepareDirectory( LogWriter log ){
		try{
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log ) ){
					local_file = parent.findChild( log, name );
					if( local_file == null ){
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
	public boolean prepareFile( LogWriter log ){
		try{
			if( local_file == null && parent != null ){
				if( parent.prepareDirectory( log ) ){
					local_file = parent.findChild( log, name );
					if( local_file == null ){
						if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
							local_file = ( (DocumentFile) parent.local_file ).createFile( "application/octet-stream", name );
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

	public long length(){
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			return ( (DocumentFile) local_file ).length();
		}else{
			return ( (File) local_file ).length();
		}
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

}
