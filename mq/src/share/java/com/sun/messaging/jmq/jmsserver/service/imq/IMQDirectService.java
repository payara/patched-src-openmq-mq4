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
 * @(#)IMQDirectService.java	1.57 06/29/07
 */ 

package com.sun.messaging.jmq.jmsserver.service.imq;

import java.io.*;
import java.util.*;
import java.security.AccessControlException;
import javax.transaction.xa.Xid;

import com.sun.messaging.jmq.util.JMQXid;
import com.sun.messaging.jmq.util.GoodbyeReason;
import com.sun.messaging.jmq.util.ServiceState;
import com.sun.messaging.jmq.util.ServiceType;
import com.sun.messaging.jmq.util.UniqueID;
import com.sun.messaging.jmq.util.UID;
import com.sun.messaging.jmq.util.DestType;
import com.sun.messaging.jmq.util.log.Logger;
import com.sun.messaging.jmq.jmsserver.service.*;
import com.sun.messaging.jmq.jmsserver.util.*;
import com.sun.messaging.jmq.jmsserver.util.pool.*;
import com.sun.messaging.jmq.jmsserver.core.Session;
import com.sun.messaging.jmq.jmsserver.core.SessionUID;
import com.sun.messaging.jmq.jmsserver.core.ConsumerUID;
import com.sun.messaging.jmq.jmsserver.core.ProducerUID;
import com.sun.messaging.jmq.jmsserver.auth.AuthCacheData;
import com.sun.messaging.jmq.jmsserver.auth.AccessController;
import com.sun.messaging.jmq.jmsserver.data.PacketRouter;
import com.sun.messaging.jmq.jmsserver.data.PacketRouter;
import com.sun.messaging.jmq.jmsserver.data.TransactionUID;
import com.sun.messaging.jmq.jmsserver.data.AutoRollbackType;
import com.sun.messaging.jmq.jmsserver.data.handlers.AckHandler;
import com.sun.messaging.jmq.jmsserver.data.protocol.Protocol;
import com.sun.messaging.jmq.jmsserver.core.PacketReference;
import com.sun.messaging.jmq.util.lists.*;
import com.sun.messaging.jmq.util.selector.Selector;
import com.sun.messaging.jmq.util.selector.SelectorFormatException;
import com.sun.messaging.jmq.jmsserver.net.*;
import com.sun.messaging.jmq.io.Packet;
import com.sun.messaging.jmq.io.Status;
import com.sun.messaging.jmq.io.PacketType;
import com.sun.messaging.jmq.io.SysMessageID;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.config.*;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.management.agent.Agent;
import com.sun.messaging.jmq.jmsservice.*;

public class IMQDirectService extends IMQService implements JMSService
{

    private static boolean DEBUG = false;

    private boolean acEnabled = false;

    Map	localConnectionList = Collections.synchronizedMap(new HashMap());
    Map		queueBrowseList = Collections.synchronizedMap(new HashMap());
    Protocol	protocol;



    protected ThreadPool pool = null;


    /**
     * the list of connections which this service knows about
     * XXX make this accessible from IMQService ??
     */
    private ConnectionManager connectionList = Globals.getConnectionManager();

    private AuthCacheData authCacheData = new AuthCacheData();
    protected Thread listenThread = null;
 
    HashMap serviceprops = null;
    
    public IMQDirectService(String name, int type, int min, int max, boolean acc) 
    {
	    super(name, type);
	    protocol = Globals.getProtocol();

        acEnabled = acc;

        // create a thread pool for the threading off of the forwarding
        // task
        OperationRunnableFactory runfac =new OperationRunnableFactory(true /* blocking */); 
        pool = new ThreadPool(name,min,max,runfac);
    }


    // XXX - this implemenetation assumes that we have 1 distinct
    // listen thread per/service
    // revisit  (may not always be true)

    public synchronized  void startService(boolean startPaused) {
        if (isServiceRunning() || listenThread != null) {
            /*
             * this error should never happen in normal operation
             * if its does, we will need to add an error message 
             * to the resource bundle
             */
            logger.log(Logger.DEBUG, 
                       BrokerResources.E_INTERNAL_BROKER_ERROR, 
                       "unable to start service, already started.");
            return;
        }
        setState(ServiceState.STARTED);
        {
            String args[] = { getName(), 
                              "",
                              String.valueOf(getMinThreadpool()),
                              String.valueOf(getMaxThreadpool()) };
            logger.log(Logger.INFO, BrokerResources.I_SERVICE_START, args);
            try {
            logger.log(Logger.INFO, BrokerResources.I_SERVICE_USER_REPOSITORY,
                       AccessController.getInstance(
                       getName(), getServiceType()).getUserRepository(),
                       getName());
            } catch (BrokerException e) {
            logger.log(Logger.WARNING, 
                       BrokerResources.W_SERVICE_USER_REPOSITORY,
                       getName(), e.getMessage());
            }
        }
     
        if (startPaused) {
            setServiceRunning(false);
            setState(ServiceState.PAUSED);
	    /*
	     * CHECK: This used to be if'd by WORKAROUND_HTTP
	     */
                try {
                    Globals.getPortMapper().updateServicePort(name, 0);
                } catch (Exception ex) {
                    logger.logStack(Logger.WARNING, 
                          BrokerResources.E_INTERNAL_BROKER_ERROR, 
                          "starting paused service " + this, ex);
                }
        } else {
            setServiceRunning(true);
            setState(ServiceState.RUNNING);
        }
        notify();
    }

    public void stopService(boolean all) { 
        synchronized (this) {

            if (isShuttingDown()) {
                // we have already been called
                return;
            }

            String strings[] = { getName(), "" };
            if (all) {
                logger.log(Logger.INFO, 
                           BrokerResources.I_SERVICE_STOP, 
                           strings);
            } else if (!isShuttingDown()) {
                logger.log(Logger.INFO, 
                           BrokerResources.I_SERVICE_SHUTTINGDOWN, 
                           strings);
            } 
           
	    setShuttingDown(true);
        }

        if (this.getServiceType() == ServiceType.NORMAL) {
            List cons = connectionList.getConnectionList(this);
            Connection con = null;
            for (int i = cons.size()-1; i >= 0; i--) {
                con = (Connection)cons.get(i);
                con.stopConnection();
            }
        }

        synchronized (this) {
            setState(ServiceState.SHUTTINGDOWN);
            this.notify();
        }

        if (!all) {
            return;
        }

        // set operation state to EXITING
        if (this.getServiceType() == ServiceType.NORMAL) {
            List cons = connectionList.getConnectionList(this);
            Connection con = null;
            for (int i = cons.size()-1; i >= 0; i--) {
                con = (Connection)cons.get(i);
                con.destroyConnection(true, GoodbyeReason.SHUTDOWN_BKR, 
                    Globals.getBrokerResources().getKString(
                        BrokerResources.M_SERVICE_SHUTDOWN));
            }
        }

        synchronized (this) {
            setState(ServiceState.STOPPED);
            this.notify();
        }

        if (DEBUG) {
            logger.log(Logger.DEBUG, 
                "Destroying Service {0}", getName());
        }
    }

    public void pauseService(boolean all) {

        if (!isServiceRunning()) {
            logger.log(Logger.DEBUG, 
                    BrokerResources.E_INTERNAL_BROKER_ERROR, 
                    "unable to pause service " 
                    + name + ", already paused.");
            return;
        }  

        String strings[] = { getName(), "" };
        logger.log(Logger.DEBUG, BrokerResources.I_SERVICE_PAUSE, strings);

        try {
            stopNewConnections();
        } catch (Exception ex) {
            logger.logStack(Logger.WARNING, 
                      BrokerResources.E_INTERNAL_BROKER_ERROR, 
                      "pausing service " + this, ex);
        }
        setState(ServiceState.PAUSED);

        setServiceRunning(false);
    }

    public void resumeService() {
        if (isServiceRunning()) {
             logger.log(Logger.DEBUG, 
                    BrokerResources.E_INTERNAL_BROKER_ERROR, 
                    "unable to resume service " 
                    + name + ", already running.");
           return;
        }
        String strings[] = { getName(), "" };
        logger.log(Logger.DEBUG, BrokerResources.I_SERVICE_RESUME, strings);
        try {
            startNewConnections();
        } catch (Exception ex) {
            logger.logStack(Logger.WARNING, 
                      BrokerResources.E_INTERNAL_BROKER_ERROR, 
                      "pausing service " + this, ex);
        }
        setServiceRunning(true);

        synchronized (this) {
            setState(ServiceState.RUNNING);
            this.notify();
        }
        
    }

    public void updateService(int port, int min, int max) 
        throws IOException, PropertyUpdateException, BrokerException
    {
        Globals.getPortMapper().updateServicePort(name, 0);
        Globals.getPortMapper().removeService(name);
        Globals.getPortMapper().addService(name, "none", 
        	Globals.getConfig().getProperty(
        	IMQDirectServiceFactory.SERVICE_PREFIX + 
        	name + ".servicetype"),
        	0, getServiceProperties());
    }

    public AuthCacheData getAuthCacheData() {
        return authCacheData;     
    }  

    public IMQConnection createDirectConnection(String username, String password) 
		throws BrokerException {
	IMQDirectConnection con = null;
	/*
	System.err.println("#### CREATE DIRECT CXN: username: " 
		+ username
		+ ", passwd: "
		+ password);
	*/

	con = new IMQDirectConnection(this);
	connectionList.addConnection(con);
	localConnectionList.put(con.getConnectionUID(), con);

	try  {
	    con.authenticate(username, password);
	    /*
	    System.err.println("#### CREATE DIRECT CXN: AUTH SUCCESSFULL");
	    System.err.println("#### DIRECT CXN authenticated name: " + con.getAuthenticatedName());
	    */
	} catch(Exception e)  {
	    e.printStackTrace();
	    String errStr = "Authentication failed for username " 
			+ username
			+ " in service "
			+ getName()
			+ ": " 
			+ e;
	    logger.log(Logger.WARNING, errStr);
	    logger.log(Logger.WARNING, BrokerResources.W_LOGIN_FAILED, e);
	    connectionList.removeConnection(con.getConnectionUID(),
			       true, GoodbyeReason.CON_FATAL_ERROR, errStr);
	    localConnectionList.remove(con.getConnectionUID());

	    throw new SecurityException(errStr);
	}

	Agent agent = Globals.getAgent();
	if (agent != null)  {
	    agent.registerConnection(con.getConnectionUID().longValue());
	    agent.notifyConnectionOpen(con.getConnectionUID().longValue());
	}

	/*
	System.err.println("#### CREATE DIRECT CXN: returning: " + con);
	System.err.println("#### CREATE DIRECT CXN: service from cxn info: " + con.getConnectionInfo().service);
	*/

	return (con);
    }

    public void removeConnection(ConnectionUID uid, int reason, String str) {
    /*
    System.err.println(">>>>>>>>> IMQDirectConnection: REMOVE CONNECTION: " + uid);
    */
         connectionList.removeConnection(uid,
             true, reason, str);
	 localConnectionList.remove(uid);
    }

    public boolean isDirect() {
        return (true);
    }

    /*
     * BEGIN Interface JMSService
     */

    /**
     *  Return an Identifier for the JMSService.<br>
     *  The identification string returned should enable a user to identify the
     *  specific broker instance that this JMSService is communicating with
     *  from a log message that contains this Identifier string.<br>
     *  for example -- {@literal <hostname>:<primary_port>:<servicename>}
     *
     *  @return The JMSServiceID string that identifies the broker address and
     *          servicename that is being used.
     */
    public String getJMSServiceID()  {
	return (getName());
    }

    /**
     *  Create a connection with the service.
     *
     *  @param  username    The identity with which to establish the connection
     *  @param  password    The password for the identity
     *  @param  ctx         The JMSServiceBootStrapContext to use for broker
     *                      thread resources etc.
     *
     *  @return the JMSServiceReply of the request to create the connection
     *
     *  @see JMSServiceReply#getJMQVersion
     *  @see JMSServiceReply#getJMQConnectionID
     *  @see JMSServiceReply#getJMQHA
     *  @see JMSServiceReply#getJMQClusterID
     *  @see JMSServiceReply#getJMQMaxMsgBytes
     *
     *  @throws JMSServiceException If the Status returned for the
     *          createConnection method is not {@link JMSServiceReply.Status#OK}
     */
    public JMSServiceReply createConnection(String username, String password,
                        JMSServiceBootStrapContext ctx) 
					throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
        IMQConnection cxn = null;

	try  {
            cxn = createDirectConnection(username, password);
	} catch (BrokerException be)  {
	    String errStr = "Failed to create direct connection for user: "
			+ username;
				
            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQConnectionID", cxn.getConnectionUID().longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Destroy a connection.
     *
     *  @param connectionId The Id of the connection to destroy
     *
     *  @return The JMSServiceReply of the request to destroy the connection
     *
     *  @throws JMSServiceException If the Status returned for the
     *          destroyConnection method is not
     *          {@link JMSServiceReply.Status#OK}
     */
    public JMSServiceReply destroyConnection(long connectionId) throws JMSServiceException {
		IMQConnection cxn;
		HashMap props = new HashMap();
		JMSServiceReply reply;

		if (getState() == ServiceState.STOPPED) {
			// too late to do anything
		} else {

			cxn = checkConnectionId(connectionId, "destroyConnection");

			/*
			 * Remove reference to connection in local list 
			 * XXX: TBD - Should really call removeConnection() here
			 */
			localConnectionList.remove(cxn.getConnectionUID());

			cxn.destroyConnection(false /* no reply */, GoodbyeReason.CLIENT_CLOSED, Globals.getBrokerResources()
					.getKString(BrokerResources.M_CLIENT_SHUTDOWN));

		}

		props.put("JMQStatus", JMSServiceReply.Status.OK);
		reply = new JMSServiceReply(props, null);
		return (reply);
	}

    /**
     *  Generate a set of Unique IDs.<br>
     *  Each Unique ID generated has the following properties:
     *    <UL>
     *       <LI>It will stay unique for a very long time (years)</LI>
     *       <LI>It will be unique across all other IDs gneerated in the Broker
     *           (ConnectionID, ConsumerID, TransactionID</LI>
     *       <LI>It will be unique acrosss all brokers in the cluster</LI>
     *    </UL>
     *
     *  @param  connectionId The Id of the connection
     *  @param  quantity The number of Unique IDs to generate
     *
     *  @return An array of size 'quantity' of long Unique IDs 
     */
    public long[] generateUID(long connectionId, int quantity) 
    		throws JMSServiceException  {
	IMQConnection  cxn = null;
	HashMap props = new HashMap();
	long	ids[];

        cxn = checkConnectionId(connectionId, "generateUID");

	if (quantity < 0)  {
	    String errStr = "generateUID: quantity is less than 0: "
			+ quantity;

            logger.log(Logger.ERROR, errStr);

	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, props);
	}

	if (quantity == 0)  {
	    return (null);
	}

	ids = new long [ quantity ];
	short prefix = UID.getPrefix();
	for (int i = 0; i < quantity; i++) {
	    ids[i] = UniqueID.generateID(prefix);
	}

	return (ids);
    }

    /**
     *  Generate a Unique ID<br>
     *  The Unique ID generated has the following properties:
     *    <UL>
     *       <LI>It will stay unique for a very long time (years)</LI>
     *       <LI>It will be unique across all other IDs gneerated in the Broker
     *           (ConnectionID, ConsumerID, TransactionID</LI>
     *       <LI>It will be unique acrosss all brokers in the cluster</LI>
     *    </UL>
     *
     *  @param  connectionId The Id of the connection
     *
     *  @return The Unique ID
     */
    public long generateUID(long connectionId) throws JMSServiceException  {
	long oneId[] = generateUID(connectionId, 1);

	return (oneId[0]);
    }

    /**
     *  Set the clientId for a connection.
     *
     *  @param connectionId The Id of the connection to set the clientId on
     *  @param clientId The clientId to be set
     *  @param shareable If <code>true</code> then the clientId can be shared
     *                   with other connections.
     *                   If <code>false</code>, it cannot be shared.
     *  @param nameSpace The scope for clientId sharing.<p>
     *                   The server must ensure that all clientId requests
     *                   within a single namespace are unique.
     *
     *  @return The JMSServiceReply of the request to set the clientId on the
     *          connection.
     *
     *  @throws JMSServiceException if the Status returned for the setClientId
     *          method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     */
    public JMSServiceReply setClientId(long connectionId, String clientId,
                    boolean shareable, String nameSpace) 
				throws JMSServiceException  {
	IMQConnection cxn = null;
	JMSServiceReply reply;
	HashMap props = new HashMap();

        cxn = checkConnectionId(connectionId, "setClientId");

	try  {
	    protocol.setClientID(cxn, clientId, nameSpace, shareable);
	} catch(BrokerException be)  {
	    String errStr = "setClientId: set client ID failed. Connection ID: "
			+ connectionId
			+ ", Client ID: "
			+ clientId
			+ ", Shareable: "
			+ shareable
			+ ", nameSpace: "
			+ nameSpace;

            logger.logStack(Logger.ERROR, errStr, be);

	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);
	return (reply);
    }
    
    /**
     *  Unset the clientId for a connection.
     *
     *  @param connectionId The Id of the connection whose clientId is to be unset
     *
     *  @return The JMSServiceReply of the request to unset the clientId on the
     *          connection.
     *
     *  @throws JMSServiceException if the Status returned for the unsetClientId
     *          method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply unsetClientId(long connectionId) 
			throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "unsetClientId");

	try  {
	    /*
	     * Passing null clientId means unset it.
	     */
	    protocol.setClientID(cxn, null, null, false);
	} catch(BrokerException be)  {
	    String errStr = "unsetClientId: unset client ID failed. Connection ID: "
			+ connectionId;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Start messge delivery for a connection.<p>
     *  Message delivery is threaded per session with all the consumers for a
     *  single session  being called using the same thread.
     *
     *  @param connectionId The Id of the connection on which to start delivery
     *
     *  @return The JMSServiceReply of the request to start the connection.
     *
     *  @throws JMSServiceException if the Status returned for the
     *          startConnection method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply startConnection(long connectionId)
                throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props;
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "startConnection");
	protocol.resumeConnection(cxn);

	/*
	 * TBD Verify!
         */
        SessionListener.startConnection(cxn);

	props = new HashMap();
	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Stop message delivery for a connection.
     *
     *  @param connectionId The Id of the connection on which to stop delivery
     *
     *  @return The JMSServiceReply of the request to stop the connection.
     *
     *  @throws JMSServiceException if the Status returned for the
     *          stopConnection method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply stopConnection(long connectionId)
    				throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props;
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "stopConnection");

	protocol.pauseConnection(cxn);

	/*
	 * TBD Verify!
         */
        SessionListener.stopConnection(cxn);

	props = new HashMap();
	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }


    /**
     *  Create a session within a connection.
     *
     *  @param connectionId The Id of the connection in which the session is to
     *                      be created
     *  @param ackMode The acknowledgement mode of the session to be created
     *
     *  @return The JMSServiceReply of the request to create a session. The Id
     *          of the session is obtained from the JMSServiceReply
     *
     *  @throws JMSServiceException if the Status returned for the
     *          createSession method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *  @see JMSServiceReply#getJMQSessionID
     */

    public JMSServiceReply createSession(long connectionId, SessionAckMode ackMode)
				throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	long sessionID = 0;

        cxn = checkConnectionId(connectionId, "createSession");

	try  {
	    int brokerAckMode;

	    brokerAckMode = convertToBrokerAckMode(ackMode);

	    Session s = protocol.createSession(brokerAckMode, cxn);
        new SessionListener(this, s); // create a thread 
	    sessionID = s.getSessionUID().longValue();
	} catch(BrokerException be)  {
	    String errStr = "createSession: create session failed. Connection ID: "
			+ connectionId
			+ ", acknowledge mode: "
			+ ackMode;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQSessionID", sessionID);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }
    
    /**
     *  Destroy a session.
     *
     *  @param connectionId The Id of the connection
     *  @param sessionId The Id of the session to be destroyed
     *
     *  @return The JMSServiceReply of the request to destroy the session
     *
     *  @throws JMSServiceException If the Status returned for the
     *          destroySession method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     */
    public JMSServiceReply destroySession(long connectionId, long sessionId)
    		throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	SessionUID sUID;

        cxn = checkConnectionId(connectionId, "destroySession");

	sUID = new SessionUID(sessionId);

	try  {
	    protocol.destroySession(sUID, cxn);

        // let the session listener go
       SessionListener.getListener(sUID).destroy();
	} catch(BrokerException be)  {
	    String errStr = "destroySession: destroy session failed. Connection ID: "
			+ connectionId
			+ ", Session ID: "
			+ sessionId;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Start messge delivery for a session.<p>
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session on which to start delivery
     *
     *  @return The JMSServiceReply of the request to start the session.
     *
     *  @throws JMSServiceException if the Status returned for the
     *          startSession method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply startSession(long connectionId, long sessionId)
                throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	SessionUID sUID;
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "startSession");
	sUID = new SessionUID(sessionId);

	try  {
		// CR 6935676
	    //protocol.resumeSession(sUID);
	    /*
	     * TBD Verify!
             */
            SessionListener.getListener(sUID).startSession();
	} catch(Exception e)  {
	    String errStr = "startSession: Start of session failed. Session ID: "
		            + sessionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Stop message delivery for a session.
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session on which to stop delivery
     *
     *  @return The JMSServiceReply of the request to stop the session
     *
     *  @throws JMSServiceException if the Status returned for the
     *          stopSession method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply stopSession(long connectionId, long sessionId)
    			throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	SessionUID sUID;
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "stopSession");
	sUID = new SessionUID(sessionId);

	try {
		// CR 6935676
	    //protocol.pauseSession(sUID);
	    /*
	     * TBD Verify!
             */
            SessionListener.getListener(sUID).stopSession();
	} catch(Exception e)  {
	    String errStr = "stopSession: Stop of session failed. Session ID: "
		        + sessionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Verify the existence / auto-createability of a physical destination.<p>
     *  If the destination exists the Status returned is OK.<br>
     *  If the destination does not exist but can be auto-created the Status
     *  returned is NOT_FOUND along with the JMQCanCreate property is set to
     *  true.<br>
     *  If the destination does not exist and cannot be auto-created the Status
     *  returned is NOT_FOUND along with JMQCanCreate property set to false.
     *  
     *  @param  connectionId The Id of the connection
     *  @param  dest The Destination object that defines the physical destination
     *
     *  @return The JMSServiceReply of the request to verify the destination
     *
     *  @throws JMSServiceException if the Status returned for the
     *          verifyDestination method is not either
     *          {@link JMSServiceReply.Status#OK} or 
     *          {@link JMSServiceReply.Status#NOT_FOUND}
     *
     *  @see JMSServiceReply#getJMQCanCreate
     *  @see JMSServiceReply#getJMQDestType
     *
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#FORBIDDEN
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#ERROR
     */
    public JMSServiceReply verifyDestination(long connectionId,
            Destination dest) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	HashMap resultProps = null;

        cxn = checkConnectionId(connectionId, "verifyDestination");

	try  {
	    resultProps = protocol.verifyDestination(dest.getName(),
			(dest.getType() == Destination.Type.QUEUE) ? 
			    DestType.DEST_TYPE_QUEUE : DestType.DEST_TYPE_TOPIC,
			 null /* selector param not needed ? */);
	} catch(Exception e)  {
	    String errStr = "verifyDestination: verify of destination failed. Destination name: "
			+ dest.getName()
			+ ", type: "
			+ dest.getType();

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}

	if (resultProps != null)  {
	    Integer status = (Integer)resultProps.get("JMQStatus");

	    if (status != null)  {
	        if (status.intValue() 
			== com.sun.messaging.jmq.io.Status.OK)  {
	            props.put("JMQStatus", JMSServiceReply.Status.OK);
	        } else if (status.intValue() 
			== com.sun.messaging.jmq.io.Status.NOT_FOUND)  {
	            Boolean canCreate = (Boolean)resultProps.get("JMQCanCreate");

	            props.put("JMQStatus", JMSServiceReply.Status.NOT_FOUND);
		    if (canCreate != null)  {
	                props.put("JMQCanCreate", canCreate);
		    }
	        }
	    }
	}

	props.put("JMQDestination", dest.getName());
	props.put("JMQDestType", dest.getType());

	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Create a physical destination.
     *
     *  @param connectionId The Id of the connection
     *  @param dest The Destination object that defines the physical destination
     *              to be created.
     *              <p>If the physical destination does not exist, it will be
     *              automatically created if the configuration allows. [DEFAULT]
     * 
     *  @return The JMSServiceReply of the request to create the destination
     *
     *  @throws JMSServiceException if the Status returned for the
     *          createDestination method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply#getJMQDestType
     *  @see JMSServiceReply.Status#ERROR
     */
    public JMSServiceReply createDestination(long connectionId,
            Destination dest) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "createDestination");

	int internalDestType;
	if (dest.getType() == Destination.Type.QUEUE)  {
	    internalDestType = DestType.DEST_TYPE_QUEUE;
	} else  {
	    internalDestType = DestType.DEST_TYPE_TOPIC;
	}

	if (dest.getLife() == Destination.Life.TEMPORARY)  {
	    internalDestType |= DestType.DEST_TEMP;
	}

        if (cxn.isAdminConnection()) {
	    /*
	     * Copied from 
	     * com.sun.messaging.jmq.jmsserver.data.handlers.DestinationHandler
	     *
	     * Not sure why DestType.DEST_AUTO is included here.
	     */
            internalDestType |= DestType.DEST_ADMIN | DestType.DEST_LOCAL
                                | DestType.DEST_AUTO;
        }

	String errStr = "createDestination: Destination creation failed. Destination name: "
			+ dest.getName()
			+ ", type: "
			+ dest.getType()
			+ ", lifespan: "
			+ dest.getLife();
	try  {
            com.sun.messaging.jmq.jmsserver.core.Destination d =null;

	    d = protocol.createDestination(dest.getName(), internalDestType, cxn, acEnabled);
	} catch(BrokerException be)  {
            logger.logStack(Logger.ERROR, errStr, be);

	    int status = be.getStatusCode();
	    if (status == Status.CONFLICT)  {
	        props.put("JMQStatus", JMSServiceReply.Status.CONFLICT);
	    } else if (status == Status.FORBIDDEN)  {
	        props.put("JMQStatus", JMSServiceReply.Status.FORBIDDEN);
	    } else  {
	        props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    }

	    throw new JMSServiceException(errStr, be, props);
	} catch (IOException ioe)  {
            logger.logStack(Logger.ERROR, errStr, ioe);

	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, ioe, props);
	} catch (AccessControlException ace)  {
            logger.logStack(Logger.ERROR, errStr, ace);

	    props.put("JMQStatus", JMSServiceReply.Status.FORBIDDEN);
	    throw new JMSServiceException(errStr, ace, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQDestination", dest.getName());
	props.put("JMQDestType", dest.getType());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Destroy a physical destination.
     *
     *  @param connectionId The Id of the connection
     *  @param dest The Destination object that identifies the physical
     *              destination to be destroyed
     *
     *  @return The JMSServiceReply of the request to destroy the destination
     *
     *  @throws JMSServiceException if the Status returned for the
     *          destroyDestination method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply destroyDestination(long connectionId,
            Destination dest) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "destroyDestination");

	try  {
            com.sun.messaging.jmq.jmsserver.core.Destination d =null;

            com.sun.messaging.jmq.jmsserver.core.DestinationUID rmuid = 
		com.sun.messaging.jmq.jmsserver.core.DestinationUID.getUID(
			dest.getName(), 
		        (dest.getType() == Destination.Type.QUEUE));

            assert com.sun.messaging.jmq.jmsserver.core.Destination.getDestination(rmuid) 
			!= null;

            com.sun.messaging.jmq.jmsserver.core.Destination.removeDestination(
		    rmuid, true, 
                    Globals.getBrokerResources().getString(
                    BrokerResources.M_CLIENT_REQUEST, cxn.getConnectionUID()));
	} catch(Exception e)  {
	    String errStr = "destroyDestination: Destination destroy failed. Destination name: "
			+ dest.getName()
			+ ", type: "
			+ dest.getType()
			+ ", lifespan: "
			+ dest.getLife();

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Add a producer.
     *
     *  @param connectionId The Id of the connection
     *  @param sessionId The Id of the session in which to add the producer
     *  @param dest The Destination on which to add a producer
     *
     *  @return The JMSServiceReply of the request to add a producer
     *
     *  @throws JMSServiceException if the Status returned for the
     *          addProducer method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#getJMQProducerID
     *
     *  @see JMSServiceReply.Status#FORBIDDEN
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply addProducer(long connectionId, long sessionId,
            Destination dest) throws JMSServiceException  {

	JMSServiceReply reply;
	Session session;
	HashMap props = new HashMap();
	IMQConnection cxn;
	com.sun.messaging.jmq.jmsserver.core.Destination d;
	com.sun.messaging.jmq.jmsserver.core.Producer prd = null;
	long producerID = 0;

        cxn = checkConnectionId(connectionId, "addProducer");
        session = checkSessionId(sessionId, "addProducer");

	try  {
            d = com.sun.messaging.jmq.jmsserver.core.Destination.getDestination(dest.getName(),
		       (dest.getType() == Destination.Type.QUEUE));

            prd = protocol.addProducer(d, cxn, new Object().toString(), acEnabled);
	    producerID = prd.getProducerUID().longValue();
	} catch(Exception e)  {
	    String errStr = "addProducer: Add producer failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQProducerID", producerID);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Delete a producer.
     *
     *  @param  connectionId The Id of the connection in which to delete the
     *          producer
     *  @param  sessionId The Id of the session in which to delete the producer
     *  @param  producerId The Id of the producer to delete
     *
     *  @return The JMSServiceReply of the request to delete a producer
     *
     *  @throws JMSServiceException if the Status returned for the
     *          deleteProducer method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#FORBIDDEN
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply deleteProducer(long connectionId, long sessionId,
            long producerId) throws JMSServiceException  {

	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "deleteProducer");

	try  {
            protocol.removeProducer(new ProducerUID(producerId), 
			cxn, new Object().toString());
	} catch(Exception e)  {
	    String errStr = "deleteProducer: Delete producer failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", producer ID: "
			+ producerId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Add a consumer.<p> The initial state of the consumer must be the
     *  <u>sync</u> state.
     *
     *  @param  connectionId The Id of the connection in which to add the
     *          consumer
     *  @param  sessionId The Id of the session in which to add the consumer.
     *          The acknowledgement mode of the consumer will be that of the
     *          session
     *  @param  dest The Destination from which the consumer will receive
     *          messages
     *  @param  selector The selector which will be used to filter messages
     *  @param  durableName If non-null, and dest is a Topic, this consumer is
     *          a durable subscription and this parameter will be the name
     *          assigned to the durable subscription. A non-null, empty
     *          durableName is an error.
     *  @param  clientId The clientId to use when this is a durable subscription
     *          with a non-null durableName. This clientId must match the one
     *          that has been set on the connection previously.
     *  @param  noLocal If {@code true}, consumer does not wnat to receive
     *          messages produced on the same connection<br>
     *          If {@code false}, consumer wants to receive messages produced
     *          on the same connection as well.
     *  @param  shared If {@code true}, and dest is a Topic, this is a
     *          shared consumer and messages will be shared with other
     *          consumers in the same group that have the same
     *          clientId+DurableName for Shared Durable Subscriptions<p>
     *          OR<p>
     *          clientId+TopicName+Selector for Shared Non-Durable Sunscriptions
     *
     *  @return The JMSServiceReply of the request to add a consumer
     *
     *  @throws JMSServiceException if the Status returned for the
     *          addConsumer method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSService#setConsumerAsync
     *
     *  @see JMSServiceReply.Status#getJMQConsumerID
     *
     *  @see JMSServiceReply.Status#FORBIDDEN
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#NOT_ALLOWED
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply addConsumer(long connectionId, long sessionId,
            Destination dest, String selector, String durableName,
            String clientId, boolean noLocal, boolean shared)
                                    throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	com.sun.messaging.jmq.jmsserver.core.Destination d;
	Session session;
	com.sun.messaging.jmq.jmsserver.core.Consumer con;
	int size = 1000;
	long consumerID = 0;

        cxn = checkConnectionId(connectionId, "addConsumer");
        session = checkSessionId(sessionId, "addConsumer");

	try  {
            d = com.sun.messaging.jmq.jmsserver.core.Destination.getDestination(dest.getName(),
		       (dest.getType() == Destination.Type.QUEUE));

	    /*
	     * size (prefetch size) is not needed here since the broker is going to 
	     * call the client method with the messages, not simply dump packets 
	     * till a particular size is reached.
	     */
            boolean useFlowControl=false;
            con = protocol.createConsumer(d, cxn,
                        session, selector, clientId,
                        durableName, noLocal, size,
                        shared, new Object().toString(), acEnabled, useFlowControl);
	    consumerID = con.getConsumerUID().longValue();
	} catch(Exception e)  {
	    String errStr = "addConsumer: Add consumer failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    if (e instanceof SelectorFormatException)  {
	        props.put("JMQStatus", JMSServiceReply.Status.BAD_REQUEST);
	    } else  {
	        props.put("JMQStatus", getErrorReplyStatus(e));
	    }
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQConsumerID", consumerID);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Delete a consumer.<p>
     *  If this operation is deleting a consumer that is a durable subscription,
     *  then the durableName <b>and</b> the clientId must be non-null. In
     *  addition, the clientId must match the clientId that is currently set
     *  on the connection identified by  connectionId.
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session
     *  @param  consumerId The Id of the consumer to delete
     *  @param  durableName The name of the durable subscription to remove
     *          if the consumer is unsubscribing.
     *  @param  clientId The clientId of the connection
     *
     *  @return The JMSServiceReply of the request to delete a consumer
     *
     *  @throws JMSServiceException if the Status returned for the
     *          deleteConsumer method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#FORBIDDEN
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply deleteConsumer(long connectionId, long sessionId,
            long consumerId, String durableName, String clientId) 
			throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	Session session;
	HashMap props = new HashMap();
	com.sun.messaging.jmq.jmsserver.core.Consumer con;

        cxn = checkConnectionId(connectionId, "deleteConsumer");
        session = checkSessionId(sessionId, "deleteConsumer");

	try  {
	    if (durableName == null)  {
	        ConsumerUID conUID = new ConsumerUID(consumerId);
                protocol.destroyConsumer(conUID, session, cxn);
	    } else  {
                protocol.unsubscribe(durableName, clientId);
	    }
	} catch(Exception e)  {
	    String errStr = "deleteConsumer: Delete consumer failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", consumer ID: "
			+ consumerId
			+ ", durable name: "
			+ durableName
			+ ", client ID: "
			+ clientId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Configure a consumer for async or sync message consumption.<p>
     *  This method is used to enable and disable async delivery of messages
     *  from the broker to the client.
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session
     *  @param  consumerId The Id of the consumer for which the async state is
     *          being set.
     *  @param  consumer The Consumer object that is to be used to deliver the
     *          message.<br>
     *          If <b>non-null</b>, the consumer is being set
     *          into the <u>async</u> state and the server must start delivering
     *          messagesto the {@code Consumer.deliver()} method when the
     *          connectionId and sessionId have been started via the
     *          {@code startConnection} and {@code startSession} methods.<br>
     *          If <b>null</b>, the consumer is being set into the <u>sync</u>
     *          state and the delivery of messages must stop.<br>
     *          The session must first be stopped, before changing a
     *          consumer from the async state to the sync state. However, the
     *          connection and session are not required to be stopped when
     *          a consumer is changed from the sync state, which is the default
     *          for {@code addConsumer()}, to the async state.
     *
     *  @throws JMSServiceException if the Status returned for the
     *          setConsumerAsync method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSService#startConnection
     *  @see JMSService#startSession
     *  @see JMSService#stopSession
     *  @see Consumer#deliver
     *
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#CONFLICT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply setConsumerAsync(long connectionId, long sessionId,
            long consumerId, Consumer consumer)
                        throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	com.sun.messaging.jmq.jmsserver.core.Destination d;
	Session session;
	com.sun.messaging.jmq.jmsserver.core.Consumer con;
	int size = -1;

        cxn = checkConnectionId(connectionId, "setConsumerAsync");
        session = checkSessionId(sessionId, "setConsumerAsync");

	try  {
	    ConsumerUID conUID = new ConsumerUID(consumerId);
	    con = com.sun.messaging.jmq.jmsserver.core.Consumer.getConsumer(conUID);

            // register it as an asychronous listener
            SessionListener slistener = SessionListener.getListener(session.getSessionUID());
            slistener.setAsyncListener(con, consumer);
	} catch(Exception e)  {
	    String errStr = "setConsumerAsync: Set Async consumer failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", consumer ID: "
			+ consumerId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQConsumerID", consumerId);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }


    /**
     *  Start a transaction.
     *
     *  @param connectionId The Id of the connection
     *  @param sessionId If non-zero, this is the Id of the session in which the
     *                   transaction is being created. This parameter is zero
     *                   for XA transactions
     *  @param xid If non-null, an XA transaction is being started
     *  @param flags If xId is non-null, then flags is one of:<p>
     *  <UL>
     *  <LI>XAResource.TMNOFLAGS = start a brand new transaction</LI>
     *  <LI>XAResource.TMRESUNE = resume a previously suspended transaction</LI>
     *  </UL>
     *  @param  rollback The type of transaction rollback behavior to use
     *  @param  timeout The transaction timeout to use. The timeout is the
     *          maximum time in seconds that the transaction will be allowed to
     *          be in an un-prepared state.
     *
     *  @return The JMSServiceReply of the request to start a transaction
     *
     *  @throws JMSServiceException if the Status returned for the
     *          startTransaction method is not {@link JMSServiceReply.Status#OK}
     *
     *
     */
    public JMSServiceReply startTransaction(long connectionId, long sessionId, Xid xid,
                        int flags, JMSService.TransactionAutoRollback rollback,
			long timeout) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	TransactionUID txnUID = null;
	JMQXid jmqXid = null;
	Integer xaFlags = null;
	boolean isXA = false;
	AutoRollbackType type = null;

        cxn = checkConnectionId(connectionId, "startTransaction");

	if (xid != null)  {
	    isXA = true;
	    jmqXid = new JMQXid(xid);
	    xaFlags = new Integer(flags);
	}

	try  {
	    txnUID = protocol.startTransaction(jmqXid, xaFlags, type, timeout, cxn);
	} catch(BrokerException be)  {
	    String errStr = "startTransaction: start transaction failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", XID: "
			+ xid;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQTransactionID", txnUID.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  End a transaction.
     *
     *  @param  connectionId The Id of the connection
     *  @param  transactionId If non-zero, the transaction being ended is
     *          identified by this broker-generated id
     *  @param  xid If transactionId is zero, then xid contains the Xid of the
     *          XA transaction being ended
     *  @param  flags If this is an XA transaction, then flags is one of:
     *  <UL>
     *  <LI>XAResource.TMSUSPEND: If suspending a transaction</LI>
     *  <LI>XAResource.TMFAIL:    If failing a transaction</LI>
     *  <LI>XAResource.TMSUCCESS: If ending a transaction</LI>
     *  </UL>
     *  
     *  @return The JMSServiceReply of the request to end a transaction
     *
     *  @throws JMSServiceException if the Status returned for the
     *          endTransaction method is not {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#getJMQTransactionID
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#TIMEOUT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply endTransaction(long connectionId, long transactionId,
            Xid xid, int flags) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	TransactionUID txnUID = null;
	JMQXid jmqXid = null;
	Integer xaFlags = null;
	long tid = 0;

        cxn = checkConnectionId(connectionId, "endTransaction");

	txnUID = new TransactionUID(transactionId);

	if (xid != null)  {
	    jmqXid = new JMQXid(xid);
	    xaFlags = new Integer(flags);
	}

	try  {
	    protocol.endTransaction(txnUID, jmqXid, xaFlags);
	} catch(BrokerException be)  {
	    String errStr = "endTransaction: end transaction failed. Connection ID: "
			+ connectionId
			+ ", Transaction ID: "
			+ transactionId
			+ ", XID: "
			+ xid;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQTransactionID", txnUID.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Pepare a transaction.
     *
     *  @param  connectionId The Id of the connection
     *  @param  transactionId If non-zero, the transaction being prepared is
     *          identified by this broker-generated id
     *  @param  xid If transactionId is zero, then xid contains the Xid of the
     *          XA transaction being prepared
     *  
     *  @return The JMSServiceReply of the request to prepare a transaction
     *
     *  @throws JMSServiceException if the Status returned for the
     *          prepareTransaction method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#getJMQTransactionID
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#TIMEOUT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply prepareTransaction(long connectionId,
            long transactionId, Xid xid) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	TransactionUID txnUID = null;
	JMQXid jmqXid = null;
	Integer xaFlags = null;

        cxn = checkConnectionId(connectionId, "prepareTransaction");

	/*
	 * If transactionId is 0, extract it from XID
	 */
	if (transactionId == 0)  {
	    jmqXid = new JMQXid(xid);
	    /*
	    xaFlags = new Integer(flags);
	    */
	    txnUID = Globals.getTransactionList().xidToUID(jmqXid);
	} else  {
	    txnUID = new TransactionUID(transactionId);
	}

	try  {
	    protocol.prepareTransaction(txnUID, xaFlags, cxn);
	} catch(BrokerException be)  {
	    String errStr = "prepareTransaction: prepare transaction failed. Connection ID: "
			    + connectionId
			    + ", Transaction ID: "
			    + transactionId
			    + ", XID: "
			    + xid;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQTransactionID", txnUID.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Commit a transaction.
     *
     *  @param  connectionId The Id of the connection
     *  @param  transactionId If non-zero, the transaction being committed is
     *          identified by this broker-generated id
     *  @param  xid If transactionId is zero, then xid contains the Xid of the
     *          XA transaction being committed
     *  @param  flags If this is an XA transaction, then flags is one of:
     *  <UL>
     *  <LI>XAResource.TMONEPHASE:One phase commit. The transaction need not be
     *                            in the PREPARED state</LI>
     *  <LI>XAResource.TMNOFLAGS: Two phase commit. The transaction must be in
     *                            the PREPARED state</LI>
     *  </UL>
     *  
     *  @return The JMSServiceReply of the request to commit a transaction
     *
     *  @throws JMSServiceException if the Status returned for the
     *          commitTransaction method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#getJMQTransactionID
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#TIMEOUT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply commitTransaction(long connectionId,
            long transactionId, Xid xid, int flags)
    		throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	TransactionUID txnUID = null;
	JMQXid jmqXid = null;
	Integer xaFlags;
	long tid = 0;

        cxn = checkConnectionId(connectionId, "commitTransaction");

	txnUID = new TransactionUID(transactionId);
        if (xid != null)
	    jmqXid = new JMQXid(xid);

	xaFlags = new Integer(flags);

	try  {
	    protocol.commitTransaction(txnUID, jmqXid, xaFlags, cxn);
	} catch(BrokerException be)  {
	    String errStr = "CommitTransaction: commit failed. Connection ID: "
			+ connectionId
			+ ", Transaction ID: "
			+ transactionId;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQTransactionID", txnUID.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Rollback a transaction.
     *
     *  @param  connectionId The Id of the connection
     *  @param  transactionId If non-zero, the transaction being rolledback is
     *          identified by this broker-generated id
     *  @param  xid If transactionId is zero, then xid contains the Xid of the
     *          XA transaction being rolledback
     *  @param  redeliver If <code>true</code>, then the broker must redeliver
     *          the messages that were rolledback by this operation.
     *  @param  setRedelivered If true <b><u>and</u></b> <code>redeliver</code>
     *          is <code>true</code> then the broker must set the
     *          REDELIVERED flag on messages it redelivers. 
     *  
     *  @return The JMSServiceReply of the request to rollback a transaction
     *
     *  @throws JMSServiceException if the Status returned for the
     *          rollbackTransaction method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     *  @see JMSServiceReply.Status#getJMQTransactionID
     *
     *  @see JMSServiceReply.Status#BAD_REQUEST
     *  @see JMSServiceReply.Status#NOT_FOUND
     *  @see JMSServiceReply.Status#PRECONDITION_FAILED
     *  @see JMSServiceReply.Status#TIMEOUT
     *  @see JMSServiceReply.Status#ERROR
     *
     */
    public JMSServiceReply rollbackTransaction(long connectionId,
            long transactionId, Xid xid, boolean redeliver,
            boolean setRedelivered) throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;
	TransactionUID txnUID = null;
	JMQXid jmqXid = null;
	Integer xaFlags = null;

        cxn = checkConnectionId(connectionId, "rollbackTransaction");

	txnUID = new TransactionUID(transactionId);

	if (xid != null)  {
	    jmqXid = new JMQXid(xid);
	    /*
	    xaFlags = new Integer(flags);
	    */
	}

	try  {
	    protocol.rollbackTransaction(txnUID, jmqXid, xaFlags, cxn, redeliver, setRedelivered);
	} catch(BrokerException be)  {
	    String errStr = "rollbackTransaction: rollback transaction failed. Connection ID: "
			+ connectionId
			+ ", Transaction ID: "
			+ transactionId
			+ ", XID: "
			+ xid;

            logger.logStack(Logger.ERROR, errStr, be);
	    props.put("JMQStatus", getErrorReplyStatus(be));
	    throw new JMSServiceException(errStr, be, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQTransactionID", txnUID.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Recover XA transactions from the broker.
     *
     *  @param connectionId The Id of the connection
     *  @param flags Controls the cursor positioning for the scanning of Xids
     *               returned.
     *               flags is set by the transaction manager and passed in 
     *               directly through the XAResource interface.
     *               flags can be one of:<p>
     *
     *<p>
     *  <UL>
     *  <LI>{@link javax.transaction.xa.XAResource#TMNOFLAGS XAResource.TMNOFLAGS}: Used when neither of the following two flags are used</LI>
     *  <LI>{@link javax.transaction.xa.XAResource#TMSTARTRSCAN XAResource.TMSTARTRSCAN}: Starts a recovery scan</LI>
     *  <LI>{@link javax.transaction.xa.XAResource#TMENDRSCAN XAResource.TMENDRSCAN}:  Ends a recovery scan</LI>
     *  </UL>
     *
     *  @see javax.transaction.xa.XAResource#recover javax.transaction.xa.XAResource.recover()
     */
    public Xid[] recoverXATransactions(long connectionId, int flags) 
					throws JMSServiceException  {
	IMQConnection cxn;
	JMQXid jmqXids[] = null;

        cxn = checkConnectionId(connectionId, "recoverXATransactions");

	try  {
	    jmqXids = protocol.recoverTransaction(null);
	} catch(Exception e)  {
	    HashMap props = new HashMap();
	    String errStr = "recoverXATransactions: recover XA transactions failed. Connection ID: "
			+ connectionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	return (jmqXids);
    }

    /**
     *  Recover a transaction from the broker.
     *
     *  @param connectionId The Id of the connection
     *  @param transactionId The id of the transaction to recover
     *
     *  @return The transactionId is returned if the transaction is in the
     *          PREPARED state. If the transaction is not found or not in the
     *          PREPARED state a zero (0L) is returned.
     */
    public long recoverTransaction(long connectionId, long transactionId) 
					throws JMSServiceException  {
	IMQConnection cxn;
        TransactionUID txnUID = null;
	JMQXid jmqXids[];
	long tid = 0;

        cxn = checkConnectionId(connectionId, "recoverTransaction");
	txnUID = new TransactionUID(transactionId);

	try  {
	    jmqXids = protocol.recoverTransaction(txnUID);

	    if (jmqXids.length > 0)  {
	        return(transactionId);
	    }
	} catch(Exception e)  {
	    String errStr = "recoverTransaction: recover transaction failed. Connection ID:"
			+ connectionId
			+ ", Transaction ID: "
			+ transactionId;

            logger.logStack(Logger.ERROR, errStr, e);

	    HashMap props = new HashMap();
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	return ((long)0);
    }

    /**
     *  Send a message to the broker.
     *
     *  @param  connectionId The Id of the connection
     *  @param  message The Message to be sent
     *
     *  @throws JMSServiceException if the Status returned for the
     *          sendMessage method is not {@link JMSServiceReply.Status#OK}
     *
     */
    public JMSServiceReply sendMessage(long connectionId, JMSPacket message)
    		throws JMSServiceException  {
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "sendMessage");

	/*
	 * TBD: The packets used here are not compatible. Should sendMessage() have
	 * individual params (eg msg body) to pass to processMessage() instead of
	 * Packet ?
	 *
	 * Trying Packet.fill()...
	 */
	try  {
	    Packet p = new Packet();
	    p.fill(message.getPacket());
	    protocol.processMessage(cxn, p);
	} catch(Exception e)  {
	    String errStr = "sendMessage: Sending message failed. Connection ID: "
			+ connectionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    /**
     *  Fetch a message from the broker.
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session
     *  @param  consumerId The Id of the consumer for which to fetch the message
     *  @param  timeout The maximum time to wait (in milliseconds) for a message
     *          to be available before returning.<br>
     *          Note that the method must return immediately if there is a
     *          message available for this consumerId at the time the call is
     *          made.<br>
     *  <UL>
     *  <LI>    When timeout is positive, the call must wait for a maximum of
     *          the specificied number of milliseconds before returning.
     *          If a message is available before the timeout expires, the method
     *          returns with the message as soon as it is available.
     *  </LI>
     *  <LI>    When timeout is 0, the call must block until a message is
     *          available to return or until the session is stopped.
     *  </LI>
     *  <LI>    When the timeout is negative (less than 0), the call must
     *          return immediately, with a message if one is available or
     *          with a null, if a message is not available immediately.
     *  </LI>
     *  </UL>
     *  @param  acknowledge If this is set to {@code true} then it implies that
     *          the caller is asking for the message to be <b>acknowledged</b> 
     *          before the method returns. If this operation is part of a
     *          transaction, the {@code transactionId} parameter will be
     *          non-zero.
     *  @param  transactionId If non-zero, this is the transactionId in which
     *          to acknowledge the message being returned if the
     *          {@code acknowledge} parameter is set to {@code true}.
     *
     *  @return The JMSPacket which contains the message being returned.
     *
     *  @throws JMSServiceException if broker encounters an error.
     *          {@link JMSServiceReply.Status} contains the reason for the
     *          error.
     */
    public JMSPacket fetchMessage(long connectionId, long sessionId,
                    long consumerId, long timeout, boolean acknowledge,
                    long transactionId)
                        throws JMSServiceException  {
        JMSPacket msg = null;
	IMQConnection cxn;
	Session session;

        cxn = checkConnectionId(connectionId, "fetchMessage");
        session = checkSessionId(sessionId, "fetchMessage");

	SessionListener slistener = SessionListener.getListener(session.getSessionUID());
        ConsumerUID conUID;

	try  {
	        conUID = new ConsumerUID(consumerId);
            msg = slistener.getNextConsumerPacket(conUID, timeout);
            if ((msg != null) && acknowledge) {
	            TransactionUID txnUID = null;

		        if (transactionId != 0)  {
	                txnUID = new TransactionUID(transactionId);
		        }
                SysMessageID ids[] = new SysMessageID[1];
                ids[0] = ((Packet)msg).getSysMessageID();
                ConsumerUID cids[] = new ConsumerUID[1];
                cids[0] = conUID;
                Globals.getProtocol().acknowledge(cxn, txnUID, false, AckHandler.ACKNOWLEDGE_REQUEST, null, null, 0, ids, cids);
            }
	} catch(Exception e)  {
	    HashMap props = new HashMap();
	    String errStr = "fetchMessage: Fetch Message failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", consumer ID: "
			+ consumerId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, e, props);
	}


	return (msg);
    }

    /**
     *  Acknowledge a message to the broker.
     *
     *  @param  connectionId The Id of the connection
     *  @param  sessionId The Id of the session
     *  @param  consumerId The Id of the consumer for which to acknowledge
     *          the message
     *
     *  @param  sysMessageID The SysMessageID of the message to be acknowledged
     *
     *  @param  transactionId If non-zero, this is the transactionId in which
     *          to acknowledge the message.
     *
     *  @param  ackType The MessageAckType for this message acknowledgement
     *
     *  @return The JMSServiceReply which contains status and information
     *          about the acknowledge request.
     *
     *  @throws JMSServiceException If the Status returned for the
     *          acknowledgeMessage method is not
     *          {@link JMSServiceReply.Status#OK}.<br>
     *          {@link JMSServiceException#getJMSServiceReply} should be used
     *          to obtain the broker reply in case of an exception.<br>
     *          The reason for the exception can be obtained from
     *          {@link JMSServiceReply.Status}
     *
     */
    public JMSServiceReply acknowledgeMessage(long connectionId, long sessionId,
            long consumerId, SysMessageID sysMessageID, long transactionId,
            MessageAckType ackType) throws JMSServiceException  {
	boolean validate = false;
        TransactionUID txnUID = null;
        int brokerAckType;
        int deliverCnt = -1;
	SysMessageID ids[] = null;
	ConsumerUID cids[] = null;
        Throwable deadThr = null;
	String deadComment = null;
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "acknowledgeMessage");

	if (transactionId != 0)  {
	    txnUID = new TransactionUID(transactionId);
	}

	brokerAckType = convertToBrokerAckType(ackType);

	ids = new SysMessageID [ 1 ];
	ids[0] = sysMessageID;

	cids = new ConsumerUID [ 1 ];
	cids[0] = new ConsumerUID(consumerId);

	try  {
    	    /*
	     * TBD:
     	     * validate - should the acks just be validated (normally false)
     	     * deadThr - exception associated with a dead message (should be null
     	     *                 if ackType != DEAD)
     	     * deadComment - the explaination why a message was marked dead (should be null
     	     *               if ackType != DEAD)
     	     * deliverCnt - number of times a dead message was delivered (should be 0
     	     *               if ackType != DEAD)
     	     */
            protocol.acknowledge(cxn, txnUID, validate, brokerAckType, 
	    		deadThr, deadComment, deliverCnt, ids, cids);
	} catch(Exception e)  {
	    String errStr = 
	    "acknowledgeMessage: Sending Acknowledgement failed. Connection ID: "
			+ connectionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }


    /**
     *  Send a message acknowledgement to the broker.
     *  All messages acknowledged by the method must be of the same
     *  MessageAckType
     *
     *  @param  connectionId The Id of the connection
     *  @param  ackType The type of the acknowledgement
     *  @param  acks The acknowledgements
     *
     *  @throws JMSServiceException if the Status returned for the
     *          sendAcknowledgement method is not
     *          {@link JMSServiceReply.Status#OK}
     *
     */
    public JMSServiceReply sendAcknowledgement(long connectionId,
                MessageAckType ackType, JMSPacket acks) 
			throws JMSServiceException  {

	boolean validate = false;
	long transactionId = -1;
        TransactionUID txnUID = null;
        int brokerAckType;
        int deliverCnt = -1;
	SysMessageID ids[] = null;
	ConsumerUID cids[] = null;
        Throwable deadThr = null;
	String deadComment = null;
	JMSServiceReply reply;
	HashMap props = new HashMap();
	IMQConnection cxn;

        cxn = checkConnectionId(connectionId, "sendAcknowledgement");

	if (transactionId != -1)  {
	    txnUID = new TransactionUID(transactionId);
	}

	brokerAckType = convertToBrokerAckType(ackType);

	try  {
    	    /*
	     * TBD:
     	     * txnUID - transaction id associated with the acknowledgement (or null 
	     * if no transaction)
     	     * validate - should the acks just be validated (normally false)
     	     * deadThr - exception associated with a dead message (should be null
     	     *                 if ackType != DEAD)
     	     * deadComment - the explaination why a message was marked dead (should be null
     	     *               if ackType != DEAD)
     	     * deliverCnt - number of times a dead message was delivered (should be 0
     	     *               if ackType != DEAD)
     	     * ids - array of message ids to process
     	     * cids - array of consumerIDs associated with a message, should directly 
     	     *            correspond to the same index in ids
     	     */
            protocol.acknowledge(cxn, txnUID, validate, brokerAckType, 
	    		deadThr, deadComment, deliverCnt, ids, cids);
	} catch(Exception e)  {
	    String errStr = 
	    "sendAcknowledgement: Sending Acknowledgement failed. Connection ID: "
			+ connectionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    public JMSServiceReply addBrowser(long connectionId, long sessionId,
            Destination dest, String selector)
                                    throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	ConsumerUID uid;
	Session session;

        cxn = checkConnectionId(connectionId, "addBrowser");
        session = checkSessionId(sessionId, "addBrowser");

	try  {
	    Selector sel = Selector.compile(selector);
	} catch(SelectorFormatException sfe)  {
	    String errStr = "addBrowser: Add browser failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", destination: "
			+ dest
			+ ", selector: "
			+ selector;

            logger.logStack(Logger.ERROR, errStr, sfe);
	    props.put("JMQStatus", JMSServiceReply.Status.BAD_REQUEST);
	    throw new JMSServiceException(errStr, sfe, props);
	}

	uid = new ConsumerUID();
	queueBrowseList.put(uid, new QueueBrowserInfo(dest, selector));

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQConsumerID", uid.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    public JMSServiceReply deleteBrowser(long connectionId, long sessionId,
            long consumerId)
                                    throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	ConsumerUID uid;
	Session session;

        cxn = checkConnectionId(connectionId, "deleteBrowser");
        session = checkSessionId(sessionId, "deleteBrowser");

	uid = new ConsumerUID(consumerId);

	if (queueBrowseList.containsKey(uid))  {
	    queueBrowseList.remove(uid);
	} else  {
	    String errStr = "deleteBrowser: consumer ID not found. Connection ID:"
			+ connectionId
			+ ", Session ID: "
			+ sessionId
			+ ", Consumer ID: "
			+ consumerId;

            logger.log(Logger.ERROR, errStr);

	    props.put("JMQStatus", JMSServiceReply.Status.NOT_FOUND);
	    throw new JMSServiceException(errStr, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	props.put("JMQConsumerID", uid.longValue());
	reply = new JMSServiceReply(props, null);

	return (reply);
    }

    public JMSPacket[] browseMessages(long connectionId, long sessionId,
            long consumerId) throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	ConsumerUID uid;
	Session session;
	JMSPacket[] msgs = null;

        cxn = checkConnectionId(connectionId, "browseMessages");
        session = checkSessionId(sessionId, "browseMessages");

	uid = new ConsumerUID(consumerId);

	if (queueBrowseList.containsKey(uid))  {
	    QueueBrowserInfo qbi = (QueueBrowserInfo)queueBrowseList.get(uid);

	    try  {
	        Destination dest = qbi.dest;
	        String selector = qbi.selector;
	        com.sun.messaging.jmq.jmsserver.core.Destination d;
                d = com.sun.messaging.jmq.jmsserver.core.Destination.getDestination(dest.getName(),
		       (dest.getType() == Destination.Type.QUEUE));

		if (d == null)  {
	            String errStr = "browseMessages: destination not found. Connection ID:"
			+ connectionId
			+ ", Session ID: "
			+ sessionId
			+ ", Consumer ID: "
			+ consumerId
			+ "destination: "
			+ dest.toString();

                    logger.log(Logger.ERROR, errStr);

	            props.put("JMQStatus", JMSServiceReply.Status.NOT_FOUND);
	            throw new JMSServiceException(errStr, props);
		}

		ArrayList msgIds = protocol.browseQueue(d, selector, cxn, acEnabled);

		if (msgIds != null)  {
		    int numMsgs = msgIds.size();

		    if (numMsgs == 0)  {
			return (null);
		    }

		    msgs = new JMSPacket[ numMsgs ];

		    for (int i = 0; i < numMsgs; ++i)  {
			PacketReference pr = d.get((SysMessageID)msgIds.get(i));
			msgs[i] = pr.getPacket();
		    }
		}
	    } catch (Exception e)  {
	        String errStr = "browseMessages: Browse queue failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", consumer ID: "
			+ consumerId;

                logger.logStack(Logger.ERROR, errStr, e);
	        if (e instanceof SelectorFormatException)  {
	            props.put("JMQStatus", JMSServiceReply.Status.BAD_REQUEST);
	        } else  {
	            props.put("JMQStatus", getErrorReplyStatus(e));
	        }
	        throw new JMSServiceException(errStr, e, props);
	    }
	} else  {
	    String errStr = "browseMessages: consumer ID not found. Connection ID:"
			+ connectionId
			+ ", Session ID: "
			+ sessionId
			+ ", Consumer ID: "
			+ consumerId;

            logger.log(Logger.ERROR, errStr);

	    props.put("JMQStatus", JMSServiceReply.Status.NOT_FOUND);
	    throw new JMSServiceException(errStr, props);
	}

	return (msgs);
    }

    /**
     *  Redeliver messages for a Session.<p>
     *  All the messages that are specified by the parameters will be
     *  redelivered by the broker.
     *
     *  @param  connectionId The Id of the connection in which the messages were
     *          received
     *  @param  sessionId The Id of the session in which the messages were
     *          received
     *  @param  SysMessageID[] The array of SysMessageID objects for the
     *          messages that were received and are to be redelivered
     *  @param  consumerIds[] The array of consumerId longs for the messages
     *          that were received and are to be redelivered
     *  @param  transactionId The Id of the transaction in which the messages
     *          were received
     *  @param  setRedelivered Indicates whether to set the Redelivered flag
     *          when redelivering the messages.<br>
     *          If <code>true</code> then the Redelivered flag must be set for
     *          the messages when they are redelivered.<br>
     *          If <code>false</code>, then the Redelivered flag must not be
     *          set for the messages when they are redelivered.
     *
     *  @throws JMSServiceException If broker encounters an error.<br>
     *          {@link JMSServiceException#getJMSServiceReply} should be used
     *          to obtain the broker reply in case of an exception.<br>
     *          The reason for the exception can be obtained from
     *          {@link JMSServiceReply.Status}
     */
    public JMSServiceReply redeliverMessages(long connectionId, long sessionId,
            SysMessageID[] messageIDs, Long[] consumerIds, long transactionId,
            boolean setRedelivered) throws JMSServiceException  {
	JMSServiceReply reply;
	IMQConnection cxn;
	HashMap props = new HashMap();
	ConsumerUID conUIDs[] = null;
        TransactionUID txnUID = null;
	Session session;

        cxn = checkConnectionId(connectionId, "redeliverMessages");
        session = checkSessionId(sessionId, "redeliverMessages");

	if (consumerIds != null)  {
	    conUIDs = new ConsumerUID [ consumerIds.length ];
	    for (int i = 0; i < consumerIds.length; ++i)  {
	        conUIDs[i] = new ConsumerUID(consumerIds[i]);
	    }
	}

	if (transactionId != -1)  {
	    txnUID = new TransactionUID(transactionId);
	}

	try  {
	    protocol.redeliver(conUIDs, messageIDs, cxn, txnUID, true); 
	} catch(Exception e)  {
	    String errStr = 
	    "redeliverMessages: Redeliver failed. Connection ID: "
			+ connectionId
			+ ", session ID: "
			+ sessionId
			+ ", transaction ID: "
			+ transactionId;

            logger.logStack(Logger.ERROR, errStr, e);
	    props.put("JMQStatus", getErrorReplyStatus(e));
	    throw new JMSServiceException(errStr, e, props);
	}

	props.put("JMQStatus", JMSServiceReply.Status.OK);
	reply = new JMSServiceReply(props, null);

	return (reply);
    }


    /*
     * END Interface JMSService
     */

    /*
     * Convenience method to check connection ID.
     * If the connection ID is valid, the corresponding 
     * IMQConnection object will be returned. If not, a
     * JMSServiceException will be thrown.
     */
    IMQConnection checkConnectionId(long connectionId,
			String methodName) throws JMSServiceException  {
	ConnectionManager cm = Globals.getConnectionManager();
	IMQConnection  cxn;
	
	cxn = (IMQConnection)cm.getConnection(new ConnectionUID(connectionId));

	if (cxn == null)  {
            String errStr = methodName
			+ ": connection ID not found: "
			+ connectionId;

            logger.log(Logger.ERROR, errStr);
	    HashMap props = new HashMap();
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, props);
	}

	return (cxn);
    }

    /*
     * Convenience method to check session ID.
     * If the session ID is valid, the corresponding 
     * Session object will be returned. If not, a
     * JMSServiceException will be thrown.
     */
    private Session checkSessionId(long sessionId,
			String methodName) throws JMSServiceException  {
        Session ses = Session.getSession(new SessionUID(sessionId));

	if (ses == null)  {
	    String errStr = methodName
			+ ": session ID not found: "
			+ sessionId;

            logger.log(Logger.ERROR, errStr);
	    HashMap props = new HashMap();
	    props.put("JMQStatus", JMSServiceReply.Status.ERROR);
	    throw new JMSServiceException(errStr, props);
	}

	return (ses);
    }

    private int convertToBrokerAckMode(SessionAckMode ackMode)  {
	switch (ackMode)  {
	case AUTO_ACKNOWLEDGE:
	    return
		(com.sun.messaging.jmq.jmsserver.core.Session.AUTO_ACKNOWLEDGE);
	case CLIENT_ACKNOWLEDGE:
	    return
		(com.sun.messaging.jmq.jmsserver.core.Session.CLIENT_ACKNOWLEDGE);
	case DUPS_OK_ACKNOWLEDGE:
	    return
		(com.sun.messaging.jmq.jmsserver.core.Session.DUPS_OK_ACKNOWLEDGE);
	case NO_ACKNOWLEDGE:
	    return
		(com.sun.messaging.jmq.jmsserver.core.Session.NO_ACK_ACKNOWLEDGE);
	case UNSPECIFIED:
	    return
		(com.sun.messaging.jmq.jmsserver.core.Session.NONE);
	}

	return (com.sun.messaging.jmq.jmsserver.core.Session.NONE);
    }

    private int convertToBrokerAckType(MessageAckType ackType)  {
	switch (ackType)  {
	case ACKNOWLEDGE:
	    return (AckHandler.ACKNOWLEDGE_REQUEST);
	case UNDELIVERABLE:
	    return (AckHandler.UNDELIVERABLE_REQUEST);
	case DEAD:
	    return (AckHandler.DEAD_REQUEST);
	}

	return (AckHandler.ACKNOWLEDGE_REQUEST);
    }

    /*
     * Returns the equivalent JMSServiceReply.Status from the passed Exception.
     * Only really handles the case where the exception is a BrokerException. If
     * the are cases where a status code is dependent on some other type of exception
     * e.g. SelectorFormatException it needs to be handled outside this method.
     */
    private JMSServiceReply.Status getErrorReplyStatus(Exception e)  {
	JMSServiceReply.Status retStatus = JMSServiceReply.Status.ERROR;

	if (e instanceof BrokerException)  {
	    BrokerException be = (BrokerException)e;
	    int status = be.getStatusCode();

	    switch (status)  {
	    case Status.FORBIDDEN:
		retStatus = JMSServiceReply.Status.FORBIDDEN;
                break;
	    case Status.CONFLICT:
		retStatus = JMSServiceReply.Status.CONFLICT;
                break;
	    case Status.BAD_REQUEST:
		retStatus = JMSServiceReply.Status.BAD_REQUEST;
                break;
	    case Status.NOT_FOUND:
		retStatus = JMSServiceReply.Status.NOT_FOUND;
                break;
	    case Status.NOT_ALLOWED:
		retStatus = JMSServiceReply.Status.NOT_ALLOWED;
                break;
	    case Status.PRECONDITION_FAILED:
		retStatus = JMSServiceReply.Status.PRECONDITION_FAILED;
                break;
	    case Status.NOT_MODIFIED:
		retStatus = JMSServiceReply.Status.NOT_MODIFIED;
                break;
	    case Status.NOT_IMPLEMENTED:
		retStatus = JMSServiceReply.Status.NOT_IMPLEMENTED;
                break;
	    case Status.TIMEOUT:
		retStatus = JMSServiceReply.Status.TIMEOUT;
                break;
	    /*
	    case Status.GONE:
		retStatus = JMSServiceReply.Status.GONE;
	    */
	    }
	}

	return (retStatus);
    }

    private void startAllConnections()  {
        Iterator itr = localConnectionList.values().iterator();
        while (itr.hasNext()) {
            IMQConnection cxn = (IMQConnection)itr.next();
	    SessionListener.startConnection(cxn);
        }
    }

    private void stopAllConnections()  {
        Iterator itr = localConnectionList.values().iterator();
        while (itr.hasNext()) {
            IMQConnection cxn = (IMQConnection)itr.next();
	    SessionListener.stopConnection(cxn);
        }
    }
}

    /**
     * Thread that watches a session for events
     */
    class SessionListener implements com.sun.messaging.jmq.util.lists.EventListener, Runnable
    {
        /**
         * IMQDirectService
         */

        IMQDirectService parent;

        /**
         * listener is alive
         */
        boolean valid = false;

	/**
	 * session is stopped ?
	 */
        boolean stopped = true;

        /**
         * session associated with this listener
         */
        Session session = null;

        /**
         * is this a sync or async consumer
         */
        boolean sync = true;

	/**
	 * was a Session event listener registered ?
	 */
	boolean sessionEvListenerRegistered = false;

        /**
         * has a thread been started
         */
        boolean started = false;

        /**
         * maps sessionUID-&lt;SessionListener
         */
        static HashMap listeners = new HashMap();

        /**
         * maps consumerUID to Consumer object
         * NOTE: this is NOT the core.Consumer object
         */
        HashMap consumers = new HashMap();

        /**
         * lock used for notification
         */
        Object sessionLock = new Object();


        /**
         * ListenerObject used for the eventListener on Session
         */
        Object sessionEL = null;

        /**
         * return a SessionListener by session
         */
        public static SessionListener getListener(SessionUID uid)
        {
            synchronized (listeners) {
                return (SessionListener)listeners.get(uid);
            }
        }

        /**
         * create a session listener
         */
        public SessionListener(IMQDirectService svc, Session s) {
            this.parent = svc;
            this.session = s;
            synchronized(listeners) {
                listeners.put(s.getSessionUID(), this);
            }
        }

        /**
         * Synchronous method to retrieve a packet
         */
        public Packet getNextConsumerPacket(ConsumerUID cuid)  {
            return getNextConsumerPacket(cuid, 0);
	}

        /**
         * Synchronous method to retrieve a packet
         */
        public Packet getNextConsumerPacket(ConsumerUID cuid, long timeout)
        {

            // we are either async or sync
            // if this is called from something that registered a message
            // listener, something went wrong. Throw an exception
            if (!sync) throw new RuntimeException("Cannot invoke SessionListener.getNextConsumerPacket() when in asynchronous receiving mode");

            //see if we are busy
            // if we aren't wait

            
            com.sun.messaging.jmq.jmsserver.core.Consumer c = (
                com.sun.messaging.jmq.jmsserver.core.Consumer)
                com.sun.messaging.jmq.jmsserver.core.Consumer.getConsumer(cuid);

            sync = true;

	    /*
	     * If timeout is < 0, this is for receiveNoWait().
	     * If the consumer is not busy() return null right away.
	     */
	    if (timeout < 0)  {
		if (stopped || !c.isBusy())  {
		    return (null);
		}
	    }


                // add an event listener to wake us up when the consumer is busy
                Object lock = c.addEventListener(this,EventType.BUSY_STATE_CHANGED, null);
                while (!c.isBusy() || stopped) {
                    try {
                        // wait until the consumer is not busy
                        synchronized (c.getConsumerUID()) {
                            synchronized(sessionLock) {
                                if (stopped) {
                                    if (timeout > 0) {
                                        long locktime = System.currentTimeMillis();
                                        try {
                                            sessionLock.wait(timeout);
                                        } catch (Exception ex) {}
                                        // adjust the timeout by the wait time
                                        long now = System.currentTimeMillis();
                                        timeout -= now - locktime;
                                        if (timeout <= 0) return null;
                                    } else if (timeout == 0)  {
                                        try {
                                            sessionLock.wait(timeout);
                                        } catch (Exception ex) {}
				    }

				    // Is this needed ?
                                    if (stopped) return null;
                                    
                                }
                            }
                                
                            // we cant check isBusy in the lock because
                            // we can deadlock
                            // instead look in the cuidNotify table
                            if (cuidNotify.remove(c.getConsumerUID()))
                                continue;
			    /*
			     * Just in case between the (timeout < 0) check above and the while loop,
			     * the consumer became not busy, we want to return if it is a noWait case.
			     */
                            if (timeout < 0)  {
                                c.removeEventListener(lock);
                                return (null);
                            }
                            c.getConsumerUID().wait(timeout);
                        }
                        if (stopped) return null;

		        /*
		         * wait() returned but this could be due to a timeout and not 
		         * from notify()
		         *
		         * We check the busy state and also if a non zero timeout was 
			 * specified. If it is indeed a timeout, return null.
		         */
			if (!c.isBusy() && (timeout > 0))  {

                            // remove the event listener since we arent sure if we will care
                            c.removeEventListener(lock);
			    return (null);
			}
                    } catch (Exception ex) {}
                // remove the event listener since we arent sure if we will care
                c.removeEventListener(lock);
            }
            if (stopped) return null;
            synchronized (c.getConsumerUID()) {
                cuidNotify.remove(c.getConsumerUID());
            }

            // now get the packet
            Packet p = new Packet();
            if (session.fillNextPacket(p, cuid))
                return p;

            // this should only happen if something went strange like we
            // have two threads processing @ the same time
            // but just try it again
            return getNextConsumerPacket(cuid, timeout); // recurse

        }

        /**
         * Set this up as an AsyncListener
         */
        public void setAsyncListener(com.sun.messaging.jmq.jmsserver.core.Consumer brokerC, 
                    Consumer target)
        {
            ConsumerUID cuid;

	    /*
	     * Target passed in may be null
	     * This is to indicate that the consumer has unregistered itself as an async
	     * consumer - may go into sync mode.
	     * 
	     */
	    if (target == null)  {
		// XXX Need to stop delivery of msgs - TBD
		//

                // we are no longer asynchronous
                sync = true;

		// Remove Consumer from table
		//
                cuid = brokerC.getConsumerUID();
                consumers.remove(cuid);

		return;
	    }

            // we arent synchronous
            sync = false;

            // put the Consumer into a table so we can retrieve it when 
            // a message is received
            cuid = brokerC.getConsumerUID();
            consumers.put(cuid, target);

            // set up a listener to wake up when the Session is Busy
	    if (!sessionEvListenerRegistered)  {
                sessionEL = session.addEventListener(this, EventType.BUSY_STATE_CHANGED, null);
	        sessionEvListenerRegistered = true;
	    }

            // ok - start the parsing thread
            if (!started) {
                Thread thr = new Thread(this, "Session" + session.getSessionUID());
                thr.start();
                started = true;
            }
        }

        /**
         * Thread run method
         */
        public void run() {
            process();
        }


        // set when a notification is about to occur
        boolean sessionLockNotify = false;

        // set when a consumer notificationis about to occur
        HashSet cuidNotify = new HashSet();


        /**
         * method which handles delivering messages
         */
        public void process() {
           if (sync) {
               // we shouldn't be here
               // there doesnt need to be an external thread
               throw new RuntimeException("Cannot invoke SessionListener.process() when in synchronous receiving mode");
           }
	   valid = true;
           while (valid ) {
                   // if we are not busy, wait for the eventListener to wake us up
                while (valid && (!session.isBusy() || stopped)) {
                   // get the lock 
                   synchronized(sessionLock) {
                       // we cant check isBusy in the loop so
                       // instead check the sessionLockNotify flag
                       if (sessionLockNotify) {
                           sessionLockNotify = false;
                           continue;
                       }
                       try {
                           sessionLock.wait();
                       } catch (Exception ex) {
                           Globals.getLogger().log(Logger.DEBUGHIGH,"Exception in sessionlock wait",
                                     ex);
                       }
                   }
                }
                synchronized(sessionLock) {
                    // we dont care if we are about to notify
                    sessionLockNotify = false;
                }
                if (session.isBusy() && !stopped) {
                       // cool, we have something to do
                       Packet p = new Packet();

                       // retrieve the next packet and its ConsumerUID
                       ConsumerUID uid = session.fillNextPacket(p);

                       if (uid == null) {
                           // weird, something went wrong, try again
                           continue;
                       }

                       // Get the consumer object 
                       Consumer con = (Consumer)consumers.get(uid);
                       try {
			   JMSAck ack = null;

                           // call the deliver method
                           ack = con.deliver(p);

			   if (ack != null)  {
			       long transactionId = ack.getTransactionId(), 
			       consumerId = ack.getConsumerId();
			       SysMessageID sysMsgId = ack.getSysMessageID();
	                       TransactionUID txnUID = null;
	                       ConsumerUID conUID = null;

		               if (transactionId != 0)  {
	                           txnUID = new TransactionUID(transactionId);
		               }

		               if (consumerId != 0)  {
	                           conUID = new ConsumerUID(consumerId);
		               }

                               IMQConnection cxn = parent.checkConnectionId(ack.getConnectionId(), "Listener Thread");

                               SysMessageID ids[] = new SysMessageID[1];
                               //ids[0] = sysMsgId;
                               //ids[0] = ((Packet)p).getSysMessageID();
                               ids[0] = sysMsgId;
                               ConsumerUID cids[] = new ConsumerUID[1];
                               cids[0] = conUID;
                               Globals.getProtocol().acknowledge(cxn, txnUID, false, AckHandler.ACKNOWLEDGE_REQUEST, null, null, 0, ids, cids);
			   }
                       } catch (Exception ex) {
                           // I have no idea what the exception might mean so just
                           // log it and go on
                           Globals.getLogger().log(Logger.ERROR,"Can not deliver message to " + con);
                       }
                   }
                   
           }
        }

        /**
         * This method is called when a thread puts a message on a
         * consumer or session. Its used to wake up the waiting thread
         * which might be in the process() [asynchronous] method or in the 
         * getNextConsumerPacket()[synchronous].
         */
        public void eventOccured(EventType type,  Reason r,
            Object target, Object oldval, Object newval,
            Object userdata) {

            if (type != EventType.BUSY_STATE_CHANGED) {
                return; // should never occur    
            }

            // deal with consumers (the synchronous case)
            if (target instanceof com.sun.messaging.jmq.jmsserver.core.Consumer)
            {
                // notify on the ConsumerUID
                com.sun.messaging.jmq.jmsserver.core.Consumer cc =
                       (com.sun.messaging.jmq.jmsserver.core.Consumer) target;
                ConsumerUID cuid = cc.getConsumerUID();
                if (cc.isBusy()) {
                    synchronized (cuid) {
                        // we cant check isBusy in here - we can deadlock
                        // so instead look in the cuidNotify hashtable

                        cuidNotify.add(cuid);
                        //ok, we want to wake up the thread currently in
                        // getNextConsumerPacket.
                        // it is waiting on the ConsumerUID
                        // so do a notify on ConsumerUID
                            cuid.notifyAll();
                    }
                }
                return;
            }

            // deal with sessions (the async case)
            Session s = (Session)target;

            // this wakes up the process thread which is blocked on SessionLock
            synchronized (sessionLock) {
                sessionLockNotify = true;
                sessionLock.notify();
            }
        }

	/*
	 * Start all sessions for the specified connection
	 */
        public static void startConnection(IMQConnection cxn) {
	    if (cxn == null)  {
		return;
	    }

	    List sessions = cxn.getSessions();

	    if (sessions == null)  {
		return;
	    }

	    Iterator itr = sessions.iterator();
	    while (itr.hasNext()) {
		SessionUID sUID = (SessionUID)itr.next();
                SessionListener sl = getListener(sUID);

		sl.startSession();
	    }
	}

	/*
	 * Stop all sessions for the specified connection
	 */
        public static void stopConnection(IMQConnection cxn) {
	    if (cxn == null)  {
		return;
	    }

	    List sessions = cxn.getSessions();

	    if (sessions == null)  {
		return;
	    }

	    Iterator itr = sessions.iterator();
	    while (itr.hasNext()) {
		SessionUID sUID = (SessionUID)itr.next();
                SessionListener sl = getListener(sUID);
		sl.stopSession();
	    }
	}

	/*
	 * Start this session - set stopped flag to false and wake up waiting thread
	 */
        public void startSession() {
	    stopped = false;
             // wake up the thread (if waiting())
             synchronized (sessionLock) {
                 sessionLock.notify();
             }
	}

	/*
	 * Stop this session - set stopped flag to true
	 */
        public void stopSession() {
	    stopped = true;
             synchronized (sessionLock) {
                 sessionLock.notify();
             }
	}

        public void destroy() {

             // set the shutdown flag
             valid = false;
             // wake up the thread (if waiting())
             synchronized (sessionLock) {
                 sessionLock.notify();
             }

             // remove any even listener
             session.removeEventListener(sessionEL);

             // clean up listeners table
             synchronized(listeners) {
                 listeners.remove(session.getSessionUID());
             }
             // clean up the consumers table
             consumers = null;
        }
 
    }

class QueueBrowserInfo  {
    Destination dest;
    String selector;

    public QueueBrowserInfo(Destination dest, String selector) {
	this.dest = dest;
	this.selector = selector;
    }
}
