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
package org.apache.maven.plugins.assembly.archive.task;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderException;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectBuilderResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.archive.DefaultAssemblyArchiverTest;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.apache.maven.plugins.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.UnpackOptions;
import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.codehaus.plexus.archiver.ArchivedFileSet;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.FileSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
class AddDependencySetsTaskTest {
    @TempDir
    private File temporaryFolder;

    /**
     * A session whose project builder returns {@code builtProject} for any dependency, or fails if that is null.
     */
    private Session sessionBuilding(Project builtProject, List<RemoteRepository> repositories, Project repositoriesOf)
            throws ProjectBuilderException, IOException {
        final Session session = TestStubs.session();
        when(session.getUserProperties()).thenReturn(new HashMap<>());
        when(session.getSystemProperties()).thenReturn(new HashMap<>());

        final ArtifactCoordinates pomCoordinates = mock(ArtifactCoordinates.class);
        lenient()
                .when(session.createArtifactCoordinates(any(String.class), any(), any(), any(String.class)))
                .thenReturn(pomCoordinates);
        final DownloadedArtifact pom = mock(DownloadedArtifact.class);
        lenient()
                .when(pom.getPath())
                .thenReturn(newFile(temporaryFolder, "dep.pom").toPath());
        lenient().when(session.resolveArtifact(pomCoordinates, repositories)).thenReturn(pom);

        final ProjectManager projectManager = session.getService(ProjectManager.class);
        lenient()
                .when(projectManager.getRemoteProjectRepositories(repositoriesOf))
                .thenReturn(repositories);

        final ProjectBuilder projectBuilder = mock(ProjectBuilder.class);
        if (builtProject != null) {
            final ProjectBuilderResult result = mock(ProjectBuilderResult.class);
            when(result.getProject()).thenReturn(Optional.of(builtProject));
            when(projectBuilder.build(any(ProjectBuilderRequest.class))).thenReturn(result);
        } else {
            when(projectBuilder.build(any(ProjectBuilderRequest.class)))
                    .thenThrow(new ProjectBuilderException("test", new Exception("Test error.")));
        }
        lenient().when(session.getService(ProjectBuilder.class)).thenReturn(projectBuilder);
        return session;
    }

    @Test
    void addDependencySetShouldInterpolateDefaultOutputFileNameMapping() throws Exception {
        final String outDir = "tmp/";
        final String mainAid = "main";
        final String mainGid = "org.maingrp";
        final String mainVer = "9";
        final String depAid = "dep";
        final String depGid = "org.depgrp";
        final String depVer = "1";
        final String depExt = "war";

        final DependencySet ds = new DependencySet();
        ds.setOutputDirectory(outDir);
        ds.setDirectoryMode(Integer.toString(10, 8));
        ds.setFileMode(Integer.toString(10, 8));

        final Project mainProject = TestStubs.project(mainGid, mainAid, mainVer);

        final Project depProject = TestStubs.project(
                Model.newBuilder()
                        .groupId(depGid)
                        .artifactId(depAid)
                        .version(depVer)
                        .packaging(depExt)
                        .build(),
                null,
                null,
                depExt);

        final File newFile = File.createTempFile("junit", null, temporaryFolder);
        // the artifact id and version are left out so that the ones of the project are used
        final AssemblyArtifact depArtifact = new AssemblyArtifact(
                null, "GROUPID", null, null, "1", null, depExt, depExt, "compile", false, newFile, null, null);

        final RemoteRepository remoteRepository = mock(RemoteRepository.class);
        when(remoteRepository.getId()).thenReturn("project-repository");
        final Session session = sessionBuilding(depProject, Collections.singletonList(remoteRepository), depProject);

        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        when(configSource.getFinalName()).thenReturn(mainAid + "-" + mainVer);
        when(configSource.getProject()).thenReturn(mainProject);
        when(configSource.getSession()).thenReturn(session);

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        DefaultAssemblyArchiverTest.setupInterpolators(configSource, mainProject);

        final AddDependencySetsTask task = new AddDependencySetsTask(
                Collections.singletonList(ds), Collections.singleton(depArtifact), depProject);

        task.addDependencySet(ds, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();

        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).addFile(newFile, outDir + depAid + "-" + depVer + "." + depExt, 10);
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver).setDirectoryMode(10);
        verify(archiver).setDirectoryMode(146);
        verify(archiver).setFileMode(10);
        verify(archiver).setFileMode(146);

        verify(session, atLeastOnce()).getUserProperties();
        verify(session, atLeastOnce()).getSystemProperties();

        ArgumentCaptor<ProjectBuilderRequest> request = ArgumentCaptor.forClass(ProjectBuilderRequest.class);
        verify(session.getService(ProjectBuilder.class)).build(request.capture());
        assertEquals(
                "project-repository",
                request.getValue().getRepositories().get(0).getId());
    }

    @Test
    void addDependencySetShouldNotAddDependenciesWhenProjectHasNone() throws Exception {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        final DependencySet ds = new DependencySet();
        ds.setOutputDirectory("/out");

        final AddDependencySetsTask task = new AddDependencySetsTask(Collections.singletonList(ds), null, project);

        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        final Session session = TestStubs.session();
        when(configSource.getSession()).thenReturn(session);

        task.addDependencySet(ds, null, configSource);
    }

    // TODO: Find a better way of testing the behavior when the project of a dependency cannot be built.
    @Test
    void addDependencySetShouldNotAddDependenciesWhenProjectIsStubbed() throws Exception {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        final String aid = "test-dep";
        final String version = "2.0-SNAPSHOT";
        final String type = "jar";

        final File file = new File("dep-artifact.jar");

        final AssemblyArtifact depArtifact = TestStubs.artifact("GROUPID", aid, version, type, "compile", file);

        final File destFile = new File("assembly-dep-set.zip");

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(destFile);
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        final Session session = sessionBuilding(null, Collections.emptyList(), project);

        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        when(configSource.getFinalName()).thenReturn("final-name");
        when(configSource.getSession()).thenReturn(session);
        when(configSource.getProject()).thenReturn(project);

        final DependencySet ds = new DependencySet();
        ds.setOutputDirectory("/out");
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        final AddDependencySetsTask task =
                new AddDependencySetsTask(Collections.singletonList(ds), Collections.singleton(depArtifact), project);

        task.addDependencySet(ds, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();

        verify(archiver).addFile(file, "out/" + aid + "-" + version + "." + type);
        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();

        verify(session, atLeastOnce()).getUserProperties();
        verify(session, atLeastOnce()).getSystemProperties();

        verify(session.getService(ProjectBuilder.class)).build(any(ProjectBuilderRequest.class));
    }

    @Test
    void addDependencySetShouldAddOneDependencyFromProjectWithoutUnpacking() throws Exception {
        verifyOneDependencyAdded("out", false);
    }

    @Test
    void addDependencySetShouldAddOneDependencyFromProjectUnpacked() throws Exception {
        verifyOneDependencyAdded("out", true);
    }

    private void verifyOneDependencyAdded(final String outputLocation, final boolean unpack)
            throws AssemblyFormattingException, ArchiverException, ArchiveCreationException, IOException,
                    InvalidAssemblerConfigurationException, ProjectBuilderException {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        final DependencySet ds = new DependencySet();
        ds.setOutputDirectory(outputLocation);
        ds.setOutputFileNameMapping("artifact");
        ds.setUnpack(unpack);
        ds.setScope(AssemblyArtifact.SCOPE_COMPILE);

        ds.setDirectoryMode(Integer.toString(10, 8));
        ds.setFileMode(Integer.toString(10, 8));

        final Project depProject = TestStubs.project("GROUPID", "unknown", "0");

        final Session session = sessionBuilding(depProject, Collections.emptyList(), project);

        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        when(configSource.getSession()).thenReturn(session);
        when(configSource.getFinalName()).thenReturn("final-name");

        final File artifactFile = File.createTempFile("junit", null, temporaryFolder);
        final AssemblyArtifact artifact =
                TestStubs.artifact("GROUPID", "artifact", "1", "jar", "compile", artifactFile);

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        if (!unpack) {
            when(configSource.getProject()).thenReturn(project);
        }

        final AddDependencySetsTask task =
                new AddDependencySetsTask(Collections.singletonList(ds), Collections.singleton(artifact), project);
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        task.addDependencySet(ds, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getFinalName();
        verify(configSource, atLeastOnce()).getSession();

        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver).setFileMode(10);
        verify(archiver).setFileMode(146);
        verify(archiver).setDirectoryMode(10);
        verify(archiver).setDirectoryMode(146);

        verify(session, atLeastOnce()).getUserProperties();
        verify(session, atLeastOnce()).getSystemProperties();

        verify(session.getService(ProjectBuilder.class)).build(any(ProjectBuilderRequest.class));

        if (unpack) {
            verify(archiver).addArchivedFileSet(any(ArchivedFileSet.class), isNull());
        } else {
            verify(archiver).addFile(artifactFile, outputLocation + "/artifact", 10);
            verify(configSource, atLeastOnce()).getProject();
        }
    }

    @Test
    void getDependencyArtifactsShouldGetOneDependencyArtifact() throws Exception {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        AssemblyArtifact artifact = TestStubs.artifact("group", "artifact", "1.0");

        final DependencySet dependencySet = new DependencySet();

        final AddDependencySetsTask task = new AddDependencySetsTask(
                Collections.singletonList(dependencySet), Collections.singleton(artifact), project);

        final Set<AssemblyArtifact> result = task.resolveDependencyArtifacts(TestStubs.session(), dependencySet);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertSame(artifact, result.iterator().next());
    }

    @Test
    void getDependencyArtifactsShouldFilterOneDependencyArtifactViaInclude() throws Exception {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        final Set<AssemblyArtifact> artifacts = new HashSet<>();

        AssemblyArtifact am1 = TestStubs.artifact("group", "artifact", "1.0");
        artifacts.add(am1);

        AssemblyArtifact am2 = TestStubs.artifact("group2", "artifact2", "1.0");
        artifacts.add(am2);

        final DependencySet dependencySet = new DependencySet();

        dependencySet.addInclude("group:artifact");
        dependencySet.setUseTransitiveFiltering(true);

        final AddDependencySetsTask task =
                new AddDependencySetsTask(Collections.singletonList(dependencySet), artifacts, project);

        final Set<AssemblyArtifact> result = task.resolveDependencyArtifacts(TestStubs.session(), dependencySet);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertSame(am1, result.iterator().next());
    }

    @Test
    void getDependencyArtifactsShouldIgnoreTransitivePathFilteringWhenIncludeNotTransitive() throws Exception {
        final Project project = TestStubs.project("group", "artifact", "1.0");

        final Set<AssemblyArtifact> artifacts = new HashSet<>();

        AssemblyArtifact am1 = TestStubs.artifact("group", "artifact", "1.0");
        artifacts.add(am1);

        AssemblyArtifact am2 = TestStubs.artifact("group2", "artifact2", "1.0");
        artifacts.add(am2);

        final DependencySet dependencySet = new DependencySet();

        dependencySet.addInclude("group:artifact");
        dependencySet.setUseTransitiveFiltering(false);

        final AddDependencySetsTask task =
                new AddDependencySetsTask(Collections.singletonList(dependencySet), artifacts, project);

        final Set<AssemblyArtifact> result = task.resolveDependencyArtifacts(TestStubs.session(), dependencySet);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertSame(am1, result.iterator().next());
    }

    // MASSEMBLY-879
    @Test
    void useDefaultExcludes() throws Exception {
        AssemblyArtifact zipArtifact = TestStubs.artifact(
                "some-artifact", "of-type-zip", "1.0", "zip", "compile", newFile(temporaryFolder, "of-type-zip.zip"));

        AssemblyArtifact dirArtifact = TestStubs.artifact(
                "some-artifact", "of-type-zip", "1.0", "dir", "compile", newFolder(temporaryFolder, "of-type-zip"));

        final Set<AssemblyArtifact> artifacts = new HashSet<>(Arrays.asList(zipArtifact, dirArtifact));

        final DependencySet dependencySet = new DependencySet();
        dependencySet.setUseProjectArtifact(false);
        dependencySet.setIncludes(Collections.singletonList("some-artifact:of-type-zip"));
        dependencySet.setOutputDirectory("MyOutputDir");
        dependencySet.setUnpack(true);
        UnpackOptions unpackOptions = new UnpackOptions();
        unpackOptions.setUseDefaultExcludes(false);
        dependencySet.setUnpackOptions(unpackOptions);

        final Project project = TestStubs.project("GROUPID", "artifact", "1.0");

        final Session session = sessionBuilding(project, Collections.emptyList(), project);

        final AddDependencySetsTask task =
                new AddDependencySetsTask(Collections.singletonList(dependencySet), artifacts, project);

        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        when(configSource.getSession()).thenReturn(session);
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        final Archiver archiver = mock(Archiver.class);

        task.addDependencySet(dependencySet, archiver, configSource);

        ArgumentCaptor<ArchivedFileSet> archivedFileSet = ArgumentCaptor.forClass(ArchivedFileSet.class);
        verify(archiver).addArchivedFileSet(archivedFileSet.capture(), isNull());
        assertFalse(archivedFileSet.getValue().isUsingDefaultExcludes());

        ArgumentCaptor<FileSet> fileSet = ArgumentCaptor.forClass(FileSet.class);
        verify(archiver).addFileSet(fileSet.capture());
        assertFalse(fileSet.getValue().isUsingDefaultExcludes());
    }

    private static File newFile(File parent, String child) throws IOException {
        File result = new File(parent, child);
        result.createNewFile();
        return result;
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
