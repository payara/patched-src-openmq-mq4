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
 * @(#)ResetMetricsHandler.java	1.7 07/12/07
 */ 

package com.sun.messaging.jmq.jmsserver.data.handlers.admin;

import java.util.Hashtable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Vector;

import com.sun.messaging.jmq.io.Packet;
import com.sun.messaging.jmq.jmsserver.service.HAMonitorService;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.util.MetricManager;
import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.util.DestType;
import com.sun.messaging.jmq.util.admin.MessageType;
import com.sun.messaging.jmq.util.admin.MessageType;
import com.sun.messaging.jmq.util.admin.BrokerInfo;
import com.sun.messaging.jmq.util.log.Logger;
import com.sun.messaging.jmq.util.ServiceType;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.config.*;
import com.sun.messaging.jmq.jmsserver.service.ServiceManager;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQService;
import com.sun.messaging.jmq.jmsserver.management.agent.Agent;
import java.util.*;


public class ResetMetricsHandler extends AdminCmdHandler
{
    private static boolean DEBUG = getDEBUG();

    public ResetMetricsHandler(AdminDataHandler parent) {
	super(parent);
    }

    /**
     * Handle the incomming administration message.
     *
     * @param con	The Connection the message came in on.
     * @param cmd_msg	The administration message
     * @param cmd_props The properties from the administration message
     */

    public static void resetAllMetrics()
    {
        // reset destination
        Destination.resetAllMetrics();

        // reset all services
        List services = Globals.getServiceManager().getAllServiceNames();
        Iterator itr = services.iterator();
        while (itr.hasNext()) {
            String name = (String)itr.next();
            IMQService service = (IMQService)Globals.getServiceManager().getService(name);
            if (service != null)
                service.resetCounters();
        }

        // reset metrics manager
        MetricManager mm = Globals.getMetricManager();
        mm.reset();

	/*
	 * Reset metrics that are kept track of by JMX MBeans
	 */
	Agent agent = Globals.getAgent();
	if (agent != null)  {
	    agent.resetMetrics();
	}
    }

    public boolean handle(IMQConnection con, Packet cmd_msg,
				       Hashtable cmd_props) {

	if ( DEBUG ) {
            logger.log(Logger.DEBUG, this.getClass().getName() + ": " +
                cmd_props);
        }

        int status = Status.OK;
        String errMsg = null;

        HAMonitorService hamonitor = Globals.getHAMonitorService(); 
        if (hamonitor != null && hamonitor.inTakeover()) {
            status = Status.ERROR;
            errMsg =  rb.getString(rb.E_CANNOT_PROCEED_TAKEOVER_IN_PROCESS);

            logger.log(Logger.ERROR, this.getClass().getName() + ": " + errMsg);
	} else  {
	String resetType = (String)cmd_props.get(MessageType.JMQ_RESET_TYPE);

	if ((resetType == null) || (resetType.equals("")))  {
            logger.log(Logger.INFO, rb.I_RESET_BROKER_METRICS);
            resetAllMetrics();
	} else  {
	    if (MessageType.JMQ_METRICS.equals(resetType))  {
                logger.log(Logger.INFO, rb.I_RESET_BROKER_METRICS);
                resetAllMetrics();
	    } else  {
		logger.log(Logger.ERROR, rb.E_INVALID_RESET_BROKER_TYPE, resetType);
		errMsg = rb.getString(rb.E_INVALID_RESET_BROKER_TYPE, resetType);
		status = Status.ERROR;
	    }
	}
	}

	// Send reply
	Packet reply = new Packet(con.useDirectBuffers());
	reply.setPacketType(PacketType.OBJECT_MESSAGE);

	setProperties(reply, MessageType.RESET_BROKER_REPLY, status, errMsg,
              null);

	parent.sendReply(con, cmd_msg, reply);
    return true;
    }
}
