/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.assembly.artifact;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResolutionManagementInfoTest {

    @Test
    void addSingleArtifactWithReplacemen() throws Exception {
        ResolutionManagementInfo rmi = new ResolutionManagementInfo();
        AssemblyArtifact a1 = TestStubs.artifact("groupid", "1", "1.0", "jar", "test", null);
        rmi.addArtifacts(Collections.singleton(a1));
        AssemblyArtifact a2 = TestStubs.artifact("groupid", "1", "1.0", "jar", "compile", null);
        rmi.addArtifacts(Collections.singleton(a2));
        assertEquals(1, rmi.getArtifacts().size());
        AssemblyArtifact next = rmi.getArtifacts().iterator().next();
        assertEquals("compile", next.getScope());
    }

    @Test
    void addMultiArtifactWithReplacemen() throws Exception {
        ResolutionManagementInfo rmi = new ResolutionManagementInfo();
        AssemblyArtifact a1 = TestStubs.artifact("groupid", "a1", "1.0", "jar", "test", null);
        AssemblyArtifact a2 = TestStubs.artifact("groupid", "a2", "1.0", "jar", "test", null);
        AssemblyArtifact a3 = TestStubs.artifact("groupid", "a3", "1.0", "jar", "test", null);
        rmi.addArtifacts(new HashSet<>(Arrays.asList(a1, a2, a3)));
        AssemblyArtifact b2 = TestStubs.artifact("groupid", "a2", "1.0", "jar", "compile", null);
        AssemblyArtifact b3 = TestStubs.artifact("groupid", "a3", "1.0", "jar", "compile", null);
        rmi.addArtifacts(new HashSet<>(Arrays.asList(b2, b3)));
        assertEquals(3, rmi.getArtifacts().size());
        int compile = 0;
        int test = 0;
        for (AssemblyArtifact artifact : rmi.getArtifacts()) {
            if (AssemblyArtifact.SCOPE_COMPILE.equals(artifact.getScope())) {
                compile++;
            } else {
                test++;
            }
        }
        assertEquals(2, compile);
        assertEquals(1, test);
    }
}
