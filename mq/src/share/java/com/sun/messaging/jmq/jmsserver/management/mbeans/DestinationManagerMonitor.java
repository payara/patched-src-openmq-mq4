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
 * @(#)DestinationManagerMonitor.java	1.14 06/28/07
 */ 

package com.sun.messaging.jmq.jmsserver.management.mbeans;

import java.util.List;

import javax.management.ObjectName;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanException;

import com.sun.messaging.jms.management.server.*;

import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.management.util.DestinationUtil;

public class DestinationManagerMonitor extends MQMBeanReadOnly  {
    private static MBeanAttributeInfo[] attrs = {
	    new MBeanAttributeInfo(DestinationAttributes.NUM_DESTINATIONS,
					Integer.class.getName(),
		                        mbr.getString(mbr.I_DST_MGR_ATTR_NUM_DESTINATIONS),
					true,
					false,
					false),

	    new MBeanAttributeInfo(DestinationAttributes.NUM_MSGS,
					Long.class.getName(),
		                        mbr.getString(mbr.I_DST_MGR_ATTR_NUM_MSGS),
					true,
					false,
					false),

	    new MBeanAttributeInfo(DestinationAttributes.NUM_MSGS_IN_DMQ,
					Long.class.getName(),
		                        mbr.getString(mbr.I_DST_MGR_ATTR_NUM_MSGS_IN_DMQ),
					true,
					false,
					false),

	    new MBeanAttributeInfo(DestinationAttributes.TOTAL_MSG_BYTES,
					Long.class.getName(),
		                        mbr.getString(mbr.I_DST_MGR_ATTR_TOTAL_MSG_BYTES),
					true,
					false,
					false),

	    new MBeanAttributeInfo(DestinationAttributes.TOTAL_MSG_BYTES_IN_DMQ,
					Long.class.getName(),
		                        mbr.getString(mbr.I_DST_MGR_ATTR_TOTAL_MSG_BYTES_IN_DMQ),
					true,
					false,
					false)
			};

    private static MBeanOperationInfo[] ops = {
	    new MBeanOperationInfo(DestinationOperations.GET_DESTINATIONS,
	        mbr.getString(mbr.I_DST_MGR_MON_OP_GET_DESTINATIONS),
		null , 
		ObjectName[].class.getName(),
		MBeanOperationInfo.INFO)
		    };
	
    private static String[] dstNotificationTypes = {
		    DestinationNotification.DESTINATION_COMPACT,
		    DestinationNotification.DESTINATION_CREATE,
		    DestinationNotification.DESTINATION_DESTROY,
		    DestinationNotification.DESTINATION_PAUSE,
		    DestinationNotification.DESTINATION_PURGE,
		    DestinationNotification.DESTINATION_RESUME
		};

    private static MBeanNotificationInfo[] notifs = {
	    new MBeanNotificationInfo(
		    dstNotificationTypes,
		    DestinationNotification.class.getName(),
		    mbr.getString(mbr.I_DST_NOTIFICATIONS)
		    )
		};


    public DestinationManagerMonitor()  {
        super();
    }

    public Integer getNumDestinations()  {
	List l = DestinationUtil.getVisibleDestinations();

	return (new Integer(l.size()));
    }

    public Long getNumMsgs()  {
	return (new Long(Destination.totalCount()));
    }

    public Long getNumMsgsInDMQ()  {
	return (new Long(Destination.getDMQ().size()));
    }

    public Long getTotalMsgBytes()  {
	return (new Long(Destination.totalBytes()));
    }

    public Long getTotalMsgBytesInDMQ()  {
	return (new Long(Destination.getDMQ().byteSize()));
    }

    public ObjectName[] getDestinations() throws MBeanException  {
	List dests = DestinationUtil.getVisibleDestinations();

	if (dests.size() == 0)  {
	    return (null);
	}

	ObjectName destONames[] = new ObjectName [ dests.size() ];

	for (int i =0; i < dests.size(); i ++) {
	    Destination d = (Destination)dests.get(i);

	    try  {
	        ObjectName o = MQObjectName.createDestinationMonitor(
				d.isQueue() ? DestinationType.QUEUE : DestinationType.TOPIC,
				d.getDestinationName());

	        destONames[i] = o;
	    } catch (Exception e)  {
		handleOperationException(DestinationOperations.GET_DESTINATIONS, e);
	    }
        }

	return (destONames);
    }

    public String getMBeanName()  {
	return ("DestinationManagerMonitor");
    }

    public String getMBeanDescription()  {
	return (mbr.getString(mbr.I_DST_MGR_MON_DESC));
    }

    public MBeanAttributeInfo[] getMBeanAttributeInfo()  {
	return (attrs);
    }

    public MBeanOperationInfo[] getMBeanOperationInfo()  {
	return (ops);
    }

    public MBeanNotificationInfo[] getMBeanNotificationInfo()  {
	return (notifs);
    }

    public void notifyDestinationCompact(Destination d)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_COMPACT, 
			this, sequenceNumber++);

	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);

	sendNotification(n);
    }

    public void notifyDestinationCreate(Destination d)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_CREATE, 
			this, sequenceNumber++);

	boolean b = !(d.isAutoCreated() || d.isInternal() || d.isDMQ() || d.isAdmin());

	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);
	n.setCreatedByAdmin(b);

	sendNotification(n);
    }

    public void notifyDestinationDestroy(Destination d)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_DESTROY, 
			this, sequenceNumber++);
	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);

	sendNotification(n);
    }

    public void notifyDestinationPause(Destination d, String pauseType)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_PAUSE, 
			this, sequenceNumber++);
	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);
	n.setPauseType(pauseType);

	sendNotification(n);
    }

    public void notifyDestinationPurge(Destination d)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_PURGE, 
			this, sequenceNumber++);
	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);

	sendNotification(n);
    }

    public void notifyDestinationResume(Destination d)  {
	DestinationNotification n;
	n = new DestinationNotification(
			DestinationNotification.DESTINATION_RESUME,
			this, sequenceNumber++);
	n.setDestinationName(d.getDestinationName());
	n.setDestinationType(d.isQueue() ? 
			DestinationType.QUEUE : DestinationType.TOPIC);

	sendNotification(n);
    }


}