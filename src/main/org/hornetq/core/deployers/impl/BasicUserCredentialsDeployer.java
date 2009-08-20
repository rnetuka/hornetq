/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.core.deployers.impl;

import org.hornetq.core.deployers.DeploymentManager;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.security.HornetQSecurityManager;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 
 * deployer for adding security loaded from the file "hornetq-users.xml"
 * @author <a href="ataylor@redhat.com">Andy Taylor</a>
 */
public class BasicUserCredentialsDeployer extends XmlDeployer
{
   private static final Logger log = Logger.getLogger(BasicUserCredentialsDeployer.class);

   private final HornetQSecurityManager hornetQSecurityManager;

   private static final String PASSWORD_ATTRIBUTE = "password";

   private static final String ROLES_NODE = "role";

   private static final String ROLE_ATTR_NAME = "name";

   private static final String DEFAULT_USER = "defaultuser";

   private static final String USER = "user";

   public BasicUserCredentialsDeployer(final DeploymentManager deploymentManager,
                                       final HornetQSecurityManager hornetQSecurityManager)
   {
      super(deploymentManager);

      this.hornetQSecurityManager = hornetQSecurityManager;
   }

   public String[] getElementTagName()
   {
      return new String[] { DEFAULT_USER, USER };
   }

   @Override
   public void validate(Node rootNode) throws Exception
   {
      org.hornetq.utils.XMLUtil.validate(rootNode, "schema/hornetq-users.xsd");
   }

   public void deploy(final Node node) throws Exception
   {
      String username = node.getAttributes().getNamedItem(getKeyAttribute()).getNodeValue();
      String password = node.getAttributes().getNamedItem(PASSWORD_ATTRIBUTE).getNodeValue();

      // add the user
      hornetQSecurityManager.addUser(username, password);
      String nodeName = node.getNodeName();
      if (DEFAULT_USER.equalsIgnoreCase(nodeName))
      {
         hornetQSecurityManager.setDefaultUser(username);
      }
      NodeList children = node.getChildNodes();
      for (int i = 0; i < children.getLength(); i++)
      {
         Node child = children.item(i);
         // and add any roles
         if (ROLES_NODE.equalsIgnoreCase(child.getNodeName()))
         {
            String role = child.getAttributes().getNamedItem(ROLE_ATTR_NAME).getNodeValue();
            hornetQSecurityManager.addRole(username, role);
         }
      }
   }

   public void undeploy(final Node node) throws Exception
   {
      String username = node.getAttributes().getNamedItem(getKeyAttribute()).getNodeValue();
      hornetQSecurityManager.removeUser(username);
   }

   public String[] getDefaultConfigFileNames()
   {
      return new String[] { "hornetq-users.xml" };
   }
}
