package com.github.jaws_example;

import com.github.jaws.http.HttpHeader;
import com.github.jaws.http.HttpRequestHeader;
import com.github.jaws.proto.ClientHandshake;
import com.github.jaws.proto.HandshakeException;
import com.github.jaws.proto.ServerHandshake;
import com.github.jaws.proto.v13.DecodeException;
import com.github.jaws.proto.v13.DecodedFrame;
import com.github.jaws.proto.v13.EncodedFrame;
import com.github.jaws.proto.v13.HeaderConstants;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/**
 * Hello world!
 *
 */
public class App {
	public static class Worker extends Thread {
		private Socket s;
		private enum Mode {
			Http,
			WebSocket
		};
		
		private DecodedFrame workingDecode;
		private EncodedFrame workingEncode;
		
		private Mode mode = Mode.Http;
		
		public Worker(final Socket s) {
			this.s = s;
		}
		
		private boolean handleHttp(final InputStream in) throws IOException {
			HttpRequestHeader header = HttpRequestHeader.parseRequestHeader(in);
			if(header == null) return false;
			System.out.println(header);
			try {
				ClientHandshake clientHandshake = ClientHandshake
					.parseClientHandshake(header);
				System.out.println("Got valid client handshake.");
				
				ServerHandshake serverHandshake = new ServerHandshake();
				
				serverHandshake.setKey(clientHandshake.getKey());
				final HttpHeader response = serverHandshake.getHeader();
				byte[] toSend = response.generateString().getBytes();
				System.out.println(response);
				s.getOutputStream().write(toSend);
				mode = Mode.WebSocket;
			} catch(HandshakeException e) {
				System.out.println("Client didn't send valid handshake.");
				e.printStackTrace();
			}
			
			return true;
		}
		
		private boolean handleWebSocket(final InputStream in) throws IOException {
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
			
			System.out.printf("Header: %x\n", workingDecode.header);
			System.out.printf("Payload length: %d\n", workingDecode.payloadLength);
			
			System.out.println("Sending back encoded frame.");
			workingEncode = EncodedFrame.encode(HeaderConstants.TEXT_FRAME_OPCODE, true,
				false, workingDecode.data, 0, workingDecode.payloadLength, workingEncode);
			if(!workingEncode.valid) {
				System.out.println("Encode was invalid");
				return true;
			}
			System.out.printf("Writing payload of length %d\n", workingEncode.payloadLength);
			s.getOutputStream().write(workingEncode.raw, 0, workingEncode.payloadLength);
			return true;
		}
		
		private boolean handle(final InputStream in) throws IOException {
			if(mode == Mode.Http) return handleHttp(in);
			if(mode == Mode.WebSocket) return handleWebSocket(in);
			return true;
		}
		
		@Override
		public void run() {
			System.out.println("New connection from " + s.getInetAddress().toString());
			try {
				while(s.isConnected() && handle(s.getInputStream())) Thread.yield();
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) throws IOException {
		ServerSocket server = new ServerSocket(8374);
		
		server.setSoTimeout(1000);
		server.setReuseAddress(true);
		
		System.out.println("Waiting for connections...");
		for(;;) {
			if(System.in.available() > 0 && System.in.read() >= 0) break;
			
			Socket s = null;
			
			try {
				s = server.accept();
			} catch(SocketTimeoutException e) {
				continue;
			}
			
			
			Worker w = new Worker(s);
			w.setDaemon(true);
			w.start();
		}
	}
}
