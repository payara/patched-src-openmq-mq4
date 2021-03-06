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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.messaging.jmq.io.SysMessageID;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.core.BrokerAddress;
import com.sun.messaging.jmq.jmsserver.core.ConsumerUID;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.core.DestinationUID;
import com.sun.messaging.jmq.jmsserver.core.PacketReference;
import com.sun.messaging.jmq.jmsserver.core.cluster.RemoteTransactionAckEntry;
import com.sun.messaging.jmq.jmsserver.persist.Store;
import com.sun.messaging.jmq.jmsserver.persist.TransactionInfo;
import com.sun.messaging.jmq.jmsserver.persist.file.FileStore;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.util.JMQXid;
import com.sun.messaging.jmq.util.log.Logger;

public class ToTxnLogConverter {

	static Logger logger = Globals.getLogger();

	public static boolean DEBUG = false;
	static {
		if (Globals.getLogger().getLevel() <= Logger.DEBUG)
			DEBUG = true;
	}

	public static void convertToTxnLogFormat(TransactionList transactionList,
			FileStore store) throws BrokerException {

		Map translist = transactionList.getTransactionListMap();
		convertTxnList(translist.values(), transactionList, store);

		Map remoteTranslist = transactionList.getRemoteTransactionListMap();
		convertTxnList(remoteTranslist.values(), transactionList, store);

	}

	static void convertTxnList(Collection txlist,
			TransactionList transactionList, FileStore store)
			throws BrokerException {
		if (DEBUG) {
			logger.log(Logger.DEBUG, getPrefix() + " convertTxnList  "
					+ txlist.size());
		}
		Iterator<TransactionInformation> txIter = txlist.iterator();
		while (txIter.hasNext()) {
			TransactionInformation txnInfo = txIter.next();
			int type = txnInfo.getType();
			LocalTxnConverter localConverter = new LocalTxnConverter(
					transactionList, store);
			ClusterTxnConverter clusterConverter = new ClusterTxnConverter(
					transactionList, store);
			RemoteTxnConverter remoteConverter = new RemoteTxnConverter(
					transactionList, store);
			switch (type) {
			case TransactionInfo.TXN_LOCAL:
				localConverter.convert(txnInfo);
				break;
			case TransactionInfo.TXN_CLUSTER:
				clusterConverter.convert(txnInfo);
				break;
			case TransactionInfo.TXN_REMOTE:
				remoteConverter.convert(txnInfo);
				break;
			default: {
				String msg = getPrefix()
						+ "convertToTxnLogFormat: unknown transaction type "
						+ type + " for " + txnInfo;
				logger.log(Logger.ERROR, msg);
			}
			}

		}
	}

	private static String getPrefix() {
		return Thread.currentThread() + " ToTxnLogConverter.";
	}

}

class TxnConverter {
	TransactionList transactionList;
	Store store;
	static Logger logger = Globals.getLogger();

	TxnConverter(TransactionList transactionList, Store store) {
		this.transactionList = transactionList;
		this.store = store;
	}

	String getPrefix() {
		return Thread.currentThread() + " ToTxnLogConverter.TxnConverter.";
	}

	void getSentMessages(TransactionInformation txnInfo, TransactionWork txnWork)
			throws BrokerException {
		// get messages for this txn
		List<SysMessageID> sentMessageIds = txnInfo.getPublishedMessages();
		Iterator<SysMessageID> msgIter = sentMessageIds.iterator();
		while (msgIter.hasNext()) {
			SysMessageID mid = msgIter.next();
			PacketReference packRef = Destination.get(mid);
			if (packRef == null) {
				String msg = getPrefix()
						+ " convertLocalToTxnLogFormat: can not find packet for sent msg "
						+ mid + " in txn " + txnInfo;
				logger.log(Logger.WARNING, msg);
			} else {

				DestinationUID destUID = packRef.getDestination()
						.getDestinationUID();
				ConsumerUID[] interests = store.getConsumerUIDs(destUID, mid);
				TransactionWorkMessage twm = new TransactionWorkMessage();
				twm.setStoredInterests(interests);
				twm.setPacketReference(packRef);
				twm.setDestUID(destUID);
				txnWork.addMesage(twm);

			}
		}
	}

	void getConsumedMessages(TransactionInformation txnInfo,
			TransactionWork txnWork) throws BrokerException {
		// get acks for this txn
		LinkedHashMap<SysMessageID, List<ConsumerUID>> cm = txnInfo
				.getConsumedMessages(false);
		Iterator<Map.Entry<SysMessageID, List<ConsumerUID>>> iter = cm
				.entrySet().iterator();

		HashMap cuidToStored = transactionList
				.retrieveStoredConsumerUIDs(txnInfo.tid);

		while (iter.hasNext()) {
			Map.Entry<SysMessageID, List<ConsumerUID>> entry = iter.next();
			SysMessageID mid = entry.getKey();
			List<ConsumerUID> consumers = entry.getValue();

			PacketReference packRef = Destination.get(mid);
			if (packRef == null) {
				String msg = getPrefix()
						+ " convertLocalToTxnLogFormat: can not find packet for consumed msg"
						+ mid + " in txn " + txnInfo;
				logger.log(Logger.WARNING, msg);
			} else {
				DestinationUID destUID = packRef.getDestination()
						.getDestinationUID();
				if (consumers != null) {
					for (int i = 0; i < consumers.size(); i++) {
						ConsumerUID cid = consumers.get(i);
						ConsumerUID storedcid = (ConsumerUID) cuidToStored
								.get(cid);
						if (storedcid == null) {
							if (ToTxnLogConverter.DEBUG) {
								String msg = getPrefix()
										+ " storedcid=null for " + cid;
								logger.log(Logger.DEBUG, msg);
							}
							storedcid = cid;
						}
						TransactionWorkMessageAck twma = new TransactionWorkMessageAck(
								destUID, mid, storedcid);
						if (ToTxnLogConverter.DEBUG) {
							String msg = getPrefix()
									+ " convertLocalToTxnLogFormat: converting messageAck:"
									+ " mid=" + mid + " destID=" + destUID
									+ " consumerID=" + cid + " storedCid="
									+ storedcid + " txid=" + txnInfo.tid;
							logger.log(Logger.DEBUG, msg);
						}
						txnWork.addMessageAcknowledgement(twma);

					}
				}

			}

		}
	}

	void deleteSentMessagesFromStore(TransactionWork txnWork)
			throws BrokerException {

		// now delete any sent messages from store ( they are stored in txn
		// log or prepared msg store)
		Iterator<TransactionWorkMessage> sent = txnWork.getSentMessages()
				.iterator();
		while (sent.hasNext()) {
			TransactionWorkMessage twm = sent.next();
			DestinationUID duid = twm.getDestUID();
			SysMessageID mid = twm.getMessage().getSysMessageID();
			try {
				store.removeMessage(duid, mid, true);
			} catch (IOException e) {
				String msg = "Could not remove transacted sent message during txn conversion";
				logger.logStack(Logger.ERROR, msg, e);
			}
		}
	}

}

class LocalTxnConverter extends TxnConverter {

	LocalTxnConverter(TransactionList transactionList, Store store) {
		super(transactionList, store);
	}

	String getPrefix() {
		return Thread.currentThread() + " ToTxnLogConverter.LocalTxnConverter.";
	}

	void convert(TransactionInformation txnInfo) throws BrokerException {
		if (ToTxnLogConverter.DEBUG) {
			logger.log(Logger.DEBUG, getPrefix()
					+ " convertLocalToTxnLogFormat " + txnInfo);
		}
		// should be a prepared transaction
		int state = txnInfo.getState().getState();
		if (state != TransactionState.PREPARED) {
			String msg = getPrefix()
					+ " convertLocalToTxnLogFormat: ignoring state  " + state
					+ " for " + txnInfo;
			logger.log(Logger.INFO, msg);
		}
		TransactionWork txnWork = new TransactionWork();

		getSentMessages(txnInfo, txnWork);
		getConsumedMessages(txnInfo, txnWork);

		TransactionUID txid = txnInfo.getTID();
		JMQXid xid = txnInfo.getState().getXid();
		LocalTransaction localTxn = new LocalTransaction(txid, state, xid,
				txnWork);
		TransactionState newState = new TransactionState(txnInfo.getState());

		localTxn.setTransactionState(newState);

		store.logTxn(localTxn);
		deleteSentMessagesFromStore(txnWork);

	}

}

class ClusterTxnConverter extends TxnConverter {

	ClusterTxnConverter(TransactionList transactionList, Store store) {
		super(transactionList, store);
	}

	String getPrefix() {
		return Thread.currentThread()
				+ " ToTxnLogConverter.ClusterTxnConverter.";
	}

	void convert(TransactionInformation txnInfo) throws BrokerException {
		if (ToTxnLogConverter.DEBUG) {
			logger.log(Logger.DEBUG, getPrefix()
					+ " convertClusterToTxnLogFormat " + txnInfo);
		}
		// should be a prepared transaction
		int state = txnInfo.getState().getState();
		if (state != TransactionState.PREPARED) {
			String msg = getPrefix()
					+ " convertClusterToTxnLogFormat: unknown state  " + state
					+ " for " + txnInfo;
			logger.log(Logger.ERROR, msg);
		}
		TransactionWork txnWork = new TransactionWork();

		getSentMessages(txnInfo, txnWork);
		getConsumedMessages(txnInfo, txnWork);

		TransactionUID txid = txnInfo.getTID();
	
		TransactionState newState = new TransactionState(txnInfo.getState());
		TransactionBroker[] tbas = txnInfo.getClusterTransactionBrokers();
		ClusterTransaction clusterTxn = new ClusterTransaction(txid, newState,
				txnWork, tbas);

		store.logTxn(clusterTxn);
		deleteSentMessagesFromStore(txnWork);

	}

}

class RemoteTxnConverter extends TxnConverter {

	RemoteTxnConverter(TransactionList transactionList, Store store) {
		super(transactionList, store);
	}

	String getPrefix() {
		return Thread.currentThread()
				+ " ToTxnLogConverter.RemoteTxnConverter.";
	}

	void convert(TransactionInformation txnInfo) throws BrokerException {
		if (ToTxnLogConverter.DEBUG) {
			logger.log(Logger.DEBUG, getPrefix() + " convert " + txnInfo);
		}

		// should be a prepared transaction
		int state = txnInfo.getState().getState();
		if (state != TransactionState.PREPARED) {
			String msg = getPrefix() + " convert: unknown state  " + state
					+ " for " + txnInfo;
			logger.log(Logger.ERROR, msg);
		}

		TransactionUID txid = txnInfo.getTID();

		TransactionState newState = new TransactionState(txnInfo.getState());
		RemoteTransactionAckEntry[] rtaes = transactionList
				.getRecoveryRemoteTransactionAcks(txid);

		if (rtaes != null) {

			ArrayList<TransactionAcknowledgement> al = new ArrayList<TransactionAcknowledgement>();
			for (int i = 0; i < rtaes.length; i++) {
				RemoteTransactionAckEntry rtae = rtaes[i];
				TransactionAcknowledgement[] txnAcks = rtae.getAcks();
				for (int j = 0; j < txnAcks.length; j++) {
					al.add(txnAcks[j]);
				}
			}

			TransactionAcknowledgement[] txnAcks = al
					.toArray(new TransactionAcknowledgement[0]);

			DestinationUID[] destIds = new DestinationUID[txnAcks.length];
			for (int i = 0; i < txnAcks.length; i++) {
				SysMessageID sid = txnAcks[i].getSysMessageID();
				PacketReference p = Destination.get(sid);
				DestinationUID destID = null;
				if (p != null) {
					destID = p.getDestinationUID();
				} else {
					logger.log(Logger.WARNING, "Could not find packet for "
							+ sid);
				}
				destIds[i] = destID;
			}
			TransactionBroker txnBroker = transactionList
					.getRemoteTransactionHomeBroker(txid);
			BrokerAddress txnHomeBroker = txnBroker.getBrokerAddress();

			RemoteTransaction remoteTxn = new RemoteTransaction(txid, newState,
					txnAcks, destIds, txnHomeBroker);

			store.logTxn(remoteTxn);
		} else {
			logger.log(Logger.ERROR,
					"Could not find RemoteTransactionAckEntry for " + txid);
		}

	}

}
