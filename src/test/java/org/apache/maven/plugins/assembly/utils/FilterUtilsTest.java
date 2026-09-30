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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
class FilterUtilsTest {

    @Test
    void filterArtifactsShouldThrowExceptionUsingStrictModeWithUnmatchedInclude() {
        final AssemblyArtifact artifact = TestStubs.artifact("group", "artifact", "version");

        final List<String> includes = new ArrayList<>();

        includes.add("other.group:other-artifact:type:version");

        final List<String> excludes = Collections.emptyList();

        final Set<AssemblyArtifact> artifacts = new HashSet<>();
        artifacts.add(artifact);

        assertThrows(
                InvalidAssemblerConfigurationException.class,
                () -> FilterUtils.filterArtifacts(
                        artifacts, includes, excludes, true, false, LoggerFactory.getLogger(getClass())),
                "Should fail because of unmatched include.");
    }

    @Test
    void filterArtifactsShouldNotRemoveArtifactDirectlyIncluded() throws Exception {
        verifyArtifactInclusion("group", "artifact", "group:artifact", null, null, null);
        verifyArtifactInclusion("group", "artifact", "group:artifact:jar", null, null, null);
    }

    @Test
    void filterArtifactsShouldNotRemoveArtifactTransitivelyIncluded() throws Exception {
        verifyArtifactInclusion(
                "group",
                "artifact",
                "group:dependentArtifact",
                null,
                Arrays.asList("current:project:jar:1.0", "group:dependentArtifact:jar:version"),
                null);
    }

    @Test
    void filterArtifactsShouldRemoveArtifactTransitivelyExcluded() throws Exception {
        verifyArtifactExclusion(
                "group",
                "artifact",
                null,
                "group:dependentArtifact",
                Arrays.asList("current:project:jar:1.0", "group:dependentArtifact:jar:version"),
                null);
    }

    @Test
    void filterArtifactsShouldRemoveArtifactDirectlyExcluded() throws Exception {
        verifyArtifactExclusion("group", "artifact", null, "group:artifact", null, null);
        verifyArtifactExclusion("group", "artifact", null, "group:artifact:jar", null, null);
    }

    @Test
    void filterArtifactsShouldNotRemoveArtifactNotIncludedAndNotExcluded() throws Exception {
        verifyArtifactInclusion("group", "artifact", null, null, null, null);
        verifyArtifactInclusion("group", "artifact", null, null, null, null);
    }

    @Test
    void filterArtifactsShouldRemoveArtifactExcludedByAdditionalFilter() throws Exception {
        Predicate<AssemblyArtifact> filter = artifact -> false;

        verifyArtifactExclusion("group", "artifact", "fail:fail", null, null, filter);
    }

    @Test
    void filterProjectsShouldNotRemoveProjectDirectlyIncluded() {
        verifyProjectInclusion("group", "artifact", "group:artifact", null);
        verifyProjectInclusion("group", "artifact", "group:artifact:jar", null);
    }

    @Test
    void filterProjectsShouldRemoveProjectDirectlyExcluded() {
        verifyProjectExclusion("group", "artifact", null, "group:artifact");
        verifyProjectExclusion("group", "artifact", null, "group:artifact:jar");
    }

    @Test
    void filterProjectsShouldNotRemoveProjectNotIncludedAndNotExcluded() {
        verifyProjectInclusion("group", "artifact", null, null);
        verifyProjectInclusion("group", "artifact", null, null);
    }

    @Test
    void transitiveScopes() {
        Collection<String> compileScope = FilterUtils.classpathScopes(Collections.singleton("compile"));
        assertFalse(compileScope.isEmpty());
        assertTrue(compileScope.contains("compile"));
        assertTrue(compileScope.contains("provided"));
        assertTrue(compileScope.contains("system"));

        Collection<String> providedScope = FilterUtils.classpathScopes(Collections.singleton("provided"));
        assertTrue(providedScope.contains("provided"));

        Collection<String> systemScope = FilterUtils.classpathScopes(Collections.singleton("system"));
        assertTrue(systemScope.contains("system"));

        Collection<String> runtimeScope = FilterUtils.classpathScopes(Collections.singleton("runtime"));
        assertTrue(runtimeScope.contains("compile"));
        assertTrue(runtimeScope.contains("runtime"));

        Collection<String> testScope = FilterUtils.classpathScopes(Collections.singleton("test"));
        assertTrue(testScope.contains("compile"));
        assertTrue(testScope.contains("provided"));
        assertTrue(testScope.contains("runtime"));
        assertTrue(testScope.contains("system"));
        assertTrue(testScope.contains("test"));
    }

    private void verifyArtifactInclusion(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern,
            final List<String> depTrail,
            final Predicate<AssemblyArtifact> additionalFilter)
            throws InvalidAssemblerConfigurationException {
        verifyArtifactFiltering(
                groupId, artifactId, inclusionPattern, exclusionPattern, depTrail, true, additionalFilter);
    }

    private void verifyArtifactExclusion(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern,
            final List<String> depTrail,
            final Predicate<AssemblyArtifact> additionalFilter)
            throws InvalidAssemblerConfigurationException {
        verifyArtifactFiltering(
                groupId, artifactId, inclusionPattern, exclusionPattern, depTrail, false, additionalFilter);
    }

    private void verifyArtifactFiltering(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern,
            final List<String> depTrail,
            final boolean verifyInclusion,
            final Predicate<AssemblyArtifact> additionalFilter)
            throws InvalidAssemblerConfigurationException {
        final List<AssemblyArtifact> ancestors = new ArrayList<>();
        if (depTrail != null) {
            for (String trailItem : depTrail) {
                final String[] tokens = trailItem.split(":");
                ancestors.add(TestStubs.artifact(tokens[0], tokens[1], tokens[3]));
            }
        }
        final AssemblyArtifact artifact = TestStubs.artifact(groupId, artifactId, "version", ancestors);

        List<String> inclusions;
        if (inclusionPattern != null) {
            inclusions = Collections.singletonList(inclusionPattern);
        } else {
            inclusions = Collections.emptyList();
        }

        List<String> exclusions;
        if (exclusionPattern != null) {
            exclusions = Collections.singletonList(exclusionPattern);
        } else {
            exclusions = Collections.emptyList();
        }

        final Set<AssemblyArtifact> artifacts = new HashSet<>(Collections.singleton(artifact));

        FilterUtils.filterArtifacts(
                artifacts,
                inclusions,
                exclusions,
                false,
                depTrail != null,
                LoggerFactory.getLogger(getClass()),
                additionalFilter);

        if (verifyInclusion) {
            assertEquals(1, artifacts.size());
            assertEquals(
                    artifact.getDependencyConflictId(),
                    artifacts.iterator().next().getDependencyConflictId());
        } else {
            assertTrue(artifacts.isEmpty());
        }
    }

    private void verifyProjectInclusion(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern) {
        verifyProjectFiltering(groupId, artifactId, inclusionPattern, exclusionPattern, true);
    }

    private void verifyProjectExclusion(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern) {
        verifyProjectFiltering(groupId, artifactId, inclusionPattern, exclusionPattern, false);
    }

    private void verifyProjectFiltering(
            final String groupId,
            final String artifactId,
            final String inclusionPattern,
            final String exclusionPattern,
            final boolean verifyInclusion) {
        final Session session = TestStubs.session();
        final Project project = TestStubs.project(groupId, artifactId, "1.0");

        final Set<Project> projects = new HashSet<>();
        projects.add(project);

        List<String> inclusions;
        if (inclusionPattern != null) {
            inclusions = Collections.singletonList(inclusionPattern);
        } else {
            inclusions = Collections.emptyList();
        }

        List<String> exclusions;
        if (exclusionPattern != null) {
            exclusions = Collections.singletonList(exclusionPattern);
        } else {
            exclusions = Collections.emptyList();
        }

        Set<Project> result = FilterUtils.filterProjects(
                session, projects, inclusions, exclusions, false, LoggerFactory.getLogger(getClass()));

        if (verifyInclusion) {
            assertEquals(1, result.size());
            assertEquals(project.getId(), result.iterator().next().getId());
        } else {
            assertTrue(result.isEmpty());
        }
    }
}
