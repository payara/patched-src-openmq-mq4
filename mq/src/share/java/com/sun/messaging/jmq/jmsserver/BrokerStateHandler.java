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
 * @(#)BrokerStateHandler.java	1.42 07/11/07
 */ 

package com.sun.messaging.jmq.jmsserver;

import com.sun.messaging.jmq.jmsserver.cluster.*;
import com.sun.messaging.jmq.jmsserver.cluster.ha.HAClusteredBroker;
import com.sun.messaging.jmq.jmsservice.BrokerEvent;
import com.sun.messaging.jmq.jmsserver.service.*;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.util.log.*;
import com.sun.messaging.jmq.io.MQAddress;
import com.sun.messaging.jmq.util.GoodbyeReason;
import com.sun.messaging.jmq.jmsserver.core.ClusterBroadcast;
import com.sun.messaging.jmq.jmsserver.data.TransactionList;
import com.sun.messaging.jmq.jmsserver.data.handlers.InfoRequestHandler;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.util.MQThread;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.management.agent.Agent;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.service.ConnectionManager;
import com.sun.messaging.jmq.util.DiagManager;
import com.sun.messaging.jmq.util.ServiceType;
import com.sun.messaging.jmq.util.UID;
import com.sun.messaging.bridge.BridgeServiceManager;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Class which handles shutting down and quiescing a broker.
 * <P>
 * <b>XXX</b> tasks to do:
 *  <UL> <LI>shutdown timeout</LI>
 *       <LI>Wait for queisce to complete</LI>
 *       <LI>handle dont takeover flag</LI>
 *  </UL>
 */

public class BrokerStateHandler
{

     private Logger logger = Globals.getLogger();
     private BrokerResources br = Globals.getBrokerResources();

     public static boolean shuttingDown = false;


     public static boolean shutdownStarted = false;

     public static Thread shutdownThread = null;
     public static boolean storeShutdownStage0 = false;
     public static boolean storeShutdownStage1 = false;
     public static boolean storeShutdownStage2 = false;

     QuiesceRunnable qrun = null;
     boolean prepared = false;


     long targetShutdownTime = 0;

     private static int restartCode = Globals.getConfig().getIntProperty(
               Globals.IMQ +".restart.code", 255);

     ClusterListener cl = new StateMonitorListener();

     public BrokerStateHandler() {
         Globals.getClusterManager().addEventListener(cl);
     }

     public void destroy() {
         Globals.getClusterManager().addEventListener(cl);
     }


     public static int getRestartCode() {
         return restartCode;
     }
     

     public long getShutdownRemaining() {
         if (targetShutdownTime == 0) return -1;
         long remaining = targetShutdownTime - System.currentTimeMillis();
         if (remaining < 0) remaining = 0;
         return remaining;
    }

    public void takeoverBroker(String brokerID, boolean force)
        throws BrokerException
    {
        ClusterManager cm = Globals.getClusterManager();
        if (!cm.isHA()) {
            throw new BrokerException( 
               Globals.getBrokerResources().getKString(
               BrokerResources.X_NONHA_NO_TAKEOVER_SUPPORT));
        } else {
            HAClusteredBroker hcb = (HAClusteredBroker)
                          cm.getBroker(brokerID);
            if (hcb == null) {
                throw new BrokerException(
                    Globals.getBrokerResources().getKString(
                    BrokerResources.X_UNKNOWN_BROKERID, brokerID));
            } else if (hcb.isLocalBroker()) {
                throw new BrokerException(
                    Globals.getBrokerResources().getKString(
                    BrokerResources.X_CANNOT_TAKEOVER_SELF));
            } else {
                Globals.getHAMonitorService().takeoverBroker(hcb, force);
            }
        }

    }
    

    /**
     * Stop allowing new jms connections to the broker.
     * This allows an administrator to "drain" the broker before
     * shutting it down to prevent the loss of non-persistent  
     * state.
     */
    public void quiesce() 
       throws IllegalStateException, BrokerException
   {
        if (qrun != null) {
            // throw exception
            throw new IllegalStateException("Already Quiescing");
        }
        synchronized (this) {
            qrun = new QuiesceRunnable();
        }
        Thread thr = new MQThread(qrun,
                                "quiesce thread");
        thr.start();
    }

    /**
     * Start allowing new jms connections to the broker.
     * This allows an administrator to stop the "drain" the broker before
     * shutting it down to prevent the loss of non-persistent  
     * state.
     */
    public void stopQuiesce() 
        throws BrokerException
    {
         try {
             // if we are in the process of quiescing, stop it
             // then un-quiesce
             QuiesceRunnable qr = null;
             synchronized (this) {
                 qr = qrun;
             }
             if (qr != null) 
                qr.breakQuiesce(); // stop quiesce
             // now, unquiesce

             // stop accepting new jms threads
             ServiceManager sm = Globals.getServiceManager();
             sm.startNewConnections(ServiceType.NORMAL);

             ClusteredBroker cb = Globals.getClusterManager().getLocalBroker();
             cb.setState(BrokerState.OPERATING);
             logger.log(Logger.INFO, BrokerResources.I_UNQUIESCE_DONE);
         } catch (Exception ex) {
              Globals.getLogger().logStack(Logger.WARNING,
                       BrokerResources.E_INTERNAL_BROKER_ERROR,
                       "exception during unquiesce", ex); 
              throw new BrokerException(
                       Globals.getBrokerResources().getKString(
                          BrokerResources.E_INTERNAL_BROKER_ERROR,
                          "unable to unquiesce"), ex);
         }
    }

    /**
     * shutdown down the broker at the specific time.
     * @param time milliseconds delay before starting shutdown
     *             or 0 if no delay
     * @param requestedBy why is the broker shutting down
     * @param exitCode exitcode to use on shutdown
     * @param threadOff if true, run in a seperate thread
     * @param noFailover if true, the broker does not want
     *             another broker to take over its store.
     * @param exit should we should really exit
     */
    public void initiateShutdown(String requestedBy, long time,
                      boolean triggerFailover, int exitCode, boolean threadOff)
    {
        initiateShutdown(requestedBy, time, triggerFailover,
              exitCode, threadOff, true, true);
    }
    public void initiateShutdown(String requestedBy, long time,
                      boolean triggerFailover, int exitCode, boolean threadOff,
                      boolean exit, boolean cleanupJMX) 
    {
    	
    	
    	
        synchronized (this) {
            if (shutdownStarted)  {
                if (targetShutdownTime > 0) {
                   if (time > 0) {
                         targetShutdownTime = System.currentTimeMillis() + 
                              time;
                   } else {
                         targetShutdownTime = 0;
                   }
                    
                   this.notifyAll();
                }
                return;
            }
            shutdownStarted = true;
        }

	Agent agent = Globals.getAgent();
	if (agent != null)  {
	    agent.notifyShutdownStart();
	}

        if (time > 0) {
            targetShutdownTime = System.currentTimeMillis() + 
                         time;
        } else {
            targetShutdownTime = 0;
        }
        ShutdownRunnable runner = new ShutdownRunnable(requestedBy, targetShutdownTime,
                        triggerFailover, exitCode, cleanupJMX);
        if (threadOff) {
            Thread thr = new MQThread(runner,
                                "shutdown thread");
            thr.start();
        } else {
            int shutdown = runner.shutdown(); // run in current thread
            if (exit)
                System.exit(shutdown);
        }
    }

    public class QuiesceRunnable implements Runnable
    {
        boolean breakQuiesce = false;

        public QuiesceRunnable() 
            throws BrokerException
        {
            logger.log(Logger.INFO,
                BrokerResources.I_QUIESCE_START);

	    Agent agent = Globals.getAgent();
	    if (agent != null)  {
	        agent.notifyQuiesceStart();
	    }

            try {
                ClusteredBroker cb = Globals.getClusterManager().getLocalBroker();
                cb.setState(BrokerState.QUIESCE_STARTED);

                // stop accepting new jms threads
                ServiceManager sm = Globals.getServiceManager();
                sm.stopNewConnections(ServiceType.NORMAL);
            } catch (Exception ex) {
                throw new BrokerException(
                    BrokerResources.X_QUIESCE_FAILED, ex);
            }
        }
        public void run() {
            try {

                // ok, now wait until connection count goes to 0 and 
                // message count goes to 0

               // we are going to poll (vs trying to get a notification) because
               // I dont want to worry about a possible deadlock

               synchronized (this) {
                    while (!breakQuiesce) {
                        // XXX - check state
                        // if OK, break
                        ServiceManager smgr = Globals.getServiceManager();
                        int ccnt = smgr.getConnectionCount(ServiceType.NORMAL) ;
                        int msgcnt = Destination.totalCountNonPersist();
                        if (ccnt == 0 && msgcnt == 0) {
                            break;
                        }
                        logger.log(logger.INFO, 
                               br.getKString(BrokerResources.I_MONITOR_QUIESCING, ccnt, msgcnt));
                        this.wait(10*1000);
                    }
               }
               if (!breakQuiesce)  {      
                    ClusteredBroker cb = Globals.getClusterManager().getLocalBroker();
                    cb.setState(BrokerState.QUIESCE_COMPLETED);
               }
               logger.log(Logger.INFO, BrokerResources.I_QUIESCE_DONE);
               synchronized(this) {
                    qrun = null; // we are done
                }
	       Agent agent = Globals.getAgent();
	       if (agent != null)  {
	           agent.notifyQuiesceComplete();
	       }

            } catch (Exception ex) {
                Globals.getLogger().logStack(
                    Logger.WARNING, BrokerResources.E_INTERNAL_BROKER_ERROR,
                    "quiescing broker ", ex); 
            }

       }

       public synchronized void breakQuiesce() {
            breakQuiesce = true;
            notify();
       }

   }

    public void prepareShutdown(boolean failover) {
        prepared = true;

        BridgeServiceManager bridgeManager =  Globals.getBridgeServiceManager();
        if (bridgeManager != null) {
            try {
                Globals.getLogger().log(Logger.INFO,
                        Globals.getBrokerResources().I_STOP_BRIDGE_SERVICE_MANAGER);

                bridgeManager.stop();
                Globals.setBridgeServiceManager(null);

                Globals.getLogger().log(Logger.INFO,
                        Globals.getBrokerResources().I_STOPPED_BRIDGE_SERVICE_MANAGER);
            } catch (Throwable t) {
                logger.logStack(Logger.WARNING,
                       Globals.getBrokerResources().W_STOP_BRIDGE_SERVICE_MANAGER_FAILED, t);
            }
        }

        if (Globals.getMemManager() != null)
            Globals.getMemManager().stopManagement();

        // First stop creating new destinations
        Destination.shutdown();

        // Next, close all the connections with clustered brokers
        // so that we don't get stuck processing remote events..

        // XXX - tell cluster whether or not failover should happen
        Globals.getClusterBroadcast().stopClusterIO(failover);
    }


    public class ShutdownRunnable implements Runnable
    {

        String requestedBy = "unknown";
        long targetTime = 0;
        int exitCode = 0;
        boolean failover = false;
        boolean cleanupJMX = false;

        public ShutdownRunnable(String who, long target, boolean trigger,
                      int exitCode, boolean cleanupJMX)
        {
            logger.log(Logger.DEBUG,"Shutdown requested by " + who);
            requestedBy = who;
            this.targetTime = target;
            this.failover = trigger;
            this.exitCode = exitCode;
            this.cleanupJMX = cleanupJMX;
        }

        public void run() {
            int exit = shutdown();
            Broker.getBroker().exit(exit,
                Globals.getBrokerResources().getKString(
                     BrokerResources.I_SHUTDOWN_REQ, requestedBy),
                (exitCode == getRestartCode()) ?
                    BrokerEvent.Type.RESTART :
                    BrokerEvent.Type.SHUTDOWN);
        }

        public int shutdown() {
            ClusteredBroker cb = null;
            BrokerState state = null;
            try {
                shutdownThread = Thread.currentThread();
                storeShutdownStage0 = true;
                cb = Globals.getClusterManager().getLocalBroker();
                try {
                    state = cb.getState();
                    if (state != BrokerState.FAILOVER_STARTED
                       && state != BrokerState.FAILOVER_PENDING
                       && state != BrokerState.FAILOVER_COMPLETE ) {
                        cb.setState(BrokerState.SHUTDOWN_STARTED);
                    }
                } catch (Throwable t) {
                    // Just log the error & continue
                    Globals.getLogger().logStack(
                        Logger.WARNING, BrokerResources.E_SHUTDOWN, t);
                }

                storeShutdownStage1 = true;
                storeShutdownStage0 = false;

                if (getShutdownRemaining() > 0) {
                    logger.log(Logger.INFO,
                            BrokerResources.I_SHUTDOWN_IN_SEC,
                            String.valueOf(getShutdownRemaining()/1000),
                            String.valueOf(getShutdownRemaining()));
                    // notify client
                    List l = Globals.getConnectionManager()
                              .getConnectionList(null);
                    Iterator itr = l.iterator();
                    while (itr.hasNext()) {
                        IMQConnection c = (IMQConnection)itr.next();
                        if (!c.isAdminConnection() && 
                            c.getClientProtocolVersion() >= 
                                  Connection.HAWK_PROTOCOL) {
                             InfoRequestHandler.sendInfoPacket(
                                   InfoRequestHandler.REQUEST_STATUS_INFO, c, 0);
                        }
                    }
                                      
                    synchronized (BrokerStateHandler.this) {
                        try {
                            logger.log(Logger.INFO,
                                  BrokerResources.I_SHUTDOWN_AT,
                                  (new Date(targetShutdownTime)).toString());
                            BrokerStateHandler.this.wait(getShutdownRemaining());
                        } catch (Exception ex) {
                        }
                    }
                }

                // XXX should this be updated to include why ???
                Globals.getLogger().logToAll(Logger.INFO,
                    Globals.getBrokerResources().getKString(
                     BrokerResources.I_SHUTDOWN_BROKER)+"["+requestedBy+"]");

                if (Broker.getBroker().getDiagInterval() == 0) {
                    // Log diagnostics at shutdown
                    Globals.getLogger().log(Logger.INFO, DiagManager.allToString());
                }

                shuttingDown = true;
                shutdownStarted = true;

                prepareShutdown(failover); // in case not called yet

                ServiceManager sm = Globals.getServiceManager();

                // OK .. first stop sending anything out
                sm.stopNewConnections(ServiceType.ADMIN);

                ConnectionManager cmgr = Globals.getConnectionManager();

                Globals.getLogger().logToAll(Logger.INFO,
                                         BrokerResources.I_BROADCAST_GOODBYE);
                int id = GoodbyeReason.SHUTDOWN_BKR;
                String msg =
                    Globals.getBrokerResources().getKString(
                         BrokerResources.M_ADMIN_REQ_SHUTDOWN,
                          requestedBy);

                if (exitCode == getRestartCode()) {
                    id = GoodbyeReason.RESTART_BKR;
                    msg = Globals.getBrokerResources().getKString(
                             BrokerResources.M_ADMIN_REQ_RESTART,
                              requestedBy);
                }
                cmgr.broadcastGoodbye(id, msg);

                Globals.getLogger().logToAll(Logger.INFO,
                                         BrokerResources.I_FLUSH_GOODBYE);
                cmgr.flushControlMessages(1000);
    
                // XXX - should be notify other brokers we are going down ?

                sm.stopAllActiveServices(true);

                TransactionList tlist = Globals.getTransactionList(); 
                if (tlist != null) {
                    tlist.destroy();
                }

   	        // stop JMX connectors
                if (cleanupJMX) {
	            Agent agent = Globals.getAgent();
	            if (agent != null)  {
	                agent.stop();
		        agent.unloadMBeans();
	            }
                } else {
                    Globals.getLogger().log(Logger.INFO,
                        BrokerResources.I_JMX_NO_SHUTDOWN);
                }
            } catch (Exception ex) {
                Globals.getLogger().logStack(
                    Logger.WARNING, BrokerResources.E_SHUTDOWN, ex); 
                // XXX do we do this if we are already in the exit thread ???
                return 1;
            } finally {
                storeShutdownStage2 = true;
                storeShutdownStage1 = false;
                try {
                    if (cb != null && (
                      state != BrokerState.FAILOVER_STARTED
                      && state != BrokerState.FAILOVER_PENDING
                      && state != BrokerState.FAILOVER_COMPLETE ))
                    {
                        try {
                            if (failover) {
                                cb.setState(BrokerState.SHUTDOWN_FAILOVER);
                            } else {
                                cb.setState(BrokerState.SHUTDOWN_COMPLETE);
                            }
                        } catch (Throwable t) {
                            // Just log the error & continue
                            Globals.getLogger().logStack(
                                Logger.WARNING, BrokerResources.E_SHUTDOWN, t);
                        }
                    }
                    storeShutdownStage2 = false;
                    storeShutdownStage1 = true;
                    // close down the persistence database
                    Globals.releaseStore();
                } catch  (Exception ex) {
                    Globals.getLogger().logStack(
                        Logger.WARNING, BrokerResources.E_SHUTDOWN, ex); 
                    // XXX do we do this if we are already in the exit thread ???
                    return 1;
                }

            }
            Globals.getPortMapper().destroy();

            Globals.getLogger().logToAll(Logger.INFO,
                     BrokerResources.I_SHUTDOWN_COMPLETE);

            if (exitCode == getRestartCode())
                Globals.getLogger().log(Logger.INFO,
                    BrokerResources.I_BROKER_RESTART);

            // XXX do we do this if we are already in the exit thread ???
           return exitCode;
       }
   }


    /**
     * listener who handles sending cluster info back to the client
     */
    class StateMonitorListener implements ClusterListener
    {
       // send cluster information to all 4.0 or later clients
       void notifyClients() {
            List l = Globals.getConnectionManager()
                              .getConnectionList(null);
            Iterator itr = l.iterator();
            while (itr.hasNext()) {
                IMQConnection c = (IMQConnection)itr.next();
                if (!c.isAdminConnection() &&  
                        c.getClientProtocolVersion() >=
                        Connection.HAWK_PROTOCOL) {
                    InfoRequestHandler.sendInfoPacket(
                        InfoRequestHandler.REQUEST_CLUSTER_INFO,
                        c, 0);
                }
 
            }
       }


       /**
        * Called to notify ClusterListeners when the cluster service
        * configuration. Configuration changes include:
        * <UL><LI>cluster service port</LI>
        *     <LI>cluster service hostname</LI>
        *     <LI>cluster service transport</LI>
        * </UL><P>
        *
        * @param name the name of the changed property
        * @param value the new value of the changed property
        */
        public void clusterPropertyChanged(String name, String value)
        {
            // we dont care
        }

    
    
       /**
        * Called when a new broker has been added.
        * @param brokerSession uid associated with the added broker
        * @param broker the new broker added.
        */
        public void brokerAdded(ClusteredBroker broker, UID brokerSession)
        {
             notifyClients();
        }

    
       /**
        * Called when a broker has been removed.
        * @param broker the broker removed.
        * @param brokerSession uid associated with the removed broker
        */
        public void brokerRemoved(ClusteredBroker broker, UID brokerSession)
        {
             notifyClients();
        }

    
       /**
        * Called when the broker who is the master broker changes
        * (because of a reload properties).
        * @param oldMaster the previous master broker.
        * @param newMaster the new master broker.
        */
        public void masterBrokerChanged(ClusteredBroker oldMaster,
                        ClusteredBroker newMaster)
        {
            // we dont care
        }

    
       /**
        * Called when the status of a broker has changed. The
        * status may not be accurate if a previous listener updated
        * the status for this specific broker.
        * @param brokerid the name of the broker updated.
        * @param oldStatus the previous status.
        * @param brokerSession uid associated with the change
        * @param newStatus the new status.
        * @param userData data associated with the state change
        */
        public void brokerStatusChanged(String brokerid,
                      int oldStatus, int newStatus, UID brokerSession,
                      Object userData)
        {
            // we dont care
        }

    
       /**
        * Called when the state of a broker has changed. The
        * state may not be accurate if a previous listener updated
        * the state for this specific broker.
        * @param brokerid the name of the broker updated.
        * @param oldState the previous state.
        * @param newState the new state.
        */
        public void brokerStateChanged(String brokerid,
                      BrokerState oldState, BrokerState newState)
        {
            // we dont care
        }

    
       /**
        * Called when the version of a broker has changed. The
        * state may not be accurate if a previous listener updated
        * the version for this specific broker.
        * @param brokerid the name of the broker updated.
        * @param oldVersion the previous version.
        * @param newVersion the new version.
        */
        public void brokerVersionChanged(String brokerid,
                      int oldVersion, int newVersion)
        {
            // we dont care
        }
    
       /**
        * Called when the url address of a broker has changed. The
        * address may not be accurate if a previous listener updated
        * the address for this specific broker.
        * @param brokerid the name of the broker updated.
        * @param newAddress the previous address.
        * @param oldAddress the new address.
        */
        public void brokerURLChanged(String brokerid,
                      MQAddress oldAddress, MQAddress newAddress)
        {
             notifyClients();
        }

    
    
    }    
    
}


