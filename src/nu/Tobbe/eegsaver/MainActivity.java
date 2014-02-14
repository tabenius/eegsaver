package nu.Tobbe.eegsaver;

/*
 * FIXME: Select data source in chart, raw or alpha etc
 * 
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Time;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GraphView.GraphViewData;
import com.jjoe64.graphview.GraphViewDataInterface;
import com.jjoe64.graphview.GraphViewSeries;
import com.jjoe64.graphview.LineGraphView;
import com.neurosky.thinkgear.TGDevice;
import com.neurosky.thinkgear.TGEegPower;
import com.neurosky.thinkgear.TGRawMulti;

@SuppressLint("UseValueOf")
public class MainActivity extends Activity {
	private ScheduledExecutorService scheduleTaskExecutor;
    private static final int REQUEST_ENABLE_BT = 0;
	public static final String MESSAGE_FILEOUT = "nu.Tobbe.eegsaver.FILENAMEOUT";
	public static final String MESSAGE_FILEIN = "nu.Tobbe.eeegsaver.FILENAMEIN";

	BluetoothAdapter bluetoothAdapter;
	static TGDevice tgDevice;
	
	final boolean rawEnabled = false;
	boolean simulating = false;
	static boolean recording = false;
	static boolean verbose = false;
	private String filenamein;
	private int binpointer = 0;
	private int cuenr = 0;
    private static BufferedWriter out;
	private static ArrayList<Triple> measures;
	private static Object measures_lock;
	
	private static TextView log;
	private static TextView view_data;
	private static ToggleButton rectog;
	private TabHost mTabHost;
	private GraphView graphView;
	private GraphViewSeries graphViewSeries;
	private int chartmode = 0;
	
	public MainActivity() {
		super();
		measures = new ArrayList<Triple>();
		measures_lock = new Object();
	}
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		log = (TextView)findViewById(R.id.view_log);
		log.setText("");
		Time today = new Time(Time.getCurrentTimezone());
		today.setToNow();
		log.append(today.format2445()+"\n");
		log.append("Android version: "+android.os.Build.VERSION.RELEASE+" "+android.os.Build.VERSION.CODENAME+", SDK level: "+android.os.Build.VERSION.SDK_INT+"\n");

    	rectog = (ToggleButton)findViewById(R.id.toggle_record);

		view_data = (TextView)findViewById(R.id.view_data);
		onClickConnect(null);
		
		((EditText)findViewById(R.id.edit_filename)).addTextChangedListener(onTextChanged);
		
		mTabHost = (TabHost) findViewById(android.R.id.tabhost);
		mTabHost.setup();

		TabSpec tab_log = mTabHost.newTabSpec("Log");
		tab_log.setIndicator("Log");
		tab_log.setContent(R.id.tab_log);
		TabSpec tab_chart = mTabHost.newTabSpec("Chart");
		tab_chart.setIndicator("Chart");
		tab_chart.setContent(R.id.tab_chart);
		TabSpec tab_save = mTabHost.newTabSpec("Save");
		tab_save.setIndicator("Save");
		tab_save.setContent(R.id.tab_save);

		mTabHost.addTab(tab_log);
		mTabHost.addTab(tab_chart);
		mTabHost.addTab(tab_save);
		
	    initWriter();
		scheduleTaskExecutor= Executors.newScheduledThreadPool(5);
	    scheduleTaskExecutor.scheduleAtFixedRate(saver, 0, 1, TimeUnit.SECONDS);
	    scheduleTaskExecutor.scheduleAtFixedRate(simulator, 0, 1, TimeUnit.SECONDS);
	    scheduleTaskExecutor.scheduleAtFixedRate(guiupdater, 0, 100, TimeUnit.MILLISECONDS);
	}
	@Override 
	protected void onStart() {
		super.onStart();
	    log.append("onStart!\n");
	}
	@Override
	protected void onResume() {
	    super.onResume();
	    log.append("onResume!\n");
	}
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	@TargetApi(19)
	public void initWriter() {
		log.append("FIXME: if out.csv is found ask user if he want's to save it as it is probably a crash;\n");
		binpointer = 0;
		try {
			String filename = "eegsaverout.csv";
			FileWriter fileWriter;

			if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
				if (android.os.Build.VERSION.SDK_INT >= 19) {
					filenamein = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath()+File.separator+filename;
				} else {
					filenamein = Environment.getExternalStorageDirectory()+File.separator+filename;
				}
			} else {
				filenamein = this.getFilesDir()+File.separator+filename;
			}
			fileWriter= new FileWriter(filenamein);
			out = new BufferedWriter(fileWriter);
			out.write("time,type,value\n");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	public void onClickUpdateChart(View view) {
		GraphViewData[] data;
		Triple[] data1;
		GraphViewData[] data2;
		Triple first;
		int n=0;
		synchronized(measures_lock) {
			int N = measures.size();
			if (N==0) return;
			first = measures.get(0);
			for (int i=0; i < N; ++i) {
				if (measures.get(i).type == Triple.Type.RAW) { n ++; }
			}
			data1 = new Triple[n];
			int j=0;
			for (int i=0; i < N; ++i) {
				Triple t = measures.get(i);
				if (t.type == Triple.Type.RAW) {
					data1[j++] = t; 
				}
			}
		}
		switch (chartmode) {
		case 1: // Create periodogram
			if(n < 512) { return; }
			FFT transform = new FFT(512);
			double x[] = new double[512];
			double y[] = new double[512];
			int j = n - 512;
			for(int i=0; i < 512; ++i) { x[i] = data1[j++].getY(); }
			transform.fft(x, y);
			// gamma frequencies are 32Hz +, higher than this will probably not be needed
			data2 = new GraphViewData[64]; 
			double scale = 512 * 512; // no samples * frequency of data
			for (int i=0; i < data2.length; ++i) { 
				data2[i] = new GraphViewData(i, (x[i]*x[i] + y[i]*y[i])/scale); // |v|, v in C
			}
			data = data2;
			break;
		default: 
			data = data1;
		}
	
		if (graphView != null) {
			graphViewSeries.resetData(data);
		} else {
			LinearLayout layout = (LinearLayout) findViewById(R.id.tab_chart);
			graphView = new LineGraphView(this, "EEG Raw Data");
			graphViewSeries = new GraphViewSeries(data);
			graphView.addSeries(graphViewSeries);
			graphView.setScalable(true);
			graphView.setScrollable(true);
			layout.addView(graphView);
		}
	}
	public void onRadioButtonClicked(View view) {
	    boolean checked = ((RadioButton) view).isChecked();	    
	    switch(view.getId()) {
	        case R.id.radio_freqspace:
	        	if (checked)
	        		chartmode = 1;
	        	break;
	        case R.id.radio_timespace:
	        	if (checked)
	        		chartmode = 0;
	            break;
	    }
	    onClickUpdateChart(null);
	}
	public void onClickConnect(View view) {
		bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (bluetoothAdapter == null) {
        	Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show();
		} else {
			if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        	tgDevice = new TGDevice(bluetoothAdapter, eeghandler);
        	tgDevice.connect(true);
		}
	}
	public void onClickDisconnect(View view) {
		bluetoothAdapter = null;
		if (tgDevice != null) {
			tgDevice.close();
		}
		tgDevice = null;
	}
	public void onClickClear(View view) {
		log.setText("");
		synchronized(measures_lock) {
			measures.clear();
			binpointer = 0;
		}
		if (simulating) { onClickSimulate(null); }
	}
	public void onClickRecord(View view) {
		recording = recording?false:true;
		ToggleButton tog = (ToggleButton)findViewById(R.id.toggle_record);
		if (!tog.equals(view)) {
			tog.toggle();
		}
		if (recording) {
			ToggleButton togs = (ToggleButton)findViewById(R.id.toggleSimulate);
			if (togs.isChecked()) {
				togs.toggle();
			}
		}
	}
	public void onClickSave(View view) {
		try {
			if (simulating) {
				onClickSimulate(null);
			}
			if (recording) {
				onClickRecord(null);
			}
			scheduleTaskExecutor.shutdown();
			scheduleTaskExecutor.awaitTermination(5, TimeUnit.SECONDS);
			synchronized(measures_lock) {
				for (; binpointer < measures.size(); binpointer ++) {
					Triple t = measures.get(binpointer);
					out.write(t.toString());
				}
			}
			out.close();
			Intent intent = new Intent(this, SaveActivity.class);
			EditText editText = (EditText) findViewById(R.id.edit_filename);
			String filenameout = editText.getText().toString();
			intent.putExtra(MESSAGE_FILEOUT, filenameout);
			intent.putExtra(MESSAGE_FILEIN, filenamein);
		    startActivity(intent);
		} catch (InterruptedException e) {
			log.append("Thread interrupted, need to press save again!");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	public void onClickDateName(View view) {
		Time today = new Time(Time.getCurrentTimezone());
		today.setToNow();
		EditText editText = (EditText) findViewById(R.id.edit_filename);
		editText.setText(today.format2445()+".csv");
	}
	public void onClickCue(View view) {
		if (recording || simulating) {
    		synchronized(measures_lock) {
    			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.CUE,cuenr ++));
    		}
    	}
	}
	public void onClickSimulate(View view) {
		simulating = simulating?false:true;
		ToggleButton tog = (ToggleButton)findViewById(R.id.toggleSimulate);
		if (!tog.equals(view)) {
			tog.toggle();
		}
	}
	private final Thread simulator = new Thread(new Runnable() {
		public void run() {
			if(!simulating) { return; }
			int deltat=512; // actually 1/deltat
			double freq = 3.5;
			long ti = System.currentTimeMillis();
			for(int i=0; i < deltat; i = i + 1) {
				double amp = Math.sin(((double)i)*freq*2*Math.PI/((double)deltat));
				measures.add(new Triple(ti+i,Triple.Type.RAW,(int)(100*amp)));
			}
		}
	},"simulator");
	private final Thread saver = new Thread(new Runnable() {
		public void run() {
			try {
				synchronized(measures_lock) {
					for (; binpointer < measures.size(); binpointer ++) {
						Triple t = measures.get(binpointer);
						out.write(t.toString());
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
	}, "csvsaver");
	private final Thread guiupdater = new Thread(new Runnable() {
		public void run() {
			runOnUiThread(new Runnable() {
				public void run() {
					int span = 0;
					int n = 0;
					synchronized(measures_lock) {
						n = measures.size();
						if (n > 0) {
							Triple first = measures.get(0);
							Triple last = measures.get(n-1);
							span = (int)(last.time - first.time);
						}
					}
					int min = span / 60000;
					float sec = (span - min) / 1000;
					String info = String.format("#measures: %d, in write buffer: %d, spanning time: %d'%.1f\"",n, n - binpointer,min,sec);
					view_data.setText(info);
				}
			});
		}
	}, "guiupdater");
	private final TextWatcher onTextChanged = new TextWatcher() {
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void afterTextChanged(Editable s) {
        	((Button)findViewById(R.id.button_save)).setEnabled(s.length() > 0);
        }
    };
	static private final Handler eeghandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        	TextView tv = log;
        	switch (msg.what) {
            case TGDevice.MSG_STATE_CHANGE:
                switch (msg.arg1) {
	                case TGDevice.STATE_IDLE:
	                	tv.append("State: idle\n");
	                    break;
	                case TGDevice.STATE_CONNECTING:		                	
	                	tv.append("State: connecting\n");
	                	break;		                    
	                case TGDevice.STATE_CONNECTED:
	                	tv.append("State: connected\n");
	                	rectog.setEnabled(true);
	            		synchronized(measures_lock) {
	            			measures.clear();
	            		}
	                	tgDevice.start();
	                    break;
	                case TGDevice.STATE_NOT_FOUND:
	                	tv.append("State: not found\n");
	                	break;
	                case TGDevice.STATE_NOT_PAIRED:
	                	tv.append("State: not paired\n");
	                	break;
	                case TGDevice.STATE_DISCONNECTED:
	                	tv.append("State: Disconnected mang\n");
	                	rectog.setEnabled(false);
                }

                break;
            case TGDevice.MSG_POOR_SIGNAL:
            	if (verbose || msg.arg1 > 1) {
            		tv.append("PoorSignal: " + msg.arg1 + "\n");
            	}
            	if (recording) {
            		synchronized(measures_lock) {
            			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.SIGNAL,msg.arg1));
            		}
            	}
                break;
            case TGDevice.MSG_RAW_DATA:
            	if (recording) {
            		synchronized(measures_lock) {
            			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.RAW,msg.arg1));
            		}
            	}
            	break;
            case TGDevice.MSG_EEG_POWER:
            	TGEegPower ep = (TGEegPower)msg.obj;
            	if (recording) {
            		synchronized(measures_lock) {
            			long t = System.currentTimeMillis();
            			measures.add(new Triple(t,Triple.Type.LOALPHA, ep.lowAlpha));
            			measures.add(new Triple(t,Triple.Type.HIALPHA, ep.highAlpha));
            			measures.add(new Triple(t,Triple.Type.LOBETA, ep.lowBeta));
            			measures.add(new Triple(t,Triple.Type.HIALPHA, ep.highBeta));
            			measures.add(new Triple(t,Triple.Type.LOGAMMA, ep.lowGamma));
            			measures.add(new Triple(t,Triple.Type.MIDGAMMA, ep.midGamma));
            			measures.add(new Triple(t,Triple.Type.DELTA, ep.delta));
            			measures.add(new Triple(t,Triple.Type.THETA, ep.theta));
            		}
            	}
            	if (verbose) {
            		tv.append("Lo Alpha: " + ep.lowAlpha);
            		tv.append("Hi Alpha: " + ep.highAlpha);
            		tv.append("Theta: " + ep.theta);
            		tv.append("Delta: " + ep.delta);
            		tv.append("Lo Beta: " + ep.lowBeta);
            		tv.append("Hi Beta: " + ep.highBeta);
            	}
            	break;
            case TGDevice.MSG_HEART_RATE:
        		tv.append("Heart rate: " + msg.arg1 + "\n");
                break;
            case TGDevice.MSG_ATTENTION:
            	if (recording) {
            		synchronized(measures_lock) {
            			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.ATTENTION,msg.arg1));
            		}
            	}
            	break;
            case TGDevice.MSG_MEDITATION:
            	if (recording) {
            		synchronized(measures_lock) {
            			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.MEDITATION,msg.arg1));
            		}
            	}
            	break;
            case TGDevice.MSG_BLINK:
            	if (verbose) {
            		tv.append("Blink: " + msg.arg1 + "\n");
            	}
            	if (recording) {
            		synchronized(measures_lock) {
            			measures.add(new Triple(System.currentTimeMillis(),Triple.Type.BLINK,msg.arg1));
            		}
            	}
            	break;
            case TGDevice.MSG_RAW_COUNT:
            	if (verbose) {
            		tv.append("Raw Count: " + msg.arg1 + "\n");
            	}
            	break;
            case TGDevice.MSG_LOW_BATTERY:
            	tv.append("Low battery!\n");
            	break;
            case TGDevice.MSG_RAW_MULTI: // deprecated in all physical devices
            	TGRawMulti rawM = (TGRawMulti)msg.obj;
            	tv.append("Raw1: " + rawM.ch1 + "\nRaw2: " + rawM.ch2);
            	break;
            default:
            	break;
        }
        }
    };
}
