package jp.juggler.fadownloader;

import android.app.Activity;
import android.view.View;
import android.widget.ListView;

public class PageLog extends PagerAdapterBase.PageViewHolder{

	ListView lvLog;
	LogViewer log_viewer;


	public PageLog( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{
		lvLog = (ListView) root.findViewById( R.id.lvLog );
		log_viewer = new LogViewer();

		if( ( (ActMain) activity ).is_start ){
			onStart();
		}
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
		onStop();
	}

	void onStart(){
		log_viewer.onStart( (ActMain) activity, lvLog, ActMain.LOADER_ID_LOG );
	}

	void onStop(){
		log_viewer.onStop();
	}

}
