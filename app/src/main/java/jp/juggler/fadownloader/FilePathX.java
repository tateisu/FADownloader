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

public class FilePathX{

	static final int DOCUMENT_FILE_VERSION = 21;

	private static Object bsearch( ArrayList<Object> local_files, String target_name ){
		int start = 0;
		int end = local_files.size();
		while( ( end - start ) > 0 ){
			int mid = ( ( start + end ) >> 1 );
			Object x = local_files.get( mid );
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
		return null;
	}



	FilePathX parent;
	String name;
	Object local_file;
	ArrayList<Object> file_list;

	public FilePathX( Context context, String folder_uri ){
		if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
			local_file = DocumentFile.fromTreeUri( context, Uri.parse( folder_uri ) );
		}else{
			local_file = new File( folder_uri );
		}
	}

	public FilePathX( FilePathX parent, String name ){
		this.parent = parent;
		this.name = name;
	}

	public ArrayList<Object> getFileList(){
		if( local_file != null ){
			if( file_list != null ) return file_list;
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
			return file_list = result;
		}else if( parent != null ){
			ArrayList<Object> parent_childs = parent.getFileList();
			if( parent_childs != null ){
				Object file = bsearch( parent_childs, name );
				if( file != null ){
					this.local_file = file;
					return getFileList();
				}
			}
		}
		return null;
	}

	private Object prepareDirectory( LogWriter log ){
		try{
			if( local_file != null ) return local_file;
			if( parent != null ){
				Object parent_dir = parent.prepareDirectory( log );
				if( parent_dir == null ) return null;

				ArrayList<Object> parent_list = parent.getFileList();
				Object file = bsearch( parent_list, name );
				if( file == null ){
					log.i( R.string.folder_create, name );
					if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
						file = ( (DocumentFile) parent_dir ).createDirectory( name );
					}else{
						file = new File( (File) parent_dir, name );
						if( ! ( (File) file ).mkdir() ){
							throw new RuntimeException( "mkdir failed." );
						}
					}
				}
				return this.local_file = file;
			}
		}catch( Throwable ex ){
			log.e( R.string.folder_create_failed, ex.getClass().getSimpleName(), ex.getMessage() );
		}
		return null;
	}

	public boolean prepareFile( LogWriter log ){
		try{
			if( local_file != null ) return true;
			if( parent != null ){
				Object parent_dir = parent.prepareDirectory( log );
				if( parent_dir == null ) return false;

				Object file = bsearch( parent.getFileList(), name );
				if( file == null ){
					if( Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ){
						file = ( (DocumentFile) parent_dir ).createFile( "application/octet-stream", name );
					}else{
						file = new File( (File) parent_dir, name );
					}
				}
				this.local_file = file;
				return true;
			}
		}catch( Throwable ex ){
			log.e( R.string.file_create_failed, ex.getClass().getSimpleName(), ex.getMessage() );
		}
		return false;
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
