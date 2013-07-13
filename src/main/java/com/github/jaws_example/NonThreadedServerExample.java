package com.github.jaws_example;

import com.github.jaws.ServerWebSocket;
import com.github.jaws.WebSocketException;
import com.github.jaws.proto.DefaultProtocolFactory;
import com.github.jaws.proto.Message;
import com.github.jaws.proto.ProtocolFactory;
import com.github.jaws.WebSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class NonThreadedServerExample {	
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
	
	private static class WebSocketPair {
		public Socket s;
		public ServerWebSocket w;
	}

	public static void main(String[] args) throws IOException {
		ServerSocket server = new ServerSocket(8375);
		
		server.setSoTimeout(1);
		server.setReuseAddress(true);
		
		System.out.println("Waiting for connections on port " + server.getLocalPort()
			+ "...");

		List<WebSocketPair> clients = new LinkedList<WebSocketPair>();
		
		for(;;) {
			// Exit server when Enter (or any character, I suppose) is pressed
			if(System.in.available() > 0 && System.in.read() >= 0) break;
			
			Iterator<WebSocketPair> it = clients.iterator();
			external: while(it.hasNext()) {
				final WebSocketPair current = it.next();
				try {
					current.w.poll();
					if(current.w.getMode() != WebSocket.Mode.WebSocket) {
						continue;
					}
				
					Message m = null;
					while((m = current.w.recv()) != null) {
						if(m.getType() == Message.Type.CloseConnection) {
							current.w.close();
							current.s.close();
							it.remove();
							continue external;
						}

						final String sentence = new String(m.getData(), 0,
							m.getDataLength(), "UTF8");
						System.out.println("Data: " + sentence);
						final String echoString = reverseWords(sentence);
						final byte[] echoUtf8 = echoString.getBytes("UTF8");
						m.setData(echoUtf8);

						current.w.send(m);
					}
				} catch(WebSocketException e) {
					e.printStackTrace();
				}
			}
			
			Socket s = null;
			try {
				s = server.accept();
			} catch(SocketTimeoutException e) {
				continue;
			}
			WebSocketPair webSocketPair = new WebSocketPair();
			webSocketPair.s = s;
			webSocketPair.w = new ServerWebSocket(s);
			clients.add(webSocketPair);
		}
	}
}
