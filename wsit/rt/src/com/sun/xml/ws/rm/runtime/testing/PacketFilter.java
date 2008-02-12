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
package com.sun.xml.ws.rm.runtime.testing;

import com.sun.xml.ws.api.message.Header;
import com.sun.xml.ws.api.message.HeaderList;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.rm.RmVersion;
import com.sun.xml.ws.rm.localization.RmLogger;
import javax.xml.bind.JAXBException;

/**
 *
 * @author Marek Potociar (marek.potociar at sun.com)
 */
public abstract class PacketFilter {

    protected static final long UNSPECIFIED = -1;
    private static final RmLogger LOGGER = RmLogger.getLogger(PacketFilter.class);
    protected final RmVersion rmVersion;

    protected PacketFilter(RmVersion rmVersion) {
        this.rmVersion = rmVersion;
    }

    /**
     * Method is called during the client-side request packet processing, which means that it is called BEFORE the request 
     * is sent to the service.
     * 
     * @param request original request packet to be filtered
     * 
     * @return filtered packet
     * 
     * @exception java.lang.Exception any exception that may occur during processing.
     */
    public abstract Packet filterClientRequest(Packet request) throws Exception;

    /**
     * Method is called during the server-side response packet processing, which means that it is called BEFORE the response 
     * is sent to the client.
     * 
     * @param response original response packet to be filtered
     * 
     * @return filtered packet
     * 
     * @exception java.lang.Exception any exception that may occur during processing.
     */
    public abstract Packet filterServerResponse(Packet response) throws Exception;

    /**
     * Retrieves RM sequence identifier form the message stored in the packet.
     * 
     * @param packet packet to be checked for the RM sequence identifier
     * 
     * @return RM sequence identifier. May return {@code null} if there is no RM sequence identifier 
     * associated with this packet.
     */
    protected final String getSequenceId(Packet packet) {
        try {
            if (packet == null || packet.getMessage() == null || packet.getMessage().getHeaders() == null) {
                return null;
            }

            HeaderList headers = packet.getMessage().getHeaders();
            switch (rmVersion) {
                case WSRM10: {
                    com.sun.xml.ws.rm.v200502.SequenceElement se = readHeaderAsUnderstood(headers, "Sequence");
                    return (se != null) ? se.getIdentifier().toString() : null;// TODO ???
                }
                case WSRM11: {
                    com.sun.xml.ws.rm.v200702.SequenceElement se = readHeaderAsUnderstood(headers, "Sequence");
                    return (se != null) ? se.getId() : null;
                }
                default:
                    LOGGER.severe("Unsupported RM version [" + rmVersion.namespaceUri + "]");
                    return null;
            }
//            AbstractSequence se = readHeaderAsUnderstood(headers, "Sequence");
//            switch (rmVersion) {
//                case WSRM10: {
//                    return (se != null) ? ((com.sun.xml.ws.rm.v200502.SequenceElement) se).getId() : null;
//                }
//                case WSRM11: {
//                    return (se != null) ? ((com.sun.xml.ws.rm.v200702.SequenceElement) se).getId() : null;
//                }
//                default:
//                    LOGGER.severe("Unsupported RM version [" + rmVersion.namespaceUri + "]");
//                    return null;
//            }
        } catch (Exception ex) {
            LOGGER.warning("Unexpected exception occured", ex);
            return null;
        }
    }

    /**
     * Retrieves RM sequence message identifier form the message stored in the packet.
     * 
     * @param packet packet to be checked for the RM message identifier
     * 
     * @return RM sequence message identifier. May return {@link UNSPECIFIED} if there is no RM message identifier 
     * associated with this packet.
     */
    protected final long getMessageId(Packet packet) {
        try {
            if (packet == null || packet.getMessage() == null || packet.getMessage().getHeaders() == null) {
                return UNSPECIFIED;
            }

            HeaderList headers = packet.getMessage().getHeaders();
            switch (rmVersion) {
                case WSRM10: {
                    com.sun.xml.ws.rm.v200502.SequenceElement se = readHeaderAsUnderstood(headers, "Sequence");
                    return (se != null) ? se.getMessageNumber() : UNSPECIFIED;
                }
                case WSRM11: {
                    com.sun.xml.ws.rm.v200702.SequenceElement se = readHeaderAsUnderstood(headers, "Sequence");
                    return (se != null) ? se.getMessageNumber() : UNSPECIFIED;
                }
                default:
                    LOGGER.severe("Unsupported RM version [" + rmVersion.namespaceUri + "]");
                    return UNSPECIFIED;
            }
//            AbstractSequence se = readHeaderAsUnderstood(headers, "Sequence");
//            switch (rmVersion) {
//                case WSRM10: {
//                    return (se != null) ? ((com.sun.xml.ws.rm.v200502.SequenceElement) se).getNumber() : UNSPECIFIED;
//                }
//                case WSRM11: {
//                    return (se != null) ? ((com.sun.xml.ws.rm.v200702.SequenceElement) se).getNumber() : UNSPECIFIED;
//                }
//                default:
//                    LOGGER.severe("Unsupported RM version [" + rmVersion.namespaceUri + "]");
//                    return UNSPECIFIED;
//            }
        } catch (Exception ex) {
            LOGGER.warning("Unexpected exception occured", ex);
            return UNSPECIFIED;
        }
    }

    /**
     * Utility method which retrieves the RM header with the specified name from the underlying {@link Message}'s 
     * {@link HeaderList) in the form of JAXB element and marks the header as understood.
     * 
     * @param headers list of message headers; must not be {@code null}
     * 
     * @param name the name of the {@link com.sun.xml.ws.api.message.Header} to find.
     * 
     * @return RM header with the specified name in the form of JAXB element or {@code null} in case no such header was found
     */
    private <T> T readHeaderAsUnderstood(HeaderList headers, String name) throws JAXBException {
        Header header = headers.get(rmVersion.namespaceUri, name, false);
        if (header == null) {
            return (T) null;
        }

        return (T) header.readAsJAXB(rmVersion.jaxbUnmarshaller);
    }
}
