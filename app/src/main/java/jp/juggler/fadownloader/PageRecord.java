package jp.juggler.fadownloader;

import android.app.Activity;
import android.view.View;
import android.widget.ListView;

public class PageRecord extends PagerAdapterBase.PageViewHolder{

	ListView listView;
	DownloadRecordViewer viewer;


	public PageRecord( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{
		listView = (ListView) root.findViewById( R.id.lvRecord );
		viewer = new DownloadRecordViewer();

		if( ( (ActMain) activity ).is_start ){
			onStart();
		}
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
		onStop();
	}

	void onStart(){
		viewer.onStart( (ActMain) activity, listView,ActMain.LOADER_ID_RECORD );
	}

	void onStop(){
		viewer.onStop();
	}
}
