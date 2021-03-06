/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.security.authentication;

import org.junit.Test;

/**
 * Tests the {@link SimpleAuthenticationProvider} class.
 */
public class SimpleAuthenticationProviderTest {

  /**
   * Tests the {@link SimpleAuthenticationProvider#authenticate(String, String)}.
   */
  @Test
  public void anyUserAllowConnectTest() throws Exception {
    AuthenticationProvider authenticationProvider = new SimpleAuthenticationProvider();
    authenticationProvider.authenticate("", "");
    authenticationProvider.authenticate("foo", "");
    authenticationProvider.authenticate("bar", "*.:");
    authenticationProvider.authenticate("*.:", "?");
  }
}
