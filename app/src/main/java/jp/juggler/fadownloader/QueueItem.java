package jp.juggler.fadownloader;

public class QueueItem{

	public final String name;
	public final String remote_path;
	public final LocalFile local_file;
	public final boolean is_file;
	public final long size;
	public final long time;
	public final String mime_type;

	public QueueItem( String name, String remote_path, LocalFile local_file ){
		this.name = name;
		this.remote_path = remote_path;
		this.local_file = local_file;
		this.is_file = false;
		this.size = 0L;
		this.time = 0L;
		this.mime_type = null;
	}

	public QueueItem( String name, String remote_path, LocalFile local_file, long size, long time, String mime_type ){
		this.name = name;
		this.remote_path = remote_path;
		this.local_file = local_file;
		this.is_file = true;
		this.size = size;
		this.time = time;
		this.mime_type = mime_type;
	}
}
