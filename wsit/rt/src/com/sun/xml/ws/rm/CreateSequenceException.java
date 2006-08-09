/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.xml.ws.rm;

/**
 * Subclass of <code>RMException</code> thrown from errors resulting
 *  when a response to create sequence request cannot be satisfied
 * @author Bhakti Mehta
 *
 */
public class CreateSequenceException extends RMException {

    private  String sequenceId;
    /**
     */
    public CreateSequenceException() {
        super();
    }

    public CreateSequenceException(String message) {
        super(message);
    }

    public CreateSequenceException(String message,String id){
        super(message);
        this.sequenceId = id;
    }

    public CreateSequenceException (String s, com.sun.xml.ws.api.message.Message message) {
        super (s,message);
    }

     public CreateSequenceException(Throwable e) {
         super(e);
    }

    public String getSequenceId() {
        return sequenceId;
    }
}
