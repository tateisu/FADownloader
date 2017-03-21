package jp.juggler.fadownloader;

import java.util.LinkedList;

public class ScanItem{

	public final String name;
	public final String remote_path;
	public final LocalFile local_file;
	public final boolean is_file;
	public final long size;
	public final long time;
	public final String mime_type;

	public ScanItem( String name, String remote_path, LocalFile local_file ){
		this.name = name;
		this.remote_path = remote_path;
		this.local_file = local_file;
		this.is_file = false;
		this.size = 0L;
		this.time = 0L;
		this.mime_type = null;
	}

	public ScanItem( String name, String remote_path, LocalFile local_file, long size, long time, String mime_type ){
		this.name = name;
		this.remote_path = remote_path;
		this.local_file = local_file;
		this.is_file = true;
		this.size = size;
		this.time = time;
		this.mime_type = mime_type;
	}

	public static class Queue {
		public final LinkedList<ScanItem> queue_file = new LinkedList<>(  );
		public final LinkedList<ScanItem> queue_folder = new LinkedList<>(  );

		long folder_count;
		public long file_count;
		long byte_count;

		public void addFolder( ScanItem item ){
			++folder_count;
			queue_folder.add(item);
		}

		public void addFile( ScanItem item ){
			++file_count;
			byte_count += item.size;
			queue_file.add( item );
		}
	}
}
