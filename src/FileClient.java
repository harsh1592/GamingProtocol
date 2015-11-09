
/*
 * FileClient.java
 * 
 * @version 
 * $Id: FileClient.java , Version 1.0 11/8/2015 $ 
 * 
 * @revision 
 * $Log Initial Version 2.0$  
 * 
 */
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * File Client class send file to game server 
 * Run using: 
 * java FileServer portnumber
 * 
 * @author Harsh Patil
 */
public class FileClient implements Runnable {
	public String fileName;
	public String destAddress;
	public int destPort;
	public DatagramPacket requestPacket;
	public DatagramSocket socket = null;
	public InetAddress aHost;
	public File sendingFile;
	public static Packets[] arrayOfPackets;
	public static boolean[] ackArray;
	public final int slowStart = 1;
	public int cwnd;
	private static Thread[] threadArray;
	public static Integer[] sync;
	public static ArrayList<Boolean> timeoutList;

	public FileClient(String fileName, String destAddress, int destPort) throws Exception {
		this.fileName = fileName;
		this.destAddress = destAddress;
		this.destPort = destPort;
		socket = new DatagramSocket(5001);
		sendingFile = new File(fileName);
		aHost = InetAddress.getByName(destAddress);
	}

	@Override
	public void run() {
		try {
			boolean ack = false;

			while (!ack) {
				// send first request with filename and number of segments
				ack = sendFileName();
			}
			System.out.println("connected to server, file send initializing...");
			Thread.sleep(1000);
			// generate the array of all the segments to be sent
			generateSegArray();

			// start sending the file
			startSending();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Method to manage the sending threads and ack receiving
	 * 
	 * @throws InterruptedException
	 */
	private void startSending() throws InterruptedException {
		cwnd = slowStart;
		boolean finished = false;
		int start = 0;
		int end = 0;
		int ackArraylength = ackArray.length;
		System.out.println("cwnd:" + cwnd + " start:" + start + " end:" + end);
		int count = 0;
		while (!ackArray[ackArraylength - 1]) {
			count++;
			threadArray = new Thread[cwnd];
			sync = new Integer[cwnd];

			// create threads as many as packets in the window
			for (int i = start; i < (start + cwnd); i++) {
				threadArray[i - start] = new Thread(new fileSender(i, start));
				sync[i - start] = new Integer(1);
			}
			Thread ackReceiverThread = new Thread(new ackReceiver(start, end));

			for (int y = start; y <= end; y++) {
				timeoutList.remove(y);
				timeoutList.add(y, false);
			}

			// start sending thread for each packet in the window
			for (Thread t : threadArray) {
				t.start();
			}

			// start ack receiving thread
			ackReceiverThread.start();
			// wait for ack receiving thread to finish
			ackReceiverThread.join();

			// wait for all sending threads to finish
			for (Thread t : threadArray) {
				t.join();
			}

			// check if finished
			if (finished) {
				// sendClose()
				System.out.println("File sent success");
				System.exit(0);
			}

			// set window start to the first unacked packet
			start = getFirstFalseInAckArray(start, end);

			// cwnd back to slow start if congestion
			if (start != (end + 1)) {
				cwnd = slowStart;
				end = start;
			} else {
				// don't increase the cwnd by more than 16
				if (cwnd < 16) {
					// increase cwnd by twice every time
					cwnd *= 2;
					end = start + cwnd - 1;
				} else {
					end = start + cwnd - 1;
				}
			}

			// update end for last pkt
			if (end < ackArray.length) {

			} else {
				finished = true;
				end = ackArray.length - 1;
				cwnd = end - start + 1;
			}
			System.out.println("cwnd:" + cwnd + " start:" + start + " end:" + end);
		}
	}

	/**
	 * Return the last true in the ack array
	 * 
	 * @param start
	 * @param end
	 * @return
	 */
	private int getFirstFalseInAckArray(int start, int end) {
		int firstFalseIndex = end;
		for (int i = ackArray.length - 1; i >= start; i--) {
			if (ackArray[i]) { // if true return the index of next packet
				firstFalseIndex = i;
				break;
			}
		}
		return firstFalseIndex + 1;
	}

	/**
	 * @param start
	 * @param end
	 * @return
	 */
	private boolean istimeOut(int start, int end) {
		for (int i = start; i <= end; i++) {
			if (timeoutList.get(i)) { // if false return false
				return true;
			}
		}
		return false;
	}

	/**
	 * Inner sending file class
	 * 
	 * @author Harsh Patil
	 */
	class fileSender implements Runnable {
		private int index;
		public int start;

		public fileSender(int i, int start) {
			this.index = i;
			this.start = start;
		}

		@Override
		public void run() {
			// send the packet at index
			send(arrayOfPackets[index].segment);

			synchronized (sync[index - start]) {
				try {
					long startTimer = System.currentTimeMillis();
					sync[index - start].wait(5000);// TIMEOUT

					// if timeout change mark the packet in timeout list as true
					if ((System.currentTimeMillis() - startTimer) >= 5000) {
						startTimer = System.currentTimeMillis();
						synchronized (timeoutList) {
							timeoutList.remove(index);
							timeoutList.add(index, true);
						}
					} else {

					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Inner ack receiver file class
	 * 
	 * @author Harsh Patil
	 */
	class ackReceiver implements Runnable {
		public int start;
		public int end;

		public ackReceiver(int start, int end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public void run() {
			int segToAck = 0;

			while (segToAck <= (end - start) && (countTimeout(start, end) != countAckFalse(start, end))) {
				byte[] recvBuf = new byte[1000];
				try {
					segToAck++;
					requestPacket = new DatagramPacket(recvBuf, recvBuf.length);
					socket.setSoTimeout(2000);
					socket.receive(requestPacket);
					Header receivedHeader = (Header) deserializePacket(requestPacket.getData(),
							requestPacket.getOffset(), requestPacket.getLength());
					System.out.println("RECEIVED: ack is " + receivedHeader.getAckNum());

					ackArray[(int) receivedHeader.getAckNum()] = true;
					for (int x = start; x <= (int) receivedHeader.getAckNum(); x++) {

						if ((x - start) <= (end - start)) {
							synchronized (sync[x - start]) {
								sync[x - start].notify();
							}
						}
					}

					for (int i = 0; i < (int) receivedHeader.getAckNum(); i++) {
						ackArray[i] = true;
					}

				} catch (Exception e) {
				}
			}
		}

		private int countAckFalse(int start2, int end2) {
			int falseCount = 0;
			for (int i = start2; i <= end2; i++) {
				if (!ackArray[i]) { // if false increase count
					falseCount++;
				}
			}
			return falseCount;
		}

		private int countTimeout(int start2, int end2) {
			int timeOutCount = 0;
			synchronized (timeoutList) {
				for (int i = start2; i <= end2; i++) {
					if (timeoutList.get(i)) { // if false increase count
						timeOutCount++;
					}
				}
			}
			return timeOutCount;
		}
	}

	/**
	 * @return
	 */
	private boolean sendFileName() {
		long ack = 0;
		try {
			int fileSize = (int) sendingFile.length();
			byte[] byteChunkPart;
			long chunkCount = 0;
			int readLength = 60000;
			byteChunkPart = new byte[readLength];

			int noOfSeg = fileSize / readLength + 1;
			arrayOfPackets = new Packets[noOfSeg]; // complete array of segments
			ackArray = new boolean[noOfSeg];

			String payLoad = fileName + "," + noOfSeg;
			byteChunkPart = serializeFileName(payLoad);
			Header msg = new Header(chunkCount, chunkCount, ack, readLength, byteChunkPart);
			System.out.println("readLength " + readLength);

			// serialize the sending packet
			byte[] sendBuf = serializePacket(msg);
			send(sendBuf);
			System.out.println("length of buffer " + sendBuf.length);
			System.out.println("connection req sent");

		} catch (Exception E) {
			System.err.println("sending file name error");
		}

		return listenForHS(ack);
	}

	/**
	 * 
	 */
	private void generateSegArray() {
		try {
			FileInputStream fis = new FileInputStream(sendingFile);

			int fileSize = (int) sendingFile.length();
			byte[] byteChunkPart;
			long chunkCount = 0;
			int read = 0;
			int ack = 0;
			int readLength = 60000;
			int offset = 0;
			byteChunkPart = new byte[readLength];
			timeoutList = new ArrayList<Boolean>();
			System.out.println("Making segments ");
			do {
				read = fis.read(byteChunkPart, 0, readLength);
				offset += read;
				Header msg = new Header(chunkCount, chunkCount, 0, readLength, byteChunkPart);

				// serialize the sending packet
				byte[] sendBuf = serializePacket(msg);
				arrayOfPackets[(int) chunkCount] = new Packets(sendBuf);
				ackArray[(int) chunkCount] = false;
				timeoutList.add(false);
				chunkCount++;
			} while (offset < fileSize);
			fis.close();
			System.out.println(chunkCount);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("error connection");
		}
	}

	/**
	 * Method which runs a thread to send a packet to the neighbor port
	 * 
	 * @param socket
	 * @param neighborPort
	 */
	public void send(byte[] sendBuf) {
		try {

			DatagramPacket replyPacket = new DatagramPacket(sendBuf, sendBuf.length, aHost, destPort);
			// Thread.sleep(100);
			socket.send(replyPacket);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * @param ack
	 * @return
	 */
	private boolean listenForHS(long ack) {
		byte[] recvBuf = new byte[1000];
		try {
			requestPacket = new DatagramPacket(recvBuf, recvBuf.length);
			socket.receive(requestPacket);

			Header receivedHeader = (Header) deserializePacket(requestPacket.getData(), requestPacket.getOffset(),
					requestPacket.getLength());

			System.out.println("RECEIVED:	id is " + receivedHeader.getId() + ", sqe number "
					+ receivedHeader.getSeqNum() + ", ack number " + receivedHeader.getAckNum());
			if (receivedHeader.getAckNum() != (ack + 1)) {
				return false;
			}
			InetAddress senderAddress = requestPacket.getAddress();
			int senderPort = requestPacket.getPort();
			byte[] byteChunkPart = new byte[1];
			Header msg = new Header(0, 2, receivedHeader.getSeqNum() + 1, 0, byteChunkPart);
			System.out.println("sending ack number: " + (receivedHeader.getSeqNum() + 1));
			sendACK(socket, msg, senderAddress, senderPort);

		} catch (IOException | ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}

	/**
	 * Method which runs a thread to send a packet to the neighbor port
	 * 
	 * @param socket
	 * @param senderPort
	 * @param senderAddress
	 * @param neighborPort
	 */
	public void sendACK(DatagramSocket socket, Header msg, InetAddress senderAddress, int senderPort) {
		try {
			System.out.println("sending");
			// serialize the sending packet
			byte[] sendBuf = serializePacket(msg);
			DatagramPacket replyPacket = new DatagramPacket(sendBuf, sendBuf.length, senderAddress, senderPort);
			// send update every one second
			Thread.sleep(1000);
			socket.send(replyPacket);
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	/**
	 * Serialize the packet to be sent over UDP connection
	 * 
	 * @param packet
	 *            Packet object to send
	 * @return
	 * @throws IOException
	 */
	public byte[] serializePacket(Header packet) throws IOException {
		ByteArrayOutputStream bStream = new ByteArrayOutputStream();
		ObjectOutputStream oStream = new ObjectOutputStream(bStream);
		oStream.flush();
		oStream.writeObject(packet);
		oStream.flush();
		byte[] serializedByte = bStream.toByteArray();
		return serializedByte;
	}

	/**
	 * Serialize the filename to be sent over UDP connection
	 * 
	 * @param packet
	 *            Packet object to send
	 * @return
	 * @throws IOException
	 */
	public byte[] serializeFileName(String fileName) throws IOException {
		ByteArrayOutputStream bStream = new ByteArrayOutputStream();
		ObjectOutputStream oStream = new ObjectOutputStream(bStream);
		oStream.flush();
		oStream.writeObject(fileName);
		oStream.flush();
		byte[] serializedByte = bStream.toByteArray();
		return serializedByte;
	}

	/**
	 * DeSerialize the incoming byte on receiving it via UDP to a Packet object
	 * 
	 * @param bytes
	 *            Incoming bytes
	 * @param offset
	 *            Offset of data inside the byte
	 * @param length
	 *            Length of data
	 * @return
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	private Object deserializePacket(byte[] bytes, int offset, int length) throws IOException, ClassNotFoundException {
		ByteArrayInputStream bStream = new ByteArrayInputStream(bytes, offset, length);
		ObjectInputStream oStream = new ObjectInputStream(new BufferedInputStream(bStream));
		Object packet = new Object();
		packet = oStream.readObject();
		return packet;
	}

	public static void main(String args[]) throws Exception {
		String fileName = args[0];
		String destAddress = args[1];
		String destPort = args[2];
		Thread sendingThread = new Thread(new FileClient(fileName, destAddress, Integer.valueOf(destPort)));
		sendingThread.start();
		sendingThread.join();
	}

}
