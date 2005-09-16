/**
 * JBoss, the OpenSource J2EE WebOS
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.jms.server.endpoint;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.jms.InvalidSelectorException;
import javax.jms.JMSException;

import org.jboss.jms.client.Closeable;
import org.jboss.jms.message.JBossMessage;
import org.jboss.jms.selector.Selector;
import org.jboss.logging.Logger;
import org.jboss.messaging.core.Channel;
import org.jboss.messaging.core.Delivery;
import org.jboss.messaging.core.DeliveryObserver;
import org.jboss.messaging.core.Message;
import org.jboss.messaging.core.Receiver;
import org.jboss.messaging.core.Routable;
import org.jboss.messaging.core.SimpleDelivery;
import org.jboss.remoting.callback.InvokerCallbackHandler;

import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;


/**
 * A Consumer endpoint. Lives on the boundary between Messaging Core and the JMS Facade.
 *
 * It doesn't implement ConsumerDelegate because ConsumerDelegate's methods will never land on the
 * server side, they will be taken care of by the client-side interceptor chain.
 *
 * @author <a href="mailto:ovidiu@jboss.org">Ovidiu Feodorov</a>
 * @version <tt>$Revision$</tt>
 *
 * $Id$
 */
public class ServerConsumerDelegate implements Receiver, Closeable
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(ServerConsumerDelegate.class);

   // Static --------------------------------------------------------

   // Attributes ----------------------------------------------------

   protected String id;
   protected Channel destination;
   protected ServerSessionDelegate sessionEndpoint;
   protected InvokerCallbackHandler callbackHandler;
   protected boolean noLocal;
   protected Selector messageSelector;
  // protected Subscription subscription;
   protected LinkedList waiting = new LinkedList();
	
   protected PooledExecutor threadPool;
   
   protected boolean started;

   // <messageID-Delivery>
   private Map deliveries;
   
   
   // Constructors --------------------------------------------------

   public ServerConsumerDelegate(String id, Channel destination,
                                 InvokerCallbackHandler callbackHandler,
                                 ServerSessionDelegate sessionEndpoint,
                                 String selector, boolean noLocal)
      throws InvalidSelectorException
   {
      if (log.isTraceEnabled()) log.trace("Creating ServerConsumerDelegate:" + id);
      this.id = id;
      this.destination = destination;
      this.sessionEndpoint = sessionEndpoint;
      this.callbackHandler = callbackHandler;
      threadPool = sessionEndpoint.getConnectionEndpoint().getServerPeer().getThreadPool();
      this.noLocal = noLocal;
      if (selector != null)
      {
         if (log.isTraceEnabled()) log.trace("creating selector:" + selector);
         this.messageSelector = new Selector(selector);
         if (log.isTraceEnabled()) log.trace("created selector");
      }
     // this.subscription = subscription;
      deliveries = new HashMap();
      started = sessionEndpoint.connectionEndpoint.started;
      destination.add(this);
   }

   // Receiver implementation ---------------------------------------

   public Delivery handle(DeliveryObserver observer, Routable routable)
   {
      // deliver the message on a different thread than the core thread that brought it here
     
      
      Delivery delivery = null;

      try
      {
         Message message = routable.getMessage();
            
         if (log.isTraceEnabled()) { log.trace("dereferenced message: " + message); }

         boolean accept = this.accept(message);

         if (!accept)
         {
            if (log.isTraceEnabled()) { log.trace("consumer DOES NOT accept the message"); }
            return null;
         }

         delivery = new SimpleDelivery(observer, routable);
         deliveries.put(routable.getMessageID(), delivery);

         synchronized (waiting)
         {
            
            if (started)
            {         
               if (log.isTraceEnabled()) { log.trace("queueing the message " + message + " for delivery"); }
               threadPool.execute(new DeliveryRunnable(callbackHandler, message, log));
            }
            else
            {
               //The consumer is closed so we store the message for later
               //See test ConnectionClosedTest.testCannotReceiveMessageOnClosedConnection
               //for why we do this
               if (log.isTraceEnabled()) { log.trace("Adding message to the waiting list"); }
               waiting.addLast(message);
            }
         }
                  
      }
      catch(InterruptedException e)
      {
         log.warn("Interrupted asynchronous delivery", e);
      }

      return delivery;
   }
   
   
   public boolean accept(Routable r)
   {
      boolean accept = true;
      if (messageSelector != null)
      {
         accept = messageSelector.accept(r);

         if (log.isTraceEnabled())
         {
            log.trace("message selector accepts the message");
         }
      }

      if (accept)
      {
         if (noLocal)
         {
            String conId = ((JBossMessage)r).getConnectionID();
            if (log.isTraceEnabled()) { log.trace("message connection id: " + conId); }
            if (conId != null)
            {
               if (log.isTraceEnabled()) { log.trace("current connection connection id: " + sessionEndpoint.connectionEndpoint.connectionID); }
               accept = !conId.equals(sessionEndpoint.connectionEndpoint.connectionID);
               if (log.isTraceEnabled()) { log.trace("accepting? " + accept); }
            }
         }
      }
      return accept;
   }
  

   // Closeable implementation --------------------------------------

   public void closing() throws JMSException
   {
      if (log.isTraceEnabled()) { log.trace(this.id + " closing"); }
   }

   public void close() throws JMSException
   {
      if (log.isTraceEnabled()) { log.trace(this.id + " close"); }

      remove();
   }
   
   void setStarted(boolean s)
   {
      if (log.isTraceEnabled()) { log.trace("setStarted: " + s); } 
      
      synchronized (waiting)
      {
         started = s;
         
         if (s)
         {
            if (!waiting.isEmpty())
            {
               if (log.isTraceEnabled()) { log.trace("There are " + waiting.size() + " waiting messages to deliver"); }
               
               int n = waiting.size();
               for (int i = 0; i < n; i++)
               {
                  //We remove them one by one, in case it fails mid way through
                  //and we don't want to deliver the message twice on retry
                  Message m = (Message)waiting.removeFirst();
                  
                  if (log.isTraceEnabled()) { log.trace("queueing the message " + m + " for delivery"); }
                  try
                  {
                     threadPool.execute(new DeliveryRunnable(callbackHandler, m, log));
                  }
                  catch (InterruptedException e)
                  {
                     log.error("Thread interrupted", e);
                  }
               }
            }
         }         
      }
   }
   

   // Public --------------------------------------------------------

   void remove()
   {
      if (log.isTraceEnabled()) log.trace("attempting to remove receiver from destination: " + destination);
 
      for(Iterator i = deliveries.keySet().iterator(); i.hasNext(); )
      {
         Object messageID = i.next();
         Delivery d = (Delivery)deliveries.get(messageID);
         try
         {
            d.cancel();
         }
         catch(Throwable t)
         {
            log.error("Cannot cancel delivery: " + d, t);
         }
         i.remove();
      }
      
      boolean removed = destination.remove(this);
      
      if (log.isTraceEnabled()) log.trace("receiver " + (removed ? "" : "NOT ")  + "removed");
      
      
      this.sessionEndpoint.connectionEndpoint.receivers.remove(id);
      this.sessionEndpoint.consumers.remove(id);
      
   }   
   
   void acknowledge(String messageID)
   {
      try
      {        
         Delivery d = (Delivery)deliveries.get(messageID);
         d.acknowledge();
         deliveries.remove(messageID);
         
         //FIXME
         //Previously message references were not being removed from the message store after the
         //message had been delivered, causing a catastrophic memory leak and very quick server
         //failure under load.
         //This is a quick and dirty fix to remove the reference after delivery
         //I imagine it could be integrated into core better
         this.sessionEndpoint.connectionEndpoint.getServerPeer().getMessageStore().removeReference(messageID);
         
      }
      catch(Throwable t)
      {
         log.error("Message " + messageID + "cannot be acknowledged to the source");
      }
   }


   // Package protected ---------------------------------------------
   
   void redeliver() throws JMSException
   {
      // TODO I need to do this atomically, otherwise only some of the messages may be redelivered
      // TODO and some old deliveries may be lost

      if (log.isTraceEnabled()) { log.trace("redeliver"); }                        
      
      List old = new ArrayList();
      synchronized(deliveries)
      {

         for(Iterator i = deliveries.keySet().iterator(); i.hasNext();)
         {
            old.add(deliveries.get(i.next()));
            i.remove();
         }
      }
      
      if (log.isTraceEnabled()) { log.trace("There are " + old.size() + " deliveries to redeliver"); }

      for(Iterator i = old.iterator(); i.hasNext();)
      {
         try
         {
            Delivery d = (Delivery)i.next();
            d.redeliver(this);
         }
         catch(Throwable t)
         {
            String msg = "Failed to initiate redelivery";
            log.error(msg, t);
            throw new JMSException(msg);
         }
      }
      
   }

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------
}
