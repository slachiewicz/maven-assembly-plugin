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
package org.apache.maven.plugins.assembly.utils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Model;
import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProjectUtilsTest {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private Project createTestProject(
            final String artifactId, final String groupId, final String version, final String pom, String... modules) {
        final Model model = TestStubs.model(groupId, artifactId, version)
                .withModules(Collections.unmodifiableList(List.of(modules)));

        return TestStubs.project(model, pom == null ? null : Path.of(pom), null, "jar");
    }

    @Test
    void getProjectModulesShouldIncludeDirectModuleOfMasterProject() throws Exception {
        final Project master = createTestProject("test", "testGroup", "1.0", "pom.xml", "module");

        final Project module = createTestProject("module", "testGroup", "1.0", "module/pom.xml");

        final List<Project> projects = new ArrayList<>(2);

        projects.add(master);
        projects.add(module);

        final Set<Project> result = ProjectUtils.getProjectModules(master, projects, true, logger);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(module.getId(), result.iterator().next().getId());
    }

    @Test
    void getProjectModulesShouldNotIncludeMasterProject() throws Exception {
        final Project master = createTestProject("test", "testGroup", "1.0", null);

        final Set<Project> result =
                ProjectUtils.getProjectModules(master, Collections.singletonList(master), true, logger);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getProjectModulesShouldIncludeInDirectModuleOfMasterWhenIncludeSubModulesIsTrue() throws Exception {
        final Project master = createTestProject("test", "testGroup", "1.0", "project/pom.xml", "module");

        final Project module = createTestProject("module", "testGroup", "1.0", "project/module/pom.xml", "submodule");

        final Project subModule =
                createTestProject("sub-module", "testGroup", "1.0", "project/module/submodule/pom.xml");

        final List<Project> projects = new ArrayList<>(3);

        projects.add(master);
        projects.add(module);
        projects.add(subModule);

        final Set<Project> result = ProjectUtils.getProjectModules(master, projects, true, logger);

        assertNotNull(result);
        assertEquals(2, result.size());

        final List<Project> verify = new ArrayList<>(projects);
        verify.remove(master);

        verifyProjectsPresent(verify, result);
    }

    @Test
    void getProjectModulesShouldExcludeInDirectModuleOfMasterWhenIncludeSubModulesIsFalse() throws Exception {
        final Project master = createTestProject("test", "testGroup", "1.0", "project/pom.xml", "module");

        final Project module = createTestProject("module", "testGroup", "1.0", "project/module/pom.xml", "submodule");

        final Project subModule =
                createTestProject("sub-module", "testGroup", "1.0", "project/module/submodule/pom.xml");

        final List<Project> projects = new ArrayList<>(3);

        projects.add(master);
        projects.add(module);
        projects.add(subModule);

        final Set<Project> result = ProjectUtils.getProjectModules(master, projects, false, logger);

        assertNotNull(result);
        assertEquals(1, result.size());

        final List<Project> verify = new ArrayList<>(projects);
        verify.remove(master);
        verify.remove(subModule);

        verifyProjectsPresent(verify, result);
    }

    @Test
    void getProjectModulesShouldExcludeNonModuleOfMasterProject() throws Exception {
        final Project master = createTestProject("test", "testGroup", "1.0", "project/pom.xml");

        final Project other = createTestProject("other", "testGroup", "1.0", "other/pom.xml");

        final List<Project> projects = new ArrayList<>(3);

        projects.add(master);
        projects.add(other);

        final Set<Project> result = ProjectUtils.getProjectModules(master, projects, true, logger);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    private void verifyProjectsPresent(final List<Project> verify, final Set<Project> result) {
        final List<Project> verifyCopy = new ArrayList<>(verify);

        final List<Project> unexpected = new ArrayList<>();

        for (final Project project : result) {
            boolean removed = false;

            for (final Iterator<Project> verifyIterator = verifyCopy.iterator(); verifyIterator.hasNext(); ) {
                final Project verification = verifyIterator.next();

                if (verification.getId().equals(project.getId())) {
                    verifyIterator.remove();
                    removed = true;
                }
            }

            if (!removed) {
                unexpected.add(project);
            }
        }

        if (!verifyCopy.isEmpty()) {
            fail("Failed to verify presence of: " + verifyCopy);
        }

        if (!unexpected.isEmpty()) {
            fail("Found unexpected projects in result: " + unexpected);
        }
    }
}
