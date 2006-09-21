/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.messaging.core.plugin.postoffice.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;

import org.jboss.messaging.core.Message;
import org.jboss.messaging.core.message.MessageFactory;
import org.jboss.messaging.util.StreamUtils;

/**
 * A MessageRequest
 * 
 * Used when sending a single message non reliably across the group
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @version <tt>$Revision: 1.1 $</tt>
 *
 * $Id$
 *
 */
class MessageRequest extends ClusterRequest
{
   static final int TYPE = 3;
   
   private String routingKey;   
   
   private Message message;
   
   private Map queueNameNodeIdMap;
   
   MessageRequest()
   {      
   }
   
   MessageRequest(String routingKey, Message message, Map queueNameNodeIdMap)
   {
      this.routingKey = routingKey;
      
      this.message = message;
      
      this.queueNameNodeIdMap = queueNameNodeIdMap;
   }
   
   Object execute(PostOfficeInternal office) throws Exception
   {
      office.routeFromCluster(message, routingKey, queueNameNodeIdMap);      
      return null;
   }  
   
   byte getType()
   {
      return TYPE;
   }
   
   public void read(DataInputStream in) throws Exception
   {
      routingKey = in.readUTF();
      
      byte type = in.readByte();
      message = MessageFactory.createMessage(type);
      message.read(in);

      queueNameNodeIdMap = (Map)StreamUtils.readObject(in, false);          
   }

   public void write(DataOutputStream out) throws Exception
   {
      out.writeUTF(routingKey);
      
      out.writeByte(message.getType());      
      message.write(out);
      
      StreamUtils.writeObject(out, queueNameNodeIdMap, true, false);
   }
}
