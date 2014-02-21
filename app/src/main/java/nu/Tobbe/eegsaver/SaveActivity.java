package nu.Tobbe.eegsaver;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.dropbox.sync.android.DbxAccountManager;
import com.dropbox.sync.android.DbxException;
import com.dropbox.sync.android.DbxFile;
import com.dropbox.sync.android.DbxFileStatus;
import com.dropbox.sync.android.DbxFileSystem;
import com.dropbox.sync.android.DbxPath;

public class SaveActivity extends Activity {
	private ScheduledExecutorService scheduleTaskExecutor;
	private static final String appKey = "r7tjcg803htbhfu";
    private static final String appSecret = "ir70ago48nyrxu0";

    private static final int REQUEST_LINK_TO_DBX = 0;

    private TextView mLog;
    private Button mLinkButton;
    private ProgressBar pb;
    private DbxAccountManager mDbxAcctMgr;
    private DbxPath path;
    private DbxFileSystem fs;
    private String filenameout;
    private String filenamein;
    private static enum DBX { EMPTY, INSTANCE, LINKED, FIRSTSYNC, PATHOK, ULSTART, COMPLETE, COMPLETE2 }
    private DBX dbxstate = DBX.EMPTY;
    private final Object dbxlock = new Object();
    Handler uihandler;
    private int pct;
    private String dbxerror;
    private String uiout;
    DbxFile outFile;
         
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_save_dropbox);
        mLog = (TextView) findViewById(R.id.test_output);
        mLinkButton = (Button) findViewById(R.id.link_button);
        pb = (ProgressBar) findViewById(R.id.progressBar);

        mLog.setVisibility(View.VISIBLE);
        mLinkButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickLinkToDropbox();
            }
        });

        Intent intent = getIntent();
        filenameout = intent.getStringExtra(MainActivity.MESSAGE_FILEOUT);
        filenamein = intent.getStringExtra(MainActivity.MESSAGE_FILEIN);

        mDbxAcctMgr = DbxAccountManager.getInstance(getApplicationContext(), appKey, appSecret);
        dbxstate = DBX.INSTANCE;
    }

	@Override
	protected void onStart() {
		super.onStart();
		if (mDbxAcctMgr.hasLinkedAccount()) {
		    showLinkedView();
		    dbxstate = DBX.LINKED;
		} else {
			showUnlinkedView();
		}
		uihandler = new Handler();
		scheduleTaskExecutor= Executors.newScheduledThreadPool(5);
	    scheduleTaskExecutor.scheduleWithFixedDelay(saverDbx, 0, 100, TimeUnit.MILLISECONDS);
	}

    private void showLinkedView() {
        mLinkButton.setVisibility(View.GONE);
        mLog.setVisibility(View.VISIBLE);
    }

    private void showUnlinkedView() {
        mLinkButton.setVisibility(View.VISIBLE);
        mLog.setVisibility(View.GONE);
    }

    private void onClickLinkToDropbox() {
        mDbxAcctMgr.startLink(this, REQUEST_LINK_TO_DBX);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_LINK_TO_DBX) {
            if (resultCode == Activity.RESULT_OK) {
            	dbxstate = DBX.LINKED;
            	mLog.append("Now linked to Dropbox.");
            } else {
                mLog.append("Link to Dropbox failed or was cancelled.");
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void setupDropbox() {
        try {
        	Log.d("saverDbx","Dropbox Sync API Version "+DbxAccountManager.SDK_VERSION_NAME);
        	path = new DbxPath(DbxPath.ROOT, filenameout);

        	// Create DbxFileSystem for synchronized file access.
        	fs = DbxFileSystem.forAccount(mDbxAcctMgr.getLinkedAccount());
        	// This will block until we can
            // sync metadata the first time.
//      	fs.awaitFirstSync();
        	dbxstate = DBX.PATHOK;
        } catch (IOException e) {
        	showStackTrace(e);
        }
    }
    public void saveToDropbox() {
        try {
        	outFile = fs.create(path);
        	File fin = new File(filenamein);
    		outFile.writeFromExistingFile(fin, false); // second arg is shouldSteal
    		dbxstate = DBX.ULSTART;
        } catch (IOException e) {
        	showStackTrace(e);
        }
    }
    private void showStackTrace(Throwable e) {
    	dbxerror = Log.getStackTraceString(e);
    	runOnUiThread(new Runnable() { public void run() { mLog.append(dbxerror); }});
    }
    private final Thread saverDbx = new Thread(new Runnable() {
    	public void run() {
//    		Log.d("saverDbx","state: "+dbxstate.toString());
    		synchronized(dbxlock) {
    			switch(dbxstate) {
    			case LINKED:
    				showLinkedView();
    				setupDropbox();
    				break;

    			case PATHOK:
    				try {
    					if(fs.hasSynced()) {
    						dbxstate = DBX.FIRSTSYNC;
    					}
    				} catch (DbxException e) {
    		        	showStackTrace(e);
    				}
    				break;
    			case FIRSTSYNC:
    				Log.v("saverDbx","entering saveToDropbox\n");
    				saveToDropbox();
    				Log.v("saverDbx","back from saveToDropbox\n");
    				break;

    			case ULSTART:
    				try {
    					DbxFileStatus stat = outFile.getSyncStatus();
//    					Log.d("saverDbx","sync status: "+stat.pending+"\n");
    					switch (stat.pending) { 
    					case UPLOAD:
    					case DOWNLOAD:
    						long btot = stat.bytesTotal <= 0? 1 : stat.bytesTotal;
    						long btr = stat.bytesTransferred < 0?0:stat.bytesTransferred;
    						pct = (int)(100.0 * (float)btr / (float)btot);
//    						Log.d("saverDbx","bytes transferred: "+btr);
//    						Log.d("saverDbx","pct: "+pct+"\n");
//    						Log.v("saverDbx","running on ui thread\n");
    						String state = stat.pending == DbxFileStatus.PendingOperation.DOWNLOAD ? "Downloading" : "Uploading";
    						uiout = state+": "+btr +"/"+ stat.bytesTotal+" = "+pct+"%\n";
    						runOnUiThread(new Runnable() { public void run() {
    							mLog.setText(uiout);
    							pb.setProgress(pct); 
    						} });
//    						Log.v("saverDbx","back from running on ui thread\n");

    						if (btr >= stat.bytesTotal) {
    							dbxstate = DBX.COMPLETE;
    						}
    						break;
    					case NONE:
    						runOnUiThread(new Runnable() { public void run() {pb.setProgress(100);}});
    						dbxstate = DBX.COMPLETE;
    						break;
    					}
    					if (stat.failure != null) {
    						Log.w("saverDbx","Failure: "+stat.failure.toString());
    						dbxerror = stat.failure.toString();
    						runOnUiThread(new Runnable() { public void run() { mLog.append(dbxerror); }});
    					}
    				} catch (DbxException e) {
    		        	showStackTrace(e);
    				}
    				break;
    			case COMPLETE:
    				runOnUiThread(new Runnable() { public void run() {pb.setProgress(100);}});
    				Log.d("saverDbx","In sync!");
    				outFile.close();
    				dbxstate = DBX.COMPLETE2;
    				scheduleTaskExecutor.shutdown();
    				finish();
    				break;	
    			default:
    				Log.d("saverDbx","Unknown state!");
    			}
    		}
		}
	},"dbxsaver");
}
