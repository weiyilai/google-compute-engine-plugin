/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine;

import com.google.api.services.compute.model.Operation;
import com.google.jenkins.plugins.computeengine.ssh.GoogleKeyPair;
import com.trilead.ssh2.Connection;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

public class ComputeEngineLinuxLauncher extends ComputeEngineComputerLauncher {
  private static final Logger LOGGER = Logger.getLogger(ComputeEngineLinuxLauncher.class.getName());

  private static int bootstrapAuthTries = 30;
  private static int bootstrapAuthSleepMs = 15000;

  public ComputeEngineLinuxLauncher(
      String cloudName, Operation insertOperation, boolean useInternalAddress) {
    super(cloudName, insertOperation.getName(), insertOperation.getZone(), useInternalAddress);
  }

  protected Logger getLogger() {
    return LOGGER;
  }

  @Override
  protected Optional<Connection> setupConnection(
      ComputeEngineInstance node, ComputeEngineComputer computer, TaskListener listener)
      throws Exception {
    if (!node.getSSHKeyPair().isPresent()) {
      logSevere(
          computer,
          listener,
          String.format("Failed to retrieve SSH keypair for instance: %s", node.getNodeName()));
      return Optional.empty();
    }

    GoogleKeyPair kp = node.getSSHKeyPair().get();
    Optional<Connection> bootstrapConn = bootstrap(kp, computer, listener);
    if (!bootstrapConn.isPresent()) {
      logWarning(computer, listener, "bootstrapresult failed");
      return Optional.empty();
    }

    return bootstrapConn;
  }

  private Optional<Connection> bootstrap(
      GoogleKeyPair kp, ComputeEngineComputer computer, TaskListener listener)
      throws IOException, Exception { // TODO: better exceptions
    logInfo(computer, listener, "bootstrap");
    ComputeEngineInstance node = computer.getNode();
    if (node == null) {
      throw new IllegalArgumentException("A ComputeEngineComputer with no node was provided");
    }
    Connection bootstrapConn = null;
    try {
      int tries = bootstrapAuthTries;
      boolean isAuthenticated = false;
      logInfo(computer, listener, "Getting keypair...");
      logInfo(computer, listener, "Using autogenerated keypair");
      while (tries-- > 0) {
        logInfo(computer, listener, "Authenticating as " + node.getSshUser());
        try {
          bootstrapConn = connectToSsh(computer, listener);
          isAuthenticated =
              bootstrapConn.authenticateWithPublicKey(
                  node.getSshUser(), kp.getPrivateKey().toCharArray(), "");
        } catch (IOException e) {
          logException(computer, listener, "Exception trying to authenticate", e);
          if (bootstrapConn != null) {
            bootstrapConn.close();
          }
        }
        if (isAuthenticated) {
          break;
        }
        logWarning(computer, listener, "Authentication failed. Trying again...");
        Thread.sleep(bootstrapAuthSleepMs);
      }
      if (!isAuthenticated) {
        logWarning(computer, listener, "Authentication failed");
        return Optional.empty();
      }
    } catch (Exception e) {
      logException(computer, listener, "Failed to authenticate with exception: ", e);
      if (bootstrapConn != null) {
        bootstrapConn.close();
      }
      return Optional.empty();
    }
    return Optional.of(bootstrapConn);
  }

  @Override
  protected String getPathSeparator() {
    return "/";
  }
}
