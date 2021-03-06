/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.messaging.jmq.jmsserver.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.util.JMQXid;

public class LocalTransaction extends BaseTransaction {

	public LocalTransaction() {
		super(BaseTransaction.LOCAL_TRANSACTION_TYPE);
		

	}
	
	public LocalTransaction(TransactionUID id, int state, JMQXid xid,
			TransactionWork txnWork) {
		super(BaseTransaction.LOCAL_TRANSACTION_TYPE);

		setTransactionWork(txnWork);
		transactionDetails.setTid(id);
		transactionDetails.setState(state);
		transactionDetails.setXid(xid);
	}

	

	public void readData(DataInputStream dis) throws IOException,
			BrokerException {
		transactionDetails.readContent(dis);
		if(transactionWork==null)
			transactionWork = new TransactionWork();
		transactionWork.readWork(dis);
	}

	public void readObjects(ObjectInputStream ois) throws IOException,
			ClassNotFoundException {
		
		transactionState = (TransactionState) ois.readObject();

		// need to reset state
		try {
			transactionState.setState(transactionDetails.getState());
		} catch (BrokerException e) {
			e.printStackTrace();
			throw new IOException(e.getMessage());
		}

	}

	
	public void writeData(DataOutputStream dos) throws IOException {
		transactionDetails.writeContent(dos);
		transactionWork.writeWork(dos);
	}

	public void writeObjects(ObjectOutputStream oos) throws IOException {
			oos.writeObject(transactionState);
	}

	String getPrefix() {
		return "LocalTransaction: " + Thread.currentThread().getName() + " "
				+ this.getTid();
	}

}
