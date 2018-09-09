package jp.juggler.fadownloader.model

import jp.juggler.fadownloader.LocalFile
import java.util.LinkedList

class ScanItem(
	val name : String,
	val remote_path : String,
	val local_file : LocalFile,
	val size : Long = 0L,
	val time : Long = 0L,
	val mime_type : String
) {
	companion object {
		const val MIME_TYPE_FOLDER =  "inode/directory"
	}
	
	val is_file : Boolean = true
	
	class Queue {
		val queue_file = LinkedList<ScanItem>()
		val queue_folder = LinkedList<ScanItem>()
		var file_count : Long = 0
		
		private var folder_count : Long = 0
		private var byte_count : Long = 0
		
		fun addFolder(item : ScanItem) {
			++ folder_count
			queue_folder.add(item)
		}
		
		fun addFile(item : ScanItem) {
			++ file_count
			byte_count += item.size
			queue_file.add(item)
		}
	}
}
