package nu.Tobbe.eegsaver;

public class Triple {
	public enum Type { CUE, SIGNAL, BLINK, RAW, MEDITATION, ATTENTION, LOALPHA, HIALPHA, LOBETA, HIBETA, LOGAMMA, MIDGAMMA, DELTA, THETA }
	public long time;
	public Type type;
	public int value;
	public Triple(long time,Type type,int value) {
		this.time = time;
		this.type = type;
		this.value = value;
	}
	public String toString() {
		return time+","+type+","+value+"\n";
	}
}
