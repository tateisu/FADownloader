package jp.juggler.fadownloader;

public class QueueItem{

	public final String remote_path;
	public final LocalFile local_file;
	public final boolean is_file;
	public final long size;

	public QueueItem( String remote_path, LocalFile local_file, boolean is_file, long size ){
		this.remote_path = remote_path;
		this.local_file = local_file;
		this.is_file = is_file;
		this.size = size;
	}
}
