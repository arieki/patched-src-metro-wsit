/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.xml.ws.rm.runtime;

import com.sun.istack.NotNull;
import com.sun.xml.ws.api.message.HeaderList;
import com.sun.xml.ws.api.message.Message;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.api.pipe.Fiber;
import com.sun.xml.ws.rm.CreateSequenceException;
import com.sun.xml.ws.rm.RmException;
import com.sun.xml.ws.rm.localization.RmLogger;
import com.sun.xml.ws.rm.policy.Configuration;
import com.sun.xml.ws.security.secext10.SecurityTokenReferenceType;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * <p>
 * RM session represents a contract between single WS proxy and it's corresponding service. Multiple tubelines (of the same
 * WS proxy) may share a single RM session, each WS proxy however creates it's own session.
 * </p>
 * <p>
 * RM session performs all tasks related to RM message processing, while being focused on a single reliable connection.
 * </p>
 * 
 * @author Marek Potociar (marek.potociar at sun.com)
 */
abstract class ClientSession {

    private static final RmLogger LOGGER = RmLogger.getLogger(ClientSession.class);
    private static final int MAX_INITIATE_SESSION_ATTEMPTS = 3;

    static ClientSession create(Configuration configuration, ProtocolCommunicator communicator) {
        switch (configuration.getRmVersion()) {
            case WSRM10:
                return new Rm10ClientSession(configuration, communicator);
            case WSRM11:
                return new Rm11ClientSession(configuration, communicator);
            default:
                // TODO L10N
                throw new IllegalStateException("Unsupported WS-ReliableMessaging version [ " + configuration.getRmVersion().namespaceUri + "]");
        }
    }

    private static class FiberRegistration {

        private final long timestamp;
        final Fiber fiber;
        final Packet packet;

        FiberRegistration(Fiber fiber, Packet packet) {
            this.timestamp = System.currentTimeMillis();
            this.fiber = fiber;
            this.packet = packet;
        }

        boolean expired(long period) {
            return System.currentTimeMillis() - timestamp >= period;
        }
    }
    protected String inboundSequenceId;
    protected String outboundSequenceId;
    protected final SequenceManager sequenceManager;
    protected final ProtocolCommunicator communicator;
    protected final Configuration configuration;
    private final Lock initLock;
    private final ScheduledTaskManager scheduledTaskManager;
    private final Queue<FiberRegistration> fibersToResend = new LinkedList<FiberRegistration>();
    private final AtomicLong lastAckRequestedTime = new AtomicLong(0);

    protected ClientSession(Configuration configuration, ProtocolCommunicator communicator) {
        this.inboundSequenceId = null;
        this.outboundSequenceId = null;
        this.initLock = new ReentrantLock();
        this.configuration = configuration;
        this.sequenceManager = SequenceManagerFactory.getInstance().getSequenceManager();
        this.communicator = communicator;
        this.scheduledTaskManager = new ScheduledTaskManager();
    }

    protected abstract void openRmSession(String offerInboundSequenceId, SecurityTokenReferenceType strType) throws RmException;

    protected abstract void appendSequenceHeader(Message outboundMessage) throws RmException;

    protected abstract void appendAckRequestedHeader(Message outboundMessage) throws RmException;

    protected abstract void appendSequenceAcknowledgementHeader(Message outboundMessage) throws RmException;

    protected abstract void processSequenceHeader(HeaderList inboundMessageHeaders) throws RmException;

    protected abstract void processAcknowledgementHeader(HeaderList inboundMessageHeaders) throws RmException;

    protected abstract void processAckRequestedHeader(HeaderList inboundMessageHeaders) throws RmException;

    protected abstract void closeOutboundSequence() throws RmException;

    protected abstract void terminateOutboundSequence() throws RmException;

    protected final void processInboundMessageHeaders(HeaderList responseHeaders, boolean expectSequenceHeader) throws RmException {
        if (responseHeaders != null) {
            if (expectSequenceHeader) {
                processSequenceHeader(responseHeaders);
            }
            processAcknowledgementHeader(responseHeaders);
            processAckRequestedHeader(responseHeaders);
        }
    }

    protected final void assertSequenceIdInInboundHeader(String expected, String actual) {
        if (expected != null && !expected.equals(actual)) {
            // TODO L10N
            throw LOGGER.logSevereException(new IllegalStateException(
                    "Sequence id in the inbound message header [" + actual + " ] " +
                    "does not match the sequence id bound to this session [" + expected + "]"));
        }
    }

    final Packet processOutgoingPacket(Packet requestPacket) throws RmException {
        initializeIfNecessary(requestPacket);

        appendSequenceHeader(requestPacket.getMessage());
        if (checkPendingAckRequest()) {
            appendAckRequestedHeader(requestPacket.getMessage());
            lastAckRequestedTime.set(System.currentTimeMillis());
        }
        if (inboundSequenceId != null) {
            // we are always sending acknowledgements if there is an inbound sequence
            appendSequenceAcknowledgementHeader(requestPacket.getMessage());
        }

        return requestPacket;
    }

    final Packet processIncommingPacket(Packet responsePacket, boolean responseToOneWayRequest) throws RmException {
        Message responseMessage = responsePacket.getMessage();
        if (responseMessage != null) {
            processInboundMessageHeaders(responseMessage.getHeaders(), !responseToOneWayRequest && !isProtocolMessage(responseMessage));
        }
// WE DON'T NEED TO TAKE CARE OF SOAP FAULTS HERE... (?)
//                        if (responseMessage != null && responseMessage.isFault()) {
//                            //don't want to resend
//                            //WSRM2004: Marking faulted message {0} as acked.
//                            LOGGER.fine(LocalizationMessages.WSRM_2004_ACKING_FAULTED_MESSAGE(requestMessage.getMessageNumber()));
//                            outboundSequence.acknowledge(requestMessage.getMessageNumber());
//                        }
//
        return responsePacket;
    }

    /**
     * Registers given fiber for a resend (resume)
     * 
     * @param fiber a fiber to be resumed after resend interval has passed
     * @return {@code true} if the fiber was successfully registered; {@code false} otherwise.
     */
    final boolean registerForResend(Fiber fiber, Packet packet) {
        synchronized (fibersToResend) {
            return fibersToResend.offer(new FiberRegistration(fiber, packet));
        }
    }

    /**
     * Closes and terminates associated sequences and releases other resources associated with this RM session
     */
    final void close() {
        try {
            try {
                closeOutboundSequence();
            } catch (RmException ex) {
                LOGGER.logException(ex, Level.WARNING);
            } finally {
                try {
                    sequenceManager.closeSequence(outboundSequenceId);
                } catch (UnknownSequenceException ex) {
                    LOGGER.logException(ex, Level.WARNING);
                }
            }

            try {
                waitUntilAllRequestsAckedOrTimeout();
                terminateOutboundSequence();
            } catch (RmException ex) {
                LOGGER.logException(ex, Level.WARNING);
            } finally {
                try {
                    sequenceManager.terminateSequence(outboundSequenceId);
                } catch (UnknownSequenceException ex) {
                    LOGGER.logException(ex, Level.WARNING);
                }
            }

            if (inboundSequenceId != null && sequenceManager.isValid(inboundSequenceId)) {
                try {
                    if (!sequenceManager.getSequence(inboundSequenceId).isClosed()) {
                        sequenceManager.closeSequence(inboundSequenceId);
                    }
                    // TODO wait for an external event?
                    sequenceManager.terminateSequence(inboundSequenceId);
                } catch (UnknownSequenceException ex) {
                    LOGGER.logException(ex, Level.WARNING);
                }
            }
        } finally {
            scheduledTaskManager.stopAll();
        }
    }

    /**
     * Performs late initialization of sequences and timer task, provided those have not yet been initialized.
     * The actual initialization thus happens only once in the lifetime of each client RM session object.
     */
    private void initializeIfNecessary(Packet requestPacket) throws CreateSequenceException, RmException {
        initLock.lock();
        try {
            if (!isInitialized()) {
                communicator.registerMusterRequestPacket(requestPacket.copy(false));

                int numberOfInitiateSessionAttempts = 0;
                while (true) {
                    try {
                        openRmSession(
                                (configuration.requestResponseOperationsDetected()) ? sequenceManager.generateSequenceUID() : null,
                                communicator.tryStartSecureConversation());
                        break;
                    } catch (RuntimeException ex) {
                        // TODO L10N
                        LOGGER.warning("Attempt to initiate RM session failed with an exception", ex);
                    } finally {
                        if (++numberOfInitiateSessionAttempts > MAX_INITIATE_SESSION_ATTEMPTS) {
                            // TODO L10N
                            throw LOGGER.logSevereException(new CreateSequenceException("Unable to initiate RM Session: Maximum attempts to initiate RM session reached"));
                        }
                    }
                }


                scheduledTaskManager.startTasks(
                        new Runnable() {

                            public void run() {
                                resend();
                            }
                        },
                        new Runnable() {

                            public void run() {
                                sendAckRequested();
                            }
                        });
            }
        } finally {
            initLock.unlock();
        }
    }

    private boolean isInitialized() {
        return outboundSequenceId != null;
    }

    private boolean isProtocolMessage(@NotNull Message responseMessage) {
        HeaderList headers = responseMessage.getHeaders();

        return headers != null && !configuration.getRmVersion().isRMAction(headers.getAction(configuration.getAddressingVersion(), configuration.getSoapVersion()));
    }

    /**
     * Resumes all suspended fibers registered for a resend which have an expired retransmission inteval.
     */
    private void resend() {
        while (!fibersToResend.isEmpty() && fibersToResend.peek().expired(configuration.getMessageRetransmissionInterval())) {
            FiberRegistration registration;
            synchronized (fibersToResend) {
                registration = fibersToResend.poll();
            }
            registration.fiber.resume(registration.packet);
        }
    }

    /**
     * Send Message with empty body and a AckRequestedElement (with Last child) down the pipe.  Process the response,
     * which may contain a SequenceAcknowledgementElement.
     *
     * @param seq Outbound sequence to which SequenceHeaderElement will belong.
     *
     */
    private void sendAckRequested() {
        Message ackResponse = null;
        try {
            if (checkPendingAckRequest()) {
                Message ackRequestMessage = communicator.createEmptyMessage();
                appendAckRequestedHeader(ackRequestMessage);
                lastAckRequestedTime.set(System.currentTimeMillis());

                ackResponse = communicator.send(ackRequestMessage, configuration.getRmVersion().ackRequestedAction);
                if (ackResponse == null) {
                    // TODO L10N
                    throw new RmException("Response for the acknowledgement request is 'null'");
                }

                processInboundMessageHeaders(ackResponse.getHeaders(), false);

                if (ackResponse.isFault()) {
                    // TODO L10N
                    throw new RmException("Acknowledgement request ended in a SOAP fault", ackResponse);
                }
            }
        } catch (RmException ex) {
            // TODO L10N
            LOGGER.warning("Acknowledgement request failed", ex);
        } finally {
            if (ackResponse != null) {
                ackResponse.consume();
            }
        }
    }

    private boolean checkPendingAckRequest() throws UnknownSequenceException {
        return lastAckRequestedTime.get() - System.currentTimeMillis() > configuration.getAcknowledgementRequestInterval() &&
                sequenceManager.getSequence(outboundSequenceId).hasPendingAcknowledgements();
    }

    private void waitUntilAllRequestsAckedOrTimeout() {
        final CountDownLatch doneSignal = new CountDownLatch(1);
        ScheduledFuture<?> taskHandle = scheduledTaskManager.startTask(new Runnable() {

            public void run() {
                try {
                    if (!sequenceManager.getSequence(outboundSequenceId).hasPendingAcknowledgements()) {
                        doneSignal.countDown();
                    }
                } catch (UnknownSequenceException ex) {
                    // TODO L10N
                    LOGGER.severe("Unexpected exception occured while waiting for sequence acknowledgements", ex);
                    doneSignal.countDown();
                }
            }
        });
        try {
            boolean waitResult = doneSignal.await(configuration.getCloseSequenceOperationTimeout(), TimeUnit.MILLISECONDS);
            if (!waitResult) {
                // TODO L10N
                LOGGER.info("Close sequence operation timed out for outbound sequence [" + outboundSequenceId + "]");
            }
        } catch (InterruptedException ex) {
            // TODO L10N
            LOGGER.fine("Got interrupted while waiting for close sequence operation", ex);
        } finally {
            taskHandle.cancel(true);
        }
    }
}
