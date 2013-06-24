package com.github.jaws_example;

import com.github.jaws.http.HttpHeader;
import com.github.jaws.http.HttpRequestHeader;
import com.github.jaws.proto.ClientHandshake;
import com.github.jaws.proto.HandshakeException;
import com.github.jaws.proto.ServerHandshake;
import com.github.jaws.proto.v13.DecodeException;
import com.github.jaws.proto.v13.DecodedFrame;
import com.github.jaws.proto.v13.EncodeException;
import com.github.jaws.proto.v13.EncodedFrame;
import com.github.jaws.proto.v13.HeaderConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class App {
	public static class Worker extends Thread {
		private Socket s;
		private enum Mode {
			Http,
			WebSocket
		};
		
		private DecodedFrame workingDecode;
		private EncodedFrame workingEncode;
		
		// By default our state machine is in the http state
		private Mode mode = Mode.Http;
		
		public Worker(final Socket s) {
			this.s = s;
		}
		
		private boolean handleHttp() throws IOException {
			final InputStream in = s.getInputStream();
			final OutputStream out = s.getOutputStream();
			
			HttpRequestHeader header = HttpRequestHeader.parseRequestHeader(in);
			if(header == null) return false;
			System.out.println(header);
			try {
				// Get the client's handshake
				ClientHandshake clientHandshake = ClientHandshake
					.parseClientHandshake(header);
				System.out.println("Got valid client handshake.");
				
				// Prepare our server handshake
				ServerHandshake serverHandshake = new ServerHandshake();
				serverHandshake.setKey(clientHandshake.getKey());
				final HttpHeader response = serverHandshake.getHeader();
				byte[] toSend = response.generateString().getBytes();
				System.out.println(response);
				
				// Write it out
				out.write(toSend);
				
				// Send our state machine to the WebSocket state
				mode = Mode.WebSocket;
			} catch(HandshakeException e) {
				System.out.println("Client didn't send valid handshake.");
				e.printStackTrace();
			}
			
			return true;
		}
		
		// Extremely simple. Just to show useful manipulation of data
		private static String reverseWords(final String sentence) {
			String[] words = sentence.split(" ");
			String ret = "";
			for(String word : words) {
				for(int i = word.length() - 1; i >= 0; --i) {
					ret += String.valueOf(word.charAt(i));
				}
				ret += " ";
			}
			ret = ret.trim();
			return ret;
		}
		
		private void closeWebSocket() throws EncodeException, IOException {
			workingEncode = EncodedFrame.encode(
				HeaderConstants.CONNECTION_CLOSE_FRAME_OPCODE,
				true, false, null, 0, 0, workingEncode);
			s.getOutputStream().write(workingEncode.raw, 0, workingEncode.totalLength);
		}
		
		private boolean handleWebSocket() throws IOException {
			final InputStream in = s.getInputStream();
			final OutputStream out = s.getOutputStream();
			
			// No data? Go back to loop, yield, and try again.
			if(in.available() == 0) return true;
			
			// Decode the incoming data
			System.out.println("Handle web socket");
			try {
				workingDecode = DecodedFrame.decode(in, workingDecode);
			} catch(DecodeException e) {
				e.printStackTrace();
				return true;
			}
			if(!workingDecode.valid) {
				System.out.println("Decode was invalid");
				return true;
			}
			
			final int header = workingDecode.header;
			System.out.printf("Header: %x\n", header);
			if((header & HeaderConstants.CONNECTION_CLOSE_FRAME_OPCODE) != 0) {
				System.out.println("Client asked us to terminate the connection.");
				closeWebSocket();
				return false;
			}
			System.out.printf("Payload length: %d\n", workingDecode.payloadLength);
			
			// Reverse the words of the string
			final String sentence = new String(workingDecode.data, 0,
				workingDecode.payloadLength, "UTF8");
			System.out.println("Data: " + sentence);
			final String echoString = reverseWords(sentence);
			final byte[] echoUtf8 = echoString.getBytes("UTF8");
			
			// Encode our return message.
			System.out.println("Sending back encoded frame.");
			workingEncode = EncodedFrame.encode(HeaderConstants.TEXT_FRAME_OPCODE, true,
				false, echoUtf8, 0, echoUtf8.length, workingEncode);
			if(!workingEncode.valid) {
				System.out.println("Encode was invalid");
				return true;
			}
			
			// Write it out
			System.out.printf("Writing payload of length %d\n",
				workingEncode.payloadLength);
			System.out.println("Writing Data: " + new String(workingEncode.raw,
				workingEncode.payloadStart, workingEncode.payloadLength));
			out.write(workingEncode.raw, 0, workingEncode.totalLength);
			return true;
		}
		
		private boolean handle() throws IOException {
			// Extremely simple "state machine"
			if(mode == Mode.Http) return handleHttp();
			if(mode == Mode.WebSocket) return handleWebSocket();
			return true;
		}
		
		@Override
		public void run() {
			System.out.println("New connection from " + s.getInetAddress());
			try {
				while(s.isConnected() && handle()) Thread.yield();
			} catch(IOException e) {
				e.printStackTrace();
			}
			System.out.println("Connection from " + s.getInetAddress()
				+ " terminated.");
		}
	}

	public static void main(String[] args) throws IOException {
		ServerSocket server = new ServerSocket(8374);
		
		server.setSoTimeout(1000);
		server.setReuseAddress(true);
		
		System.out.println("Waiting for connections on port " + server.getLocalPort()
			+ "...");
		for(;;) {
			// Exit server when Enter (or any character, I suppose) is pressed
			if(System.in.available() > 0 && System.in.read() >= 0) break;
			
			Socket s = null;
			try {
				s = server.accept();
			} catch(SocketTimeoutException e) {
				continue;
			}
			
			// Start up new socket worker as daemon
			Worker w = new Worker(s);
			w.setDaemon(true);
			w.start();
		}
	}
}
