/*
 * RED5 Open Source Flash Server - https://github.com/Red5/
 * 
 * Copyright 2006-2015 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.rtmp;

import java.io.IOException;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.message.Packet;
import org.red5.server.net.rtmpe.RTMPEIoFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all RTMP protocol events fired by the MINA framework.
 */
public class RTMPMinaIoHandler extends IoHandlerAdapter {

	private static Logger log = LoggerFactory.getLogger(RTMPMinaIoHandler.class);

	/**
	 * RTMP events handler
	 */
	protected IRTMPHandler handler;

	protected ProtocolCodecFactory codecFactory;

	/** {@inheritDoc} */
	@Override
	public void sessionCreated(IoSession session) throws Exception {
		log.debug("Session created");
		// add rtmpe filter
		session.getFilterChain().addFirst("rtmpeFilter", new RTMPEIoFilter());
		// add protocol filter next
		session.getFilterChain().addLast("protocolFilter", new ProtocolCodecFilter(codecFactory));
		// create a connection
		RTMPMinaConnection conn = createRTMPMinaConnection();
		// add session to the connection
		conn.setIoSession(session);
		// add the handler
		conn.setHandler(handler);
		// add the connections session id for look up using the connection manager
		session.setAttribute(RTMPConnection.RTMP_SESSION_ID, conn.getSessionId());
		// add the in-bound handshake
		session.setAttribute(RTMPConnection.RTMP_HANDSHAKE, new InboundHandshake());
	}

	/** {@inheritDoc} */
	@Override
	public void sessionOpened(IoSession session) throws Exception {
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.debug("Session opened: {} id: {}", session.getId(), sessionId);
		RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
		handler.connectionOpened(conn);
	}

	/** {@inheritDoc} */
	@Override
	public void sessionClosed(IoSession session) throws Exception {
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.debug("Session closed: {} id: {}", session.getId(), sessionId);
		if (log.isTraceEnabled()) {
			log.trace("Session attributes: {}", session.getAttributeKeys());
		}
		if (sessionId != null) {
			RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
			if (conn != null) {
				// fire-off closed event
				handler.connectionClosed(conn);
				// clear any session attributes we may have previously set
				// TODO: verify this cleanup code is necessary. The session is over and will be garbage collected surely?
				if (session.containsAttribute(RTMPConnection.RTMP_HANDSHAKE)) {
					session.removeAttribute(RTMPConnection.RTMP_HANDSHAKE);
				}
				if (session.containsAttribute(RTMPConnection.RTMPE_CIPHER_IN)) {
					session.removeAttribute(RTMPConnection.RTMPE_CIPHER_IN);
					session.removeAttribute(RTMPConnection.RTMPE_CIPHER_OUT);
				}
			} else {
				log.warn("Connection was not found for {}", sessionId);
			}
			cleanSession(session, false);
		} else {
			log.debug("Connections session id was null in session, may already be closed");
		}
	}

	/**
	 * Handle raw buffer receiving event.
	 *
	 * @param in Data buffer
	 * @param session I/O session, that is, connection between two endpoints
	 */
	protected void rawBufferRecieved(IoBuffer in, IoSession session) {
		if (log.isTraceEnabled()) {
			log.trace("rawBufferRecieved: {}", in);
		}
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.trace("Session id: {}", sessionId);
		RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
		RTMPHandshake handshake = (RTMPHandshake) session.getAttribute(RTMPConnection.RTMP_HANDSHAKE);
		if (handshake != null) {
			if (conn.getStateCode() != RTMP.STATE_HANDSHAKE) {
				log.warn("Raw buffer after handshake, something odd going on");
			}
			log.debug("Handshake - server phase 1 - size: {}", in.remaining());
			IoBuffer out = handshake.doHandshake(in);
			if (out != null) {
				log.trace("Output: {}", out);
				session.write(out);
				//if we are connected and doing encryption, add the ciphers
				if (conn.getStateCode() == RTMP.STATE_CONNECTED) {
					// remove handshake from session now that we are connected
					// if we are using encryption then put the ciphers in the session
					if (handshake.getHandshakeType() == RTMPConnection.RTMP_ENCRYPTED) {
						log.debug("Adding ciphers to the session");
						session.setAttribute(RTMPConnection.RTMPE_CIPHER_IN, handshake.getCipherIn());
						session.setAttribute(RTMPConnection.RTMPE_CIPHER_OUT, handshake.getCipherOut());
					}
				}
			}
		} else {
			log.warn("Handshake was not found for this connection: {}", conn);
			log.debug("Session: {}", session.getId());
		}
	}

	/** {@inheritDoc} */
	@Override
	public void messageReceived(IoSession session, Object message) throws Exception {
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.trace("Message received on session: {} id: {}", session.getId(), sessionId);
		if (message instanceof IoBuffer) {
			rawBufferRecieved((IoBuffer) message, session);
		} else {
			log.trace("Session id: {}", sessionId);
			RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
			if (conn != null) {
				byte state = conn.getStateCode();
				// checking the state before allowing a task to be created will hopefully prevent rejected task exceptions
				if (state != RTMP.STATE_DISCONNECTING && state != RTMP.STATE_DISCONNECTED) {
					conn.handleMessageReceived((Packet) message);
				} else {
					log.info("Ignoring received message on {} due to state: {}", sessionId, RTMP.states[state]);
				}
			} else {
				log.warn("Connection was not found for {}, force closing", sessionId);
				forceClose(session);
			}
		}
	}

	/** {@inheritDoc} */
	@Override
	public void messageSent(IoSession session, Object message) throws Exception {
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.trace("Message sent on session: {} id: {}", session.getId(), sessionId);
		RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
		if (conn != null) {
			byte state = conn.getStateCode();
			if (state != RTMP.STATE_DISCONNECTING && state != RTMP.STATE_DISCONNECTED) {
				if (message instanceof Packet) {
					handler.messageSent(conn, (Packet) message);
				} else {
					log.debug("Message was not of Packet type; its type: {}", message != null ? message.getClass().getName() : "null");
				}
			}
		} else {
			log.warn("Destination connection was null, it is already disposed. Session id: {}", sessionId);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
		String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		if (log.isDebugEnabled()) {
			log.debug("Exception caught on session: {} id: {}", session.getId(), sessionId, cause);
			cause.printStackTrace();
		}
		if (cause instanceof IOException) {
			// Mina states that the connection will be automatically closed when an IOException is caught
			log.debug("IOException caught on {}", sessionId);
		} else {
			log.debug("Non-IOException caught on {}", sessionId);
			forceClose(session);
		}
	}

	/**
	 * Force the NioSession to be released and cleaned up.
	 * 
	 * @param session
	 */
	private void forceClose(final IoSession session) {
		log.warn("Force close - session: {}", session.getId());
		if (session.containsAttribute("FORCED_CLOSE")) {
			log.info("Close already forced on this session: {}", session.getId());
		} else {
			// set flag
			session.setAttribute("FORCED_CLOSE", Boolean.TRUE);
			session.suspendRead();
			cleanSession(session, true);
		}
	}
	
	/**
	 * Close and clean-up the IoSession.
	 * 
	 * @param session
	 * @param immediately - close without waiting for the write queue to flush
	 */
	private void cleanSession(final IoSession session, boolean immediately) {
		// clean up
		final String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
		log.debug("Forcing close on session: {} id: {}", session.getId(), sessionId);
		log.debug("Session closing: {}", session.isClosing());
		// get the write request queue
		final WriteRequestQueue writeQueue = session.getWriteRequestQueue();
		if (writeQueue != null && !writeQueue.isEmpty(session)) {
			log.debug("Clearing write queue");
			try {
				writeQueue.clear(session);
			} catch (Exception ex) {
				// clear seems to cause a write to closed session ex in some cases
				log.warn("Exception clearing write queue for {}", sessionId, ex);
			}
		}
		// force close the session
		final CloseFuture future = session.close(immediately);
		IoFutureListener<CloseFuture> listener = new IoFutureListener<CloseFuture>() {
			@SuppressWarnings({ "unchecked", "rawtypes" })
			public void operationComplete(CloseFuture future) {
				// now connection should be closed
				log.debug("Close operation completed {}: {}", sessionId, future.isClosed());
				future.removeListener(this);
				for (Object key : session.getAttributeKeys()) {
					Object obj = session.getAttribute(key);
					if (obj != null) {
    					log.debug("Attribute: {}", obj.getClass().getName());
    					if (obj instanceof IoProcessor) {
    						log.debug("Flushing session in processor");
    						((IoProcessor) obj).flush(session);
    						log.debug("Removing session from processor");
    						((IoProcessor) obj).remove(session);
    					} else if (obj instanceof IoBuffer) {
    						log.debug("Clearing session buffer");
    						((IoBuffer) obj).clear();
    						((IoBuffer) obj).free();
    					}
					}
				}
			}
		};
		future.addListener(listener);		
	}

	/**
	 * Setter for handler.
	 *
	 * @param handler RTMP events handler
	 */
	public void setHandler(IRTMPHandler handler) {
		this.handler = handler;
	}

	/**
	 * @param codecFactory the codecFactory to set
	 */
	public void setCodecFactory(ProtocolCodecFactory codecFactory) {
		this.codecFactory = codecFactory;
	}

	protected RTMPMinaConnection createRTMPMinaConnection() {
		return (RTMPMinaConnection) RTMPConnManager.getInstance().createConnection(RTMPMinaConnection.class);
	}
}
