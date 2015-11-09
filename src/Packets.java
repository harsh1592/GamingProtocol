
/**
 * packet class to hold each segment of file
 * 
 * @author Harsh Patil
 *
 */
public class Packets {
	byte[] segment;
	public Packets(byte[] segmentByte){
		this.segment = segmentByte;
	}
	public byte[] getSegment() {
		return segment;
	}
	public void setSegment(byte[] segment) {
		this.segment = segment;
	}
}
