
/*
 * FileServer.java
 * 
 * @version 
 * $Id: FileServer.java , Version 1.0 11/8/2015 $ 
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
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * File Server class accepts incoming file from sender/game client Run using:
 * java FileServer portnumber
 * 
 * @author Harsh Patil
 *
 */
public class FileServer implements Runnable {
	private String fileName;
	private DatagramPacket receivedPacket;
	private DatagramSocket socket = null;
	private FileOutputStream fos;
	private FileInputStream fis;
	private int noOfSeg;
	private boolean[] receiveCount;
	public Packets[] arrayOfPackets;

	public FileServer(DatagramSocket socket) throws NumberFormatException, SocketException {
		this.socket = socket;
	}

	@Override
	public void run() {
		System.out.println("Server started, listening...");

		// perform handshake
		listenConnection(socket);
		for (int i = 0; i < receiveCount.length; i++) {
			receiveCount[i] = false;
		}
		System.out.println("Listening for file");
		listen(socket);
	}

	class ackReceiver implements Runnable {
		@Override
		public void run() {

		}
	}

	/**
	 * Listens for incoming connection request for file transfer, initializes
	 * the filename to be received and the segment count. Complete the three way
	 * handshake
	 * 
	 * @param sock
	 */
	private void listenConnection(DatagramSocket sock) {
		byte[] recvBuf = new byte[20000];
		try {
			receivedPacket = new DatagramPacket(recvBuf, recvBuf.length);
			sock.receive(receivedPacket);// listen for incoming connection

			// deserialize the object inside received packet
			Header receivedHeader = (Header) deserializePacket(receivedPacket.getData(), receivedPacket.getOffset(),
					receivedPacket.getLength());

			System.out.println("id is " + receivedHeader.getId() + ", sqe number " + receivedHeader.getSeqNum());

			// extract filename and no of segments to receive
			String text = (String) deserializeFileName(receivedHeader.getData());
			String[] temp = text.split(",");
			fileName = temp[0];
			noOfSeg = Integer.valueOf(temp[1]);
			arrayOfPackets = new Packets[noOfSeg];
			// initialize receiveCOunt array to no of segments
			receiveCount = new boolean[noOfSeg];

			System.out.println("received file: " + fileName);

			InetAddress senderAddress = receivedPacket.getAddress();
			int senderPort = receivedPacket.getPort();
			byte[] byteChunkPart = new byte[1];
			Header msg = new Header(0, 100, receivedHeader.getSeqNum() + 1, 0, byteChunkPart);

			System.out.println("ACK sent");

			while (true) {
				sendACK(sock, msg, senderAddress, senderPort);
				try {
					sock.setSoTimeout(5000);
					sock.receive(receivedPacket);
					Header receivedAck = (Header) deserializePacket(receivedPacket.getData(),
							receivedPacket.getOffset(), receivedPacket.getLength());
					if (receivedAck.getAckNum() == 101) {
						System.out.println("ACK received");
						return;
					}
				} catch (SocketTimeoutException ste) {
					System.out.println("no ack");
				}
			}
		} catch (IOException | ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Listen to incoming file segments
	 * 
	 * @param sock
	 */
	public void listen(DatagramSocket sock) {
		byte[] recvBuf = new byte[65000];
		try {
			int bytesRead = 0;
			int count = 0;
			
			//while there are more packets
			while (true) {
				count++;
				receivedPacket = new DatagramPacket(recvBuf, recvBuf.length);
				sock.setSoTimeout(0);
				sock.receive(receivedPacket);

				Header receivedHeader = (Header) deserializePacket(receivedPacket.getData(), receivedPacket.getOffset(),
						receivedPacket.getLength());
				receiveCount[(int) receivedHeader.getId()] = true;
				System.out.println("RECEIVED: seq number " + receivedHeader.getSeqNum());

				arrayOfPackets[(int) receivedHeader.getSeqNum()] = new Packets(receivedHeader.getData());

				// 3 cases for handling incoming packets
				if (case2((int) receivedHeader.getSeqNum())) {
					System.out.println("ACK sent: case2");
				} else if (case1((int) receivedHeader.getSeqNum())) {
					System.out.println("ACK sent: case1");
				} else {
					System.out.println("ACK sent: case3");
					case3((int) receivedHeader.getSeqNum());
				}
				
				// mark packet as received
				receiveCount[(int) receivedHeader.getSeqNum()] = true;

				// if done, break out of loop
				if (checkIfDone()) {
					System.out.println("File Received");
					break;
				}
			}
			generateFile();
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Error: " + e.getMessage());
		}
	}

	/**
	 * check if there are any packets already acked after the current packet
	 * 
	 * @param seqNum
	 */
	private void case3(int seqNum) {
		if (seqNum == 0 || receiveCount[seqNum - 1]) {
			for (int i = seqNum + 1; i < receiveCount.length; i++) {
				if (!receiveCount[i]) {
					InetAddress senderAddress = receivedPacket.getAddress();
					int senderPort = receivedPacket.getPort();
					byte[] byteChunkPart = new byte[1];
					Header msg = new Header(0, 414, i - 1, 0, byteChunkPart);
					System.out.println("Sending ack for " + (i - 1));
					sendACK(socket, msg, senderAddress, senderPort);
					break;
				}
			}
		}
	}

	/**
	 * check if there is any unacked packet before the current packet
	 * 
	 * @param seqNum
	 * @return
	 */
	private boolean case2(int seqNum) {
		// resend the ack for ith - 1 packet for everypacket less than the
		// seq number of received packet.
		int i = 0;
		for (; i < seqNum; i++) {
			if (!receiveCount[i]) {
				/// return true;
				InetAddress senderAddress = receivedPacket.getAddress();
				int senderPort = receivedPacket.getPort();
				byte[] byteChunkPart = new byte[1];
				Header msg = new Header(0, 314, i - 1, 0, byteChunkPart);
				System.out.println("Sending ack for " + (i - 1));
				sendACK(socket, msg, senderAddress, senderPort);
				return true;
			}
		}
		return false;
	}

	/**
	 * check if packets before current packet are all acked and this is 
	 * the first unacked packet
	 * 
	 * @param seqNum
	 * @return
	 */
	private boolean case1(int seqNum) {
		InetAddress senderAddress = receivedPacket.getAddress();
		int senderPort = receivedPacket.getPort();
		byte[] byteChunkPart = new byte[1];
		if (seqNum == 0) {
			if (!receiveCount[seqNum + 1]) {				
				System.out.println("Sending ack for " + seqNum);
				Header msg = new Header(0, 213, seqNum, 0, byteChunkPart);
				sendACK(socket, msg, senderAddress, senderPort);
				return true;
			}
		} else if (receiveCount[seqNum - 1]) {
			if (seqNum < receiveCount.length - 1) {
				if (!receiveCount[seqNum + 1]) {					
					System.out.println("Sending ack for " + seqNum);
					Header msg = new Header(0, 214, seqNum, 0, byteChunkPart);
					sendACK(socket, msg, senderAddress, senderPort);
					return true;
				}
			} else {
				System.out.println("Sending ack for " + seqNum);
				Header msg = new Header(0, 214, seqNum, 0, byteChunkPart);
				sendACK(socket, msg, senderAddress, senderPort);
				return true;
			}
		}
		return false;
	}

	/**
	 * Generate the file on receiving all packets
	 * 
	 * @throws IOException
	 */
	private void generateFile() throws IOException {
		File receivedFile = new File(fileName);
		fos = new FileOutputStream(receivedFile, false);
		for (Packets p : arrayOfPackets) {
			fos.write(p.segment);
			fos.flush();
		}
		fos.close();
	}

	/**
	 * check if all packets received
	 * @return
	 */
	private boolean checkIfDone() {
		for (boolean ackStatus : receiveCount) {
			if (ackStatus) {

			} else {
				return false;
			}
		}
		return true;
	}

	/**
	 * Method to send ack to sender
	 * 
	 * @param socket
	 * @param senderPort
	 * @param senderAddress
	 * @param neighborPort
	 */
	public void sendACK(DatagramSocket socket, Header msg, InetAddress senderAddress, int senderPort) {
		try {

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
		oStream.close();
		return packet;
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
	private Object deserializeFileName(byte[] bytes) throws IOException, ClassNotFoundException {
		ByteArrayInputStream bStream = new ByteArrayInputStream(bytes);
		ObjectInputStream oStream = new ObjectInputStream(new BufferedInputStream(bStream));
		Object packet = new Object();
		packet = oStream.readObject();
		oStream.close();
		return packet;
	}

	/**
	 * Main method
	 * 
	 * @param args
	 * @throws NumberFormatException
	 * @throws SocketException
	 * @throws InterruptedException
	 */
	public static void main(String args[]) throws NumberFormatException, SocketException, InterruptedException {
		String destPort = args[0];
		DatagramSocket socket = new DatagramSocket(Integer.valueOf(destPort));
		Thread sendingThread = new Thread(new FileServer(socket));
		sendingThread.start();
		sendingThread.join();
	}
}
