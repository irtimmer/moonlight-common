package com.limelight.nvstream.input;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

import com.limelight.nvstream.ConnectionContext;

public class ControllerStream {
	
	private final static int PORT = 35043;
	
	private final static int CONTROLLER_TIMEOUT = 3000;
	
	private ConnectionContext context;
	
	private Socket s;
	private OutputStream out;
	private Cipher riCipher;
	
	private Thread inputThread;
	private LinkedBlockingQueue<InputPacket> inputQueue = new LinkedBlockingQueue<InputPacket>();
	
	private ByteBuffer stagingBuffer = ByteBuffer.allocate(128);
	private ByteBuffer sendBuffer = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN);
	
	public ControllerStream(ConnectionContext context)
	{
		this.context = context;
		try {
			// This cipher is guaranteed to be supported
			this.riCipher = Cipher.getInstance("AES/CBC/NoPadding");
			
			ByteBuffer bb = ByteBuffer.allocate(16);
			bb.putInt(context.riKeyId);
			
			this.riCipher.init(Cipher.ENCRYPT_MODE, context.riKey, new IvParameterSpec(bb.array()));
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (NoSuchPaddingException e) {
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			e.printStackTrace();
		} catch (InvalidAlgorithmParameterException e) {
			e.printStackTrace();
		}
	}
	
	public void initialize() throws IOException
	{
		s = new Socket();
		s.connect(new InetSocketAddress(context.serverAddress, PORT), CONTROLLER_TIMEOUT);
		s.setTcpNoDelay(true);
		out = s.getOutputStream();
	}
	
	public void start()
	{
		inputThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted()) {
					InputPacket packet;
					
					try {
						packet = inputQueue.take();
					} catch (InterruptedException e) {
						context.connListener.connectionTerminated(e);
						return;
					}
					
					// Try to batch mouse move packets
					if (!inputQueue.isEmpty() && packet instanceof MouseMovePacket) {
						MouseMovePacket initialMouseMove = (MouseMovePacket) packet;
						int totalDeltaX = initialMouseMove.deltaX;
						int totalDeltaY = initialMouseMove.deltaY;
						
						// Combine the deltas with other mouse move packets in the queue
						synchronized (inputQueue) {
							Iterator<InputPacket> i = inputQueue.iterator();
							while (i.hasNext()) {
								InputPacket queuedPacket = i.next();
								if (queuedPacket instanceof MouseMovePacket) {
									MouseMovePacket queuedMouseMove = (MouseMovePacket) queuedPacket;
									
									// Add this packet's deltas to the running total
									totalDeltaX += queuedMouseMove.deltaX;
									totalDeltaY += queuedMouseMove.deltaY;
									
									// Remove this packet from the queue
									i.remove();
								}
							}
						}
						
						// Total deltas could overflow the short so we must split them if required
						do {
							short partialDeltaX = (short)(totalDeltaX < 0 ?
									Math.max(Short.MIN_VALUE, totalDeltaX) :
									Math.min(Short.MAX_VALUE, totalDeltaX));
							short partialDeltaY = (short)(totalDeltaY < 0 ?
									Math.max(Short.MIN_VALUE, totalDeltaY) :
									Math.min(Short.MAX_VALUE, totalDeltaY));
							
							initialMouseMove.deltaX = partialDeltaX;
							initialMouseMove.deltaY = partialDeltaY;
							
							try {
								sendPacket(initialMouseMove);
							} catch (IOException e) {
								context.connListener.connectionTerminated(e);
								return;
							}
							
							totalDeltaX -= partialDeltaX;
							totalDeltaY -= partialDeltaY;
						} while (totalDeltaX != 0 && totalDeltaY != 0);
					}
					// Try to batch axis changes on controller packets too
					else if (!inputQueue.isEmpty() && packet instanceof MultiControllerPacket) {
						MultiControllerPacket initialControllerPacket = (MultiControllerPacket) packet;
						ControllerBatchingBlock batchingBlock = null;
						
						synchronized (inputQueue) {
							Iterator<InputPacket> i = inputQueue.iterator();
							while (i.hasNext()) {
								InputPacket queuedPacket = i.next();
								
								if (queuedPacket instanceof MultiControllerPacket) {
									// Only initialize the batching block if we got here
									if (batchingBlock == null) {
										batchingBlock = new ControllerBatchingBlock(initialControllerPacket);
									}
									
									if (batchingBlock.submitNewPacket((MultiControllerPacket) queuedPacket))
									{
										// Batching was successful, so remove this packet
										i.remove();
									}
									else
									{
										// Unable to batch so we must stop
										break;
									}
								}
							}
						}
						
						if (batchingBlock != null) {
							// Reinitialize the initial packet with the new values
							batchingBlock.reinitializePacket(initialControllerPacket);
						}
						
						try {
							sendPacket(packet);
						} catch (IOException e) {
							context.connListener.connectionTerminated(e);
							return;
						}
					}
					else {
						// Send any other packet as-is
						try {
							sendPacket(packet);
						} catch (IOException e) {
							context.connListener.connectionTerminated(e);
							return;
						}
					}
				}
			}
		};
		inputThread.setName("Input - Queue");
		inputThread.setPriority(Thread.NORM_PRIORITY + 1);
		inputThread.start();
	}
	
	public void abort()
	{
		if (inputThread != null) {
			inputThread.interrupt();
			
			try {
				inputThread.join();
			} catch (InterruptedException e) {}
		}
		
		try {
			s.close();
		} catch (IOException e) {}
	}
	
	private static int getPaddedSize(int length) {
		return ((length + 15) / 16) * 16;
	}
	
	private static int inPlacePadData(byte[] data, int length) {
		// This implements the PKCS7 padding algorithm
		
		if ((length % 16) == 0) {
			// Already a multiple of 16
			return length;
		}
		
		int paddedLength = getPaddedSize(length);
		byte paddingByte = (byte)(16 - (length % 16));
		
		for (int i = length; i < paddedLength; i++) {
			data[i] = paddingByte;
		}
		
		return paddedLength;
	}
	
	private int encryptAesInputData(byte[] inputData, int inputLength, byte[] outputData, int outputOffset) throws Exception {
		int encryptedLength = inPlacePadData(inputData, inputLength);
		riCipher.update(inputData, 0, encryptedLength, outputData, outputOffset);
		return encryptedLength;
	}
	
	private void sendPacket(InputPacket packet) throws IOException {
		// Store the packet in wire form in the byte buffer
		packet.toWire(stagingBuffer);
		int packetLen = packet.getPacketLength();
		
		// Pad to 16 byte chunks
		int paddedLength = getPaddedSize(packetLen);
		
		// Allocate a byte buffer to represent the final packet
		sendBuffer.rewind();
		sendBuffer.putInt(paddedLength);
		try {
			encryptAesInputData(stagingBuffer.array(), packetLen, sendBuffer.array(), 4);
		} catch (Exception e) {
			// Should never happen
			e.printStackTrace();
			return;
		}
		
		// Send the packet
		out.write(sendBuffer.array(), 0, paddedLength + 4);
		out.flush();
	}
	
	private void queuePacket(InputPacket packet) {
		synchronized (inputQueue) {
			inputQueue.add(packet);
		}
	}
	
	public void sendControllerInput(short buttonFlags, byte leftTrigger, byte rightTrigger,
			short leftStickX, short leftStickY, short rightStickX, short rightStickY)
	{
		if (context.serverGeneration == ConnectionContext.SERVER_GENERATION_3) {
			// Use legacy controller packets for generation 3
			queuePacket(new ControllerPacket(buttonFlags, leftTrigger,
					rightTrigger, leftStickX, leftStickY,
					rightStickX, rightStickY));
		}
		else {
			// Use multi-controller packets for generation 4 and above
			queuePacket(new MultiControllerPacket((short) 0, buttonFlags, leftTrigger,
					rightTrigger, leftStickX, leftStickY,
					rightStickX, rightStickY));
		}
	}
	
	public void sendControllerInput(short controllerNumber, short buttonFlags, byte leftTrigger, byte rightTrigger,
			short leftStickX, short leftStickY, short rightStickX, short rightStickY)
	{
		if (context.serverGeneration == ConnectionContext.SERVER_GENERATION_3) {
			// Use legacy controller packets for generation 3
			queuePacket(new ControllerPacket(buttonFlags, leftTrigger,
					rightTrigger, leftStickX, leftStickY,
					rightStickX, rightStickY));
		}
		else {
			// Use multi-controller packets for generation 4 and above
			queuePacket(new MultiControllerPacket(controllerNumber, buttonFlags, leftTrigger,
					rightTrigger, leftStickX, leftStickY,
					rightStickX, rightStickY));
		}
	}
	
	public void sendMouseButtonDown(byte mouseButton)
	{
		queuePacket(new MouseButtonPacket(true, mouseButton));
	}
	
	public void sendMouseButtonUp(byte mouseButton)
	{
		queuePacket(new MouseButtonPacket(false, mouseButton));
	}
	
	public void sendMouseMove(short deltaX, short deltaY)
	{
		queuePacket(new MouseMovePacket(deltaX, deltaY));
	}
	
	public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier) 
	{
		queuePacket(new KeyboardPacket(keyMap, keyDirection, modifier));
	}
	
	public void sendMouseScroll(byte scrollClicks)
	{
		queuePacket(new MouseScrollPacket(scrollClicks));
	}
}
