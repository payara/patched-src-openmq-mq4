/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
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

/*
 * @(#)JMSXASession.java	1.5 06/28/07
 */ 

package com.sun.jms.spi.xa;

import javax.jms.*;
import javax.transaction.xa.XAResource;

/** XASession provides access to a JMS provider's support for JTA (optional) 
  * and the associated Session. This support takes the form of a 
  * <CODE>javax.transaction.xa.XAResource</CODE> object. The functionality of 
  * this object closely resembles that defined by the standard X/Open XA 
  * Resource interface.
  *
  * <P>An application server controls the transactional assignment of an 
  * JMSXASession by obtaining its XAResource. It uses the XAResource to assign 
  * the session to a transaction; prepare and commit work on the
  * transaction; etc.
  *
  * <P>An XAResource provides some fairly sophisticated facilities for 
  * interleaving work on multiple transactions; recovering a list of 
  * transactions in progress; etc. A JTA aware JMS provider must fully 
  * implement this functionality. This could be done by using the services 
  * of a database that supports XA or a JMS provider may choose to implement 
  * this functionality from scratch.
  *
  * <P>A client of the application server is given what it thinks is a 
  * regular JMS Session. Behind the scenes, the application server controls 
  * the transaction management of the underlying JMSXASession.
  * 
  * @see         com.sun.jms.xa.spi.JMSXAQueueSession
  * @see         com.sun.jms.xa.spi.JMSXATopicSession
  */ 
 
public interface JMSXASession {

    /** Return an XA resource to the caller.
      *
      * @return an XA resource to the caller
      */

     XAResource
     getXAResource();


    /*
     * return a Session associated with this XASession object.
     *
     * @return a Session to the caller
     *  
     * @exception JMSException if a JMS error occurs.
     */

    Session getSession() throws JMSException;
    
    /*
     * close the JMSXASession.
     *
     * @exception JMSException if a JMS error occurs.
     */
    void close() throws JMSException;
}
