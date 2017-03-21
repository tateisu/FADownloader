package jp.juggler.fadownloader;

import android.content.Intent;
import android.location.Location;
import android.os.Handler;

/*
	DownloadWorkerのスレッド終了を待ってから次のスレッドを作る
	作成要求が来てから作成するまではメインスレッド上で定期的に状態確認を行う
 */
public class WorkerTracker{

	final DownloadService service;
	final Handler handler;
	final LogWriter log;
	boolean tracker_disposed;
	boolean tracker_dispose_complete;

	DownloadWorker worker;
	boolean worker_disposed;

	// パラメータ指定付きでのスレッド作成フラグ
	boolean will_restart;
	Intent start_param;

	// 何かのイベントでのスレッド再生成フラグ
	boolean will_wakeup;
	String wakeup_cause;

	// サービス開始時に作られる
	public WorkerTracker( DownloadService service, LogWriter log ){
		this.service = service;
		this.log = log;
		this.handler = new Handler();
	}

	// サービス終了時に破棄される
	public void dispose(){
		tracker_disposed = true;
		proc_check.run();
	}

	void start( Intent intent ){
		if( tracker_disposed ) return;
		this.will_restart = true;
		this.start_param = intent;
		proc_check.run();
	}

	void wakeup( String cause ){
		if( tracker_disposed ) return;
		this.will_wakeup = true;
		this.wakeup_cause = cause;
		proc_check.run();
	}

	final Runnable proc_check = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( proc_check );

			if( tracker_disposed ){
				if( worker != null && ! worker_disposed ){
					worker.cancel( service.getString( R.string.service_end ) );
					handler.postDelayed( proc_check, 3000L );
					return;
				}else{
					tracker_dispose_complete = true;
					return;
				}
			}

			if( will_restart ){
				if( worker != null && ! worker_disposed ){
					worker.cancel( service.getString( R.string.manual_restart ) );
					handler.postDelayed( proc_check, 3000L );
					return;
				}

				Pref.pref( service ).edit()
					.remove( Pref.LAST_IDLE_START )
					.remove( Pref.FLASHAIR_UPDATE_STATUS_OLD )
					.apply();

				try{
					will_restart = false;
					worker_disposed = false;
					worker = new DownloadWorker( service, start_param, worker_callback );
					worker.start();
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( ex, "thread start failed." );
				}
			}

			if( will_wakeup ){
				if( worker != null ){
					if( ! worker.isCancelled() ){
						// キャンセルされていないなら通知して終わり
						will_wakeup = false;
						worker.notifyEx();
						return;
					}else if( ! worker_disposed ){
						// dispose 完了を待つ
						log.d("waiting dispose previous thread..");
						handler.postDelayed( proc_check, 3000L );
						return;
					}
				}

				try{
					will_wakeup = false;
					worker_disposed = false;
					worker = new DownloadWorker( service, wakeup_cause, worker_callback );
					worker.start();
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( ex, "thread start failed." );
				}
			}
		}
	};

	final DownloadWorker.Callback worker_callback = new DownloadWorker.Callback(){

		@Override public void onThreadEnd( final boolean complete_and_no_repeat ){
			handler.post( new Runnable(){
				@Override public void run(){
					worker_disposed = true;
					service.onThreadEnd( complete_and_no_repeat );
					proc_check.run();
				}
			} );
		}

		@Override public void onThreadStart(){
			service.onThreadStart();
		}

		@Override public void releaseWakeLock(){
			if( will_restart || will_wakeup ) return;
			service.releaseWakeLock();
		}

		@Override public void acquireWakeLock(){
			service.acquireWakeLock();
		}

		@Override public Location getLocation(){
			if( tracker_disposed ) return null;
			return service.location_tracker.getLocation();
		}

		@Override public void onAllFileCompleted( long count ){
			service.addHiddenDownloadCount( count );
		}

		@Override public boolean hasHiddenDownloadCount(){
			return service.hasHiddenDownloadCount( );
		}
	};
}
