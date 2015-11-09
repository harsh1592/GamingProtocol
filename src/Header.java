import java.io.Serializable;

/**
 * header to class specifies the format of packet to be sent by the client and 
 * received by the server
 * 
 * @author Harsh Patil
 *
 */
class Header implements Serializable {
	private static final long serialVersionUID = 1L;
	private long id;
	private long seqNum;
	private long ackNum;
	private int chunkSize;
	private byte[] data;

	public Header(long id, long seqNum, long ackNum, int chunkSize, byte[] data) {
		super();
		this.id = id;
		this.seqNum = seqNum;
		this.ackNum = ackNum;
		this.chunkSize = chunkSize;
		this.data = data;
	}



	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public long getSeqNum() {
		return seqNum;
	}

	public void setSeqNum(long seqNum) {
		this.seqNum = seqNum;
	}

	public long getAckNum() {
		return ackNum;
	}

	public void setAckNum(long ackNum) {
		this.ackNum = ackNum;
	}

	public int getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(int chunkSize) {
		this.chunkSize = chunkSize;
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

}
