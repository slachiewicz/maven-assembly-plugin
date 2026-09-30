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
package org.apache.maven.plugins.assembly.testutils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Stand-ins for the Maven 4 API objects the assembly code reads. They are mocks, stubbed with only what the plugin
 * asks of a project or a session.
 */
public final class TestStubs {
    private static final Session SESSION = session();

    private TestStubs() {}

    public static Model model(String groupId, String artifactId, String version) {
        return Model.newBuilder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .build();
    }

    public static java.util.Map<String, String> toMap(java.util.Properties properties) {
        final java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    public static Project project(String groupId, String artifactId, String version) {
        return project(model(groupId, artifactId, version), null, null, "jar");
    }

    public static Project project(Model model, Path pomPath, Path basedir, String packaging) {
        final Project project = mock(Project.class);
        lenient().when(project.getModel()).thenReturn(model);
        lenient().when(project.getGroupId()).thenReturn(model.getGroupId());
        lenient().when(project.getArtifactId()).thenReturn(model.getArtifactId());
        lenient().when(project.getVersion()).thenReturn(model.getVersion());
        lenient()
                .when(project.getId())
                .thenReturn(
                        model.getGroupId() + ":" + model.getArtifactId() + ":" + packaging + ":" + model.getVersion());
        lenient().when(project.getPomPath()).thenReturn(pomPath);
        lenient().when(project.getBasedir()).thenReturn(basedir != null ? basedir : parentOf(pomPath));
        final org.apache.maven.api.Packaging packagingStub = packaging(packaging);
        lenient().when(project.getPackaging()).thenReturn(packagingStub);
        lenient()
                .when(project.getBuild())
                .thenReturn(model.getBuild() != null ? model.getBuild() : Build.newInstance());
        final ProducedArtifact artifact =
                produced(model.getGroupId(), model.getArtifactId(), model.getVersion(), packaging);
        lenient().when(project.getMainArtifact()).thenReturn(Optional.of(artifact));
        lenient().when(project.getPomArtifact()).thenReturn(artifact);
        return project;
    }

    private static Path parentOf(Path pomPath) {
        return pomPath == null
                ? Path.of("basedir")
                : (pomPath.getParent() == null ? Path.of(".") : pomPath.getParent());
    }

    public static org.apache.maven.api.Packaging packaging(String id) {
        final Type type = mock(Type.class);
        lenient().when(type.id()).thenReturn(id);
        final org.apache.maven.api.Packaging packaging = mock(org.apache.maven.api.Packaging.class);
        lenient().when(packaging.id()).thenReturn(id);
        lenient().when(packaging.type()).thenReturn(type);
        return packaging;
    }

    /**
     * A session that hands out mocked services, the way {@code Session#getService} does at runtime.
     */
    public static Session session() {
        final Session session = mock(Session.class);
        final ProjectManager projectManager = mock(ProjectManager.class);
        final ArtifactManager artifactManager = mock(ArtifactManager.class);
        lenient().when(session.getService(ProjectManager.class)).thenReturn(projectManager);
        lenient().when(session.getService(ArtifactManager.class)).thenReturn(artifactManager);
        lenient().when(artifactManager.getPath(any())).thenReturn(Optional.empty());
        lenient().when(session.getUserProperties()).thenReturn(new java.util.HashMap<>());
        lenient().when(session.getSystemProperties()).thenReturn(new java.util.HashMap<>());
        lenient().when(projectManager.getAttachedArtifacts(any())).thenReturn(new ArrayList<>());
        lenient().when(session.requireType(anyString())).thenAnswer(invocation -> {
            final Type type = mock(Type.class);
            lenient().when(type.id()).thenReturn(invocation.getArgument(0));
            return type;
        });
        lenient().when(session.parseVersion(anyString())).thenAnswer(invocation -> {
            final Version version = mock(Version.class);
            lenient().when(version.toString()).thenReturn(invocation.getArgument(0));
            return version;
        });
        lenient()
                .when(session.requireDependencyScope(anyString()))
                .thenAnswer(invocation -> DependencyScope.forId(invocation.getArgument(0)));
        return session;
    }

    public static AssemblyArtifact artifact(String groupId, String artifactId, String version) {
        return artifact(groupId, artifactId, version, "jar", "compile", null);
    }

    public static AssemblyArtifact artifact(
            String groupId, String artifactId, String version, String type, String scope, File file) {
        return new AssemblyArtifact(
                SESSION, groupId, artifactId, version, version, null, type, type, scope, false, file, null, null);
    }

    /**
     * @param ancestors the dependencies between the project and the artifact, project first
     */
    public static AssemblyArtifact artifact(
            String groupId, String artifactId, String version, List<AssemblyArtifact> ancestors) {
        return new AssemblyArtifact(
                SESSION,
                groupId,
                artifactId,
                version,
                version,
                null,
                "jar",
                "jar",
                "compile",
                false,
                null,
                null,
                ancestors.stream().map(AssemblyArtifact::toDependency).collect(java.util.stream.Collectors.toList()));
    }

    public static AssemblyArtifact artifact(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String type,
            String scope,
            File file) {
        return new AssemblyArtifact(
                SESSION, groupId, artifactId, version, version, classifier, type, type, scope, false, file, null, null);
    }

    public static <T> T unused(Class<T> type) {
        return Mockito.mock(type);
    }

    public static List<String> modules(String... modules) {
        return List.of(modules);
    }

    /**
     * Makes the session report {@code file} as the file of the project's main artifact.
     */
    public static void mainArtifactFile(Session session, Project project, File file) {
        final ArtifactManager artifactManager = session.getService(ArtifactManager.class);
        lenient().when(artifactManager.getPath(project.getMainArtifact().get())).thenReturn(Optional.of(file.toPath()));
    }

    /**
     * Attaches an artifact with the given classifier to the project, as the session reports it.
     */
    public static void attach(Session session, Project project, String classifier, String extension, File file) {
        final ProducedArtifact attachment =
                produced(project.getGroupId(), project.getArtifactId(), project.getVersion(), classifier, extension);
        final ProjectManager projectManager = session.getService(ProjectManager.class);
        lenient().when(projectManager.getAttachedArtifacts(project)).thenReturn(List.of(attachment));
        lenient()
                .when(session.getService(ArtifactManager.class).getPath(attachment))
                .thenReturn(Optional.of(file.toPath()));
    }

    public static Version version(String version) {
        final Version result = mock(Version.class);
        lenient().when(result.toString()).thenReturn(version);
        return result;
    }

    public static ProducedArtifact produced(String groupId, String artifactId, String version, String extension) {
        return produced(groupId, artifactId, version, "", extension);
    }

    public static ProducedArtifact produced(
            String groupId, String artifactId, String version, String classifier, String extension) {
        final ProducedArtifact artifact = mock(ProducedArtifact.class);
        lenient().when(artifact.getGroupId()).thenReturn(groupId);
        lenient().when(artifact.getArtifactId()).thenReturn(artifactId);
        final Version versionStub = version(version);
        lenient().when(artifact.getVersion()).thenReturn(versionStub);
        lenient().when(artifact.getBaseVersion()).thenReturn(versionStub);
        lenient().when(artifact.getExtension()).thenReturn(extension);
        lenient().when(artifact.getClassifier()).thenReturn(classifier);
        return artifact;
    }
}
