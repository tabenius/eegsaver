package nu.Tobbe.eegsaver;

import com.jjoe64.graphview.GraphView.GraphViewData;

public class Triple extends GraphViewData {
	public enum Type { CUE, SIGNAL, BLINK, RAW, MEDITATION, ATTENTION, LOALPHA, HIALPHA, LOBETA, HIBETA, LOGAMMA, MIDGAMMA, DELTA, THETA }
	public long time;
	public Type type;
	public int value;
	public Triple(long time,Type type,int value) {
		super(time/1000.0, value);
		this.time = time;
		this.type = type;
		this.value = value;
	}
	public String toString() {
		return time+","+type+","+value+"\n";
	}
}
