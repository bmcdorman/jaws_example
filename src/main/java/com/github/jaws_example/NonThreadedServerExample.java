package com.github.jaws_example;

import com.github.jaws.ServerWebSocket;
import com.github.jaws.WebSocketException;
import com.github.jaws.proto.DefaultProtocolFactory;
import com.github.jaws.proto.Message;
import com.github.jaws.proto.ProtocolFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class ThreadedServerExample {
	public static class Worker extends Thread {
		private ServerWebSocket webSocket;
		
		public Worker(final Socket s, final ProtocolFactory factory) throws IOException {
			this.webSocket = new ServerWebSocket(s, factory);
		}
		
		// Extremely simple. Just to show useful manipulation of data
		private static String reverseWords(final String sentence) {
			final String[] words = sentence.split(" ");
			String ret = "";
			for(final String word : words) {
				for(int i = word.length() - 1; i >= 0; --i) {
					ret += String.valueOf(word.charAt(i));
				}
				ret += " ";
			}
			ret = ret.trim();
			return ret;
		}
		
		private boolean handle() throws WebSocketException, IOException {
			webSocket.poll();
			if(webSocket.getMode() != ServerWebSocket.Mode.WebSocket) return true;
			
			Message m = null;
			while((m = webSocket.recv()) != null) {
				if(m.getType() == Message.Type.CloseConnection) {
					webSocket.close();
					return false;
				}
				
				final String sentence = new String(m.getData(), 0,
					m.getDataLength(), "UTF8");
				System.out.println("Data: " + sentence);
				final String echoString = reverseWords(sentence);
				final byte[] echoUtf8 = echoString.getBytes("UTF8");
				m.setData(echoUtf8);
				
				webSocket.send(m);
			}

			return true;
		}
		
		@Override
		public void run() {
			try {
				while(handle()) Thread.yield();
			} catch(final Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) throws IOException {
		ServerSocket server = new ServerSocket(8375);
		
		server.setSoTimeout(1000);
		server.setReuseAddress(true);
		
		System.out.println("Waiting for connections on port " + server.getLocalPort()
			+ "...");
		ProtocolFactory factory = new DefaultProtocolFactory();
		
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
			Worker w = new Worker(s, factory);
			w.setDaemon(true);
			w.start();
		}
	}
}
