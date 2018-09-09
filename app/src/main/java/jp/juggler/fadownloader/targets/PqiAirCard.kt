package jp.juggler.fadownloader.targets

import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import jp.juggler.fadownloader.*
import jp.juggler.fadownloader.model.ScanItem
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.util.LogWriter
import jp.juggler.fadownloader.util.Utils
import java.io.File
import java.util.*
import java.util.regex.Pattern

class PqiAirCard(
	private val service : DownloadService,
	internal val thread : DownloadWorker
) {
	
	companion object {
		
		internal val reDate = Pattern.compile("(\\w+)\\s+(\\d+)\\s+(\\d+):(\\d+):(\\d+)\\s+(\\d+)")
		
		private val month_array = arrayOf(
			"January".toLowerCase(),
			"February".toLowerCase(),
			"March".toLowerCase(),
			"April".toLowerCase(),
			"May".toLowerCase(),
			"June".toLowerCase(),
			"July".toLowerCase(),
			"August".toLowerCase(),
			"September".toLowerCase(),
			"October".toLowerCase(),
			"November".toLowerCase(),
			"December".toLowerCase()
		)
		
		// 月の省略形から月の数字(1-12)を返す
		internal fun parseMonth(targetArg : String) : Int {
			val target = targetArg.toLowerCase()
			var i = 0
			val ie = month_array.size
			while(i < ie) {
				if(month_array[i].startsWith(target)) return i + 1
				++ i
			}
			throw RuntimeException("invalid month name :$target")
		}
	}
	
	internal val log : LogWriter = service.log
	
	private val calendar = GregorianCalendar(TimeZone.getDefault())
	
	private fun loadFolder(network : Any?, item : ScanItem) {
		
		// フォルダを読む
		val cgi_url = thread.target_url + "cgi-bin/wifi_filelist?fn=/mnt/sd" + Uri.encode(
			item.remote_path,
			"/_-"
		) + if(item.remote_path.length > 1) "/" else ""
		val data = thread.client.getHTTP(log, network, cgi_url)
		if(thread.isCancelled) return
		
		if(data == null) {
			thread.checkHostError()
			log.e(
				R.string.folder_list_failed,
				item.remote_path,
				cgi_url,
				thread.client.last_error
			)
			thread.file_error = true
			return
		}
		
		//	Element root = Utils.parseXml( "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"+ Utils.decodeUTF8( data ) );
		val root = Utils.parseXml(data)
		if(root != null) {
			if("filelist" == root.tagName) {
				val child_list = root.childNodes
				if(child_list != null) {
					var i = 0
					val ie = child_list.length
					while(i < ie) {
						if(thread.isCancelled) break
						val node = child_list.item(i)
						if("file" != node.nodeName) {
							++ i
							continue
						}
						val attr = node.attributes
						if(attr == null) {
							++ i
							continue
						}
						val file_name = Utils.getAttribute(attr, "name")
						val size_str = Utils.getAttribute(attr, "size")
						val date_str = Utils.getAttribute(attr, "date")
						val type_str = Utils.getAttribute(attr, "type")
						if(file_name?.isEmpty() != false) {
							++ i
							continue
						}
						if(TextUtils.isEmpty(size_str)) {
							++ i
							continue
						}
						if(TextUtils.isEmpty(date_str)) {
							++ i
							continue
						}
						if(TextUtils.isEmpty(type_str)) {
							++ i
							continue
						}
						val size : Long
						try {
							size = java.lang.Long.parseLong(size_str, 10)
						} catch(ex : NumberFormatException) {
							ex.printStackTrace()
							log.e("incorrect size: %s", size_str)
							++ i
							continue
						}
						
						val time : Long
						try {
							val matcher = reDate.matcher(date_str)
							if(! matcher.find()) {
								log.e("incorrect date: %s", date_str)
								continue
							}
							val y = Integer.parseInt(matcher.group(6), 10)
							val m = parseMonth(matcher.group(1))
							val d = Integer.parseInt(matcher.group(2), 10)
							val h = Integer.parseInt(matcher.group(3), 10)
							val j = Integer.parseInt(matcher.group(4), 10)
							val s = Integer.parseInt(matcher.group(5), 10)
							//	log.f( "time=%s,%s,%s,%s,%s,%s", y, m, d, h, j, s );
							calendar.set(y, m - 1, d, h, j, s)
							calendar.set(Calendar.MILLISECOND, 500)
							time = calendar.timeInMillis
						} catch(ex : NumberFormatException) {
							ex.printStackTrace()
							++ i
							continue
						}
						
						val dir = if(item.remote_path == "/") "" else item.remote_path
						
						val remote_path = "$dir/$file_name"
						val local_file = LocalFile(item.local_file, file_name)
						
						if(type_str != "0") {
							// フォルダはキューの頭に追加
							thread.job_queue !!.addFolder(
								ScanItem(
									file_name,
									remote_path,
									local_file,
									mime_type = ScanItem.MIME_TYPE_FOLDER
								)
							)
						} else {
							// ファイル
							for(re in thread.file_type_list) {
								if(! re.matcher(file_name).find()) continue
								// マッチした
								
								// ローカルのファイルサイズを調べて既読スキップ
								if(thread.checkSkip(local_file, log, size)) continue
								
								val mime_type = Utils.getMimeType(log, file_name)
								
								// ファイルはキューの末尾に追加
								val sub_item = ScanItem(
									file_name,
									remote_path,
									local_file,
									size,
									time,
									mime_type
								)
								thread.job_queue !!.addFile(sub_item)
								thread.record(sub_item, 0L, DownloadRecord.STATE_QUEUED, "queued.")
								
								break
							}
						}
						++ i
						
					}
					
				}
				return
			}
		}
		log.e(R.string.folder_list_failed, item.remote_path, cgi_url, "(xml parse error)")
		thread.file_error = true
	}
	
	private fun loadFile(network : Any?, item : ScanItem) {
		val time_start = SystemClock.elapsedRealtime()
		val file_name = File(item.remote_path).name
		val remote_path = item.remote_path
		val local_file = item.local_file
		
		try {
			if(! local_file.prepareFile(log, true, item.mime_type)) {
				log.e("%s//%s :skip. can not prepare local file.", item.remote_path, file_name)
				thread.record(
					item,
					SystemClock.elapsedRealtime() - time_start,
					DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR,
					"can not prepare local file."
				)
				return
			}
			
			val get_url = thread.target_url + "/sd" + Uri.encode(remote_path, "/_-")
			val buf = ByteArray(2048)
			val data = thread.client.getHTTP(
				log,
				network,
				get_url
			) { log, cancel_checker, inStream, _ ->
				
				try {
					val os = local_file.openOutputStream(service)
					if(os == null) {
						log.e("cannot open local output file.")
					} else {
						try {
							while(true) {
								if(cancel_checker.isCancelled) {
									return@getHTTP null
								}
								val delta = inStream.read(buf)
								if(delta <= 0) break
								os.write(buf, 0, delta)
							}
							return@getHTTP buf
						} finally {
							try {
								os.close()
							} catch(ignored : Throwable) {
							}
							
						}
					}
				} catch(ex : Throwable) {
					log.e(ex,"HTTP read error.")
				}
				
				null
			}
			
			
			thread.afterDownload(time_start, data, item)
			
		} catch(ex : Throwable) {
			thread.afterDownload(time_start, ex, item)
			
		}
		
	}
	
	fun run() {
		
		while(! thread.isCancelled) {
			
			if(thread.job_queue == null && ! thread.callback.hasHiddenDownloadCount()) {
				// 指定時刻まで待機する
				while(! thread.isCancelled) {
					val now = System.currentTimeMillis()
					val last_file_listing = Pref.lastIdleStart(Pref.pref(service))
					val remain = last_file_listing + thread.intervalSeconds * 1000L - now
					if(remain <= 0) break
					
					if(thread.isTetheringType || remain < 15 * 1000L) {
						thread.setShortWait(remain)
					} else {
						thread.setAlarm(now, remain)
						break
					}
				}
				if(thread.isCancelled) break
				// 待機が終わった
			}
			
			thread.callback.acquireWakeLock()
			var network : Any? = null
			
			// 通信状態の確認
			thread.setStatus(false, service.getString(R.string.network_check))
			val network_check_start = SystemClock.elapsedRealtime()
			
			if(thread.target_type == Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER) {
				while(! thread.isCancelled) {
					val tracker_last_result = service.wifi_tracker.last_result.get()
					val air_url = service.wifi_tracker.last_flash_air_url.get()
					if(tracker_last_result && air_url != null) {
						thread.target_url = air_url
						break
					}
					
					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					val er_now = SystemClock.elapsedRealtime()
					if(er_now - network_check_start >= 60 * 1000L) {
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						thread.job_queue = null
						thread.cancel(service.getString(R.string.network_not_good))
						break
					}
					
					// 少し待って再確認
					thread.waitEx(10000L)
				}
			} else {
				
				while(! thread.isCancelled) {
					network = thread.wifiNetwork
					if(network != null) break
					
					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					val er_now = SystemClock.elapsedRealtime()
					if(er_now - network_check_start >= 60 * 1000L) {
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						thread.job_queue = null
						thread.cancel(service.getString(R.string.network_not_good))
						break
					}
					
					// 少し待って再確認
					thread.waitEx(10000L)
				}
			}
			if(thread.isCancelled) break
			
			// ファイルスキャンの開始
			if(thread.job_queue == null) {
				Pref.pref(service).edit().put(Pref.lastIdleStart, System.currentTimeMillis())
					.apply()
				
				// 未取得状態のファイルを履歴から消す
				@Suppress("ConstantConditionIf")
				if(DownloadWorker.RECORD_QUEUED_STATE) {
					service.contentResolver.delete(
						DownloadRecord.meta.content_uri,
						DownloadRecord.COL_STATE_CODE + "=?",
						arrayOf(Integer.toString(DownloadRecord.STATE_QUEUED))
					)
				}
				
				// フォルダスキャン開始
				thread.onFileScanStart()
				thread.job_queue !!.addFolder(
					ScanItem(
						"",
						"/",
						LocalFile(service, thread.folder_uri.toString()),
						mime_type = ScanItem.MIME_TYPE_FOLDER
					)
				)
				
			}
			
			try {
				if(! thread.job_queue !!.queue_folder.isEmpty()) {
					val head = thread.job_queue !!.queue_folder.first
					thread.setStatus(
						false,
						service.getString(R.string.progress_folder, head.remote_path)
					)
					thread.job_queue !!.queue_folder.removeFirst()
					loadFolder(network, head)
				} else if(! thread.job_queue !!.queue_file.isEmpty()) {
					// キューから除去するまえに残りサイズを計算したい
					val head = thread.job_queue !!.queue_file.first
					thread.setStatus(
						true,
						service.getString(R.string.download_file, head.remote_path)
					)
					thread.job_queue !!.queue_file.removeFirst()
					loadFile(network, head)
				} else {
					// ファイルスキャンの終了
					val file_count = thread.job_queue !!.file_count
					thread.job_queue = null
					thread.setStatus(false, service.getString(R.string.file_scan_completed))
					if(! thread.file_error) {
						thread.onFileScanComplete(file_count)
					}
				}
			} catch(ex : Throwable) {
				ex.printStackTrace()
				log.e(ex, "error.")
			}
			
		}
	}
	
}
