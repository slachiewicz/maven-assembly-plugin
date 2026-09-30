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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.ModuleBinaries;
import org.apache.maven.plugins.assembly.model.ModuleSet;
import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
class DefaultDependencyResolverTest {

    private final DefaultDependencyResolver resolver = new DefaultDependencyResolver();

    private Session session;

    private org.apache.maven.api.services.DependencyResolver dependencyResolver;

    @BeforeEach
    void setUp() {
        session = TestStubs.session();
        dependencyResolver = mock(org.apache.maven.api.services.DependencyResolver.class);
        lenient()
                .when(session.getService(org.apache.maven.api.services.DependencyResolver.class))
                .thenReturn(dependencyResolver);
        final DownloadedArtifact downloaded = mock(DownloadedArtifact.class);
        lenient().when(downloaded.getPath()).thenReturn(Path.of("."));
        lenient()
                .when(session.resolveArtifact(any(org.apache.maven.api.Artifact.class), any()))
                .thenReturn(downloaded);
    }

    @Test
    void getDependencySetResolutionRequirementsTransitive() throws Exception {
        final DependencySet ds = new DependencySet();
        ds.setScope(AssemblyArtifact.SCOPE_RUNTIME);
        ds.setUseTransitiveDependencies(true);

        final Project project = createProject("main-group", "main-artifact", "1", null);

        // the second dependency is a dependency of the first one
        final Node node2 = node("g.id", "a-id-2", "2", "runtime");
        final Node node1 = node("g.id", "a-id", "1", "compile", node2);
        collects(node1);

        final ResolutionManagementInfo info = new ResolutionManagementInfo();
        resolver.updateDependencySetResolutionRequirements(session, ds, info, project);

        final Set<AssemblyArtifact> expected = new HashSet<>();
        expected.add(TestStubs.artifact("g.id", "a-id", "1"));
        expected.add(TestStubs.artifact("g.id", "a-id-2", "2"));
        assertEquals(expected, info.getArtifacts());

        // the trail starts with the project and ends with the artifact's parent
        for (AssemblyArtifact artifact : info.getArtifacts()) {
            final List<Dependency> ancestors = artifact.getAncestors();
            assertEquals("a-id".equals(artifact.getArtifactId()) ? 1 : 2, ancestors.size());
            assertEquals("main-artifact", ancestors.get(0).getArtifactId());
            if (ancestors.size() == 2) {
                assertEquals("a-id", ancestors.get(1).getArtifactId());
            }
        }
    }

    @Test
    void getDependencySetResolutionRequirementsFiltersByScope() throws Exception {
        final DependencySet ds = new DependencySet();
        ds.setScope(AssemblyArtifact.SCOPE_RUNTIME);
        ds.setUseTransitiveDependencies(true);

        final Project project = createProject("main-group", "main-artifact", "1", null);

        collects(node("g.id", "a-id", "1", "compile"), node("g.id", "provided-id", "1", "provided"));

        final ResolutionManagementInfo info = new ResolutionManagementInfo();
        resolver.updateDependencySetResolutionRequirements(session, ds, info, project);

        assertEquals(Collections.singleton(TestStubs.artifact("g.id", "a-id", "1")), info.getArtifacts());
    }

    @Test
    void getDependencySetResolutionRequirementsNonTransitive() throws Exception {
        final DependencySet ds = new DependencySet();
        ds.setScope(AssemblyArtifact.SCOPE_RUNTIME);
        ds.setUseTransitiveDependencies(false);

        final Project project = createProject("main-group", "main-artifact", "1", null);

        final Node transitive = node("g.id", "a-id-2", "2", "compile");
        collects(node("g.id", "a-id", "1", "compile", transitive));

        final ResolutionManagementInfo info = new ResolutionManagementInfo();
        resolver.updateDependencySetResolutionRequirements(session, ds, info, project);
        assertEquals(Collections.singleton(TestStubs.artifact("g.id", "a-id", "1")), info.getArtifacts());
    }

    @Test
    void getModuleSetResolutionRequirementsWithoutBinaries() throws Exception {
        final ResolutionManagementInfo info = new ResolutionManagementInfo();

        final ModuleSet ms = new ModuleSet();
        ms.setBinaries(null);

        resolver.updateModuleSetResolutionRequirements(ms, new DependencySet(), info, null);
        assertTrue(info.getArtifacts().isEmpty());
    }

    @Test
    void getModuleSetResolutionRequirementsIncludeDeps() throws Exception {
        final File rootDir = new File("root");
        final Project project = createProject("main-group", "main-artifact", "1", rootDir, "module-1", "module-2");
        final Project module1 = createProject("main-group", "module-1", "1", new File(rootDir, "module-1"));
        final Project module2 = createProject("main-group", "module-2", "1", new File(rootDir, "module-2"));

        final AssemblerConfigurationSource cs = mock(AssemblerConfigurationSource.class);
        when(cs.getReactorProjects()).thenReturn(Arrays.asList(project, module1, module2));
        when(cs.getProject()).thenReturn(project);
        when(cs.getSession()).thenReturn(session);

        final ResolutionManagementInfo info = new ResolutionManagementInfo();

        final ModuleSet ms = new ModuleSet();
        final ModuleBinaries mb = new ModuleBinaries();
        mb.setIncludeDependencies(true);
        ms.setBinaries(mb);
        // FIXME - this is not checked - because ms.UseAllReactorProjects is false
        ms.addInclude("*:module-1");

        collects(node("group.id", "module-1-dep", "1", "compile"));

        resolver.updateModuleSetResolutionRequirements(ms, new DependencySet(), info, cs);
        assertEquals(Collections.singleton(TestStubs.artifact("group.id", "module-1-dep", "1")), info.getArtifacts());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(cs).getReactorProjects();
        verify(cs).getProject();
    }

    /**
     * Makes the resolver service report a dependency graph whose root has the given children.
     */
    private void collects(Node... children) {
        final Node root = mock(Node.class);
        when(root.getChildren()).thenReturn(Arrays.asList(children));
        final DependencyResolverResult result = mock(DependencyResolverResult.class);
        when(result.getRoot()).thenReturn(root);
        when(dependencyResolver.collect(any(DependencyResolverRequest.class))).thenReturn(result);
    }

    private Node node(String groupId, String artifactId, String version, String scope, Node... children) {
        final AssemblyArtifact artifact = TestStubs.artifact(groupId, artifactId, version, "jar", scope, null);
        final Dependency dependency = artifact.toDependency();
        final Node node = mock(Node.class);
        lenient().when(node.getDependency()).thenReturn(dependency);
        lenient().when(node.getChildren()).thenReturn(new ArrayList<>(Arrays.asList(children)));
        return node;
    }

    private Project createProject(
            final String groupId,
            final String artifactId,
            final String version,
            final File basedir,
            final String... modules) {
        final Model model = TestStubs.model(groupId, artifactId, version)
                .withPackaging("pom")
                .withModules(Arrays.asList(modules));

        final Project project =
                TestStubs.project(model, basedir == null ? null : new File(basedir, "pom.xml").toPath(), null, "pom");

        // the direct dependencies are chosen by scope before the graph is collected
        final DependencyCoordinates coordinates = mock(DependencyCoordinates.class);
        lenient().when(coordinates.getScope()).thenReturn(DependencyScope.COMPILE);
        lenient().when(project.getDependencies()).thenReturn(Collections.singletonList(coordinates));
        lenient().when(project.getManagedDependencies()).thenReturn(Collections.emptyList());
        return project;
    }
}
