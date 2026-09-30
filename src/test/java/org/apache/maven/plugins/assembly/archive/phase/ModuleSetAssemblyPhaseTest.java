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
package org.apache.maven.plugins.assembly.archive.phase;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.archive.DefaultAssemblyArchiverTest;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.apache.maven.plugins.assembly.artifact.DependencyResolver;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.plugins.assembly.model.FileSet;
import org.apache.maven.plugins.assembly.model.ModuleBinaries;
import org.apache.maven.plugins.assembly.model.ModuleSet;
import org.apache.maven.plugins.assembly.model.ModuleSources;
import org.apache.maven.plugins.assembly.testutils.TestStubs;
import org.codehaus.plexus.archiver.Archiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
class ModuleSetAssemblyPhaseTest {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @TempDir
    private File temporaryFolder;

    private ModuleSetAssemblyPhase phase;

    private DependencyResolver dependencyResolver;

    private Session session;

    @BeforeEach
    void setUp() {
        this.dependencyResolver = mock(DependencyResolver.class);
        this.session = TestStubs.session();

        this.phase = new ModuleSetAssemblyPhase(dependencyResolver);
    }

    private AssemblerConfigurationSource mockConfigSource() {
        final AssemblerConfigurationSource configSource = mock(AssemblerConfigurationSource.class);
        lenient().when(configSource.getSession()).thenReturn(session);
        return configSource;
    }

    @Test
    void isDeprecatedModuleSourcesConfigPresentShouldCatchOutputDir() {
        final ModuleSources sources = new ModuleSources();
        sources.setOutputDirectory("outdir");

        assertTrue(this.phase.isDeprecatedModuleSourcesConfigPresent(sources));
    }

    @Test
    void isDeprecatedModuleSourcesConfigPresentShouldCatchInclude() {
        final ModuleSources sources = new ModuleSources();
        sources.addInclude("**/included.txt");

        assertTrue(this.phase.isDeprecatedModuleSourcesConfigPresent(sources));
    }

    @Test
    void isDeprecatedModuleSourcesConfigPresentShouldCatchExclude() {
        final ModuleSources sources = new ModuleSources();
        sources.addExclude("**/excluded.txt");

        assertTrue(this.phase.isDeprecatedModuleSourcesConfigPresent(sources));
    }

    @Test
    void isDeprecatedModuleSourcesConfigPresentShouldNotCatchFileMode() {
        final ModuleSources sources = new ModuleSources();
        sources.setFileMode("777");

        assertFalse(this.phase.isDeprecatedModuleSourcesConfigPresent(sources));
    }

    @Test
    void isDeprecatedModuleSourcesConfigPresentShouldNotCatchDirMode() {
        final ModuleSources sources = new ModuleSources();
        sources.setDirectoryMode("777");

        assertFalse(this.phase.isDeprecatedModuleSourcesConfigPresent(sources));
    }

    @Test
    void createFileSetShouldUseModuleDirOnlyWhenOutDirIsNull() throws Exception {
        final Project project = TestStubs.project("unknown", "artifact", "0");

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getProject()).thenReturn(project);

        final FileSet fs = new FileSet();

        final ModuleSources sources = new ModuleSources();
        sources.setIncludeModuleDirectory(true);

        final File basedir = temporaryFolder;

        final Project artifactProject = TestStubs.project(
                TestStubs.model("GROUPID", "artifact", "0"), new File(basedir, "pom.xml").toPath(), null, "jar");

        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        final FileSet result = this.phase.createFileSet(fs, sources, artifactProject, configSource);

        assertEquals("artifact/", result.getOutputDirectory());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void createFileSetShouldPrependModuleDirWhenOutDirIsProvided() throws Exception {
        final Project project = TestStubs.project("unknown", "artifact", "0");

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getProject()).thenReturn(project);

        final FileSet fs = new FileSet();
        fs.setOutputDirectory("out");

        final ModuleSources sources = new ModuleSources();
        sources.setIncludeModuleDirectory(true);

        final File basedir = temporaryFolder;

        final Project artifactProject = TestStubs.project(
                TestStubs.model("GROUPID", "artifact", "0"), new File(basedir, "pom.xml").toPath(), null, "jar");
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project /* or artifactProject */);

        final FileSet result = this.phase.createFileSet(fs, sources, artifactProject, configSource);

        assertEquals("artifact/out/", result.getOutputDirectory());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void createFileSetShouldAddExcludesForSubModulesWhenExcludeSubModDirsIsTrue() throws Exception {
        final AssemblerConfigurationSource configSource = mockConfigSource();

        final FileSet fs = new FileSet();

        final ModuleSources sources = new ModuleSources();
        sources.setExcludeSubModuleDirectories(true);

        final File basedir = temporaryFolder;
        final Project project = TestStubs.project(
                TestStubs.model("GROUPID", "artifact", "0").withModules(List.of("submodule")),
                new File(basedir, "pom.xml").toPath(),
                null,
                "jar");
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        final FileSet result = this.phase.createFileSet(fs, sources, project, configSource);

        assertEquals(1, result.getExcludes().size());
        assertEquals("submodule/**", result.getExcludes().get(0));

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void executeShouldSkipIfNoModuleSetsFound() throws Exception {
        final Assembly assembly = new Assembly();
        assembly.setIncludeBaseDirectory(false);

        this.phase.execute(assembly, null, null);
    }

    @Test
    void executeShouldAddOneModuleSetWithOneModuleInIt() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);

        final Project module = createProject("group", "module", "version", project);

        final File moduleArtifactFile = File.createTempFile("junit", null, temporaryFolder);
        TestStubs.mainArtifactFile(session, module, moduleArtifactFile);

        final List<Project> projects = new ArrayList<>();
        projects.add(module);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getReactorProjects()).thenReturn(projects);
        when(configSource.getFinalName()).thenReturn("final-name");
        when(configSource.getProject()).thenReturn(project);

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0777);
        when(archiver.getOverrideFileMode()).thenReturn(0777);

        final ModuleBinaries bin = new ModuleBinaries();
        bin.setOutputFileNameMapping("artifact");
        bin.setOutputDirectory("out");
        bin.setFileMode("777");
        bin.setUnpack(false);
        bin.setIncludeDependencies(false);

        final ModuleSet ms = new ModuleSet();
        ms.setBinaries(bin);

        final Assembly assembly = new Assembly();
        assembly.setIncludeBaseDirectory(false);
        assembly.addModuleSet(ms);

        when(dependencyResolver.resolveDependencySets(eq(assembly), eq(ms), eq(configSource), anyList()))
                .thenReturn(new LinkedHashMap<>());
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, module);

        this.phase.execute(assembly, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();
        verify(configSource, atLeastOnce()).getReactorProjects();

        verify(archiver).addFile(moduleArtifactFile, "out/artifact", 511);
        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver, times(2)).setFileMode(511);

        verify(dependencyResolver).resolveDependencySets(eq(assembly), eq(ms), eq(configSource), anyList());
    }

    @Test
    void addModuleBinariesShouldReturnImmediatelyWhenBinariesIsNull() throws Exception {
        this.phase.addModuleBinaries(null, null, null, null, null, null);
    }

    @Test
    void addModuleBinariesShouldFilterPomModule() throws Exception {
        final ModuleBinaries binaries = new ModuleBinaries();

        binaries.setUnpack(false);
        binaries.setFileMode("777");
        binaries.setOutputDirectory("out");
        binaries.setOutputFileNameMapping("artifact");

        final Project project = createProject("group", "artifact", "version", null, "pom");

        final Set<Project> projects = singleton(project);

        this.phase.addModuleBinaries(null, null, binaries, projects, null, mockConfigSource());
    }

    @Test
    void addModuleBinariesShouldAddOnePomModuleAttachmentArtifactAndNoDeps() throws Exception {
        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getFinalName()).thenReturn("final-name");

        final File artifactFile = File.createTempFile("junit", null, temporaryFolder);

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        final ModuleBinaries binaries = new ModuleBinaries();

        binaries.setIncludeDependencies(false);
        binaries.setUnpack(false);
        binaries.setFileMode("777");
        binaries.setOutputDirectory("out");
        binaries.setOutputFileNameMapping("artifact");
        binaries.setAttachmentClassifier("test");

        final Project project = createProject("group", "artifact", "version", null, "pom");
        TestStubs.attach(session, project, "test", "jar", artifactFile);

        final Set<Project> projects = singleton(project);

        when(dependencyResolver.resolveDependencySets(isNull(), isNull(), eq(configSource), anyList()))
                .thenReturn(new LinkedHashMap<>());
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        this.phase.addModuleBinaries(null, null, binaries, projects, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();

        verify(archiver).addFile(artifactFile, "out/artifact", 511);
        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver).setFileMode(511);
        verify(archiver).setFileMode(146);

        verify(dependencyResolver).resolveDependencySets(isNull(), isNull(), eq(configSource), anyList());
    }

    @Test
    void addModuleBinariesShouldFailWhenOneModuleDoesntHaveAttachmentWithMatchingClassifier() throws Exception {

        final ModuleBinaries binaries = new ModuleBinaries();

        binaries.setUnpack(false);
        binaries.setFileMode("777");
        binaries.setOutputDirectory("out");
        binaries.setOutputFileNameMapping("artifact");
        binaries.setAttachmentClassifier("test");

        final Project project = createProject("group", "artifact", "version", null);

        final Set<Project> projects = singleton(project);

        try {

            this.phase.addModuleBinaries(null, null, binaries, projects, null, mockConfigSource());

            fail("Should throw an invalid configuration exception because of module with missing attachment.");
        } catch (final InvalidAssemblerConfigurationException e) {
            assertEquals(
                    "Cannot find attachment with classifier: test in module project: group:artifact:jar:version. "
                            + "Please exclude this module from the module-set.",
                    e.getMessage());
            // should throw this because of missing attachment.
        }
    }

    @Test
    void addModuleBinariesShouldAddOneModuleArtifactAndNoDeps() throws Exception {
        final File artifactFile = File.createTempFile("junit", null, temporaryFolder);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getFinalName()).thenReturn("final-name");

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        final ModuleBinaries binaries = new ModuleBinaries();

        binaries.setIncludeDependencies(false);
        binaries.setUnpack(false);
        binaries.setFileMode("777");
        binaries.setOutputDirectory("out");
        binaries.setOutputFileNameMapping("artifact");

        final Project project = createProject("group", "artifact", "version", null);
        TestStubs.mainArtifactFile(session, project, artifactFile);

        final Set<Project> projects = singleton(project);

        when(dependencyResolver.resolveDependencySets(
                        isNull(), isNull(), any(AssemblerConfigurationSource.class), anyList()))
                .thenReturn(new LinkedHashMap<>());
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        this.phase.addModuleBinaries(null, null, binaries, projects, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();

        verify(dependencyResolver)
                .resolveDependencySets(isNull(), isNull(), any(AssemblerConfigurationSource.class), anyList());

        verify(archiver).addFile(artifactFile, "out/artifact", 511);
        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver).setFileMode(511);
        verify(archiver).setFileMode(146);
    }

    @Test
    void addModuleArtifactShouldThrowExceptionWhenArtifactFileIsNull() throws Exception {
        AssemblyArtifact artifact = TestStubs.artifact("group", "artifact", "version");
        try {
            this.phase.addModuleArtifact(artifact, null, null, null, null);

            fail("Expected ArchiveCreationException since artifact file is null.");
        } catch (final ArchiveCreationException e) {
            // expected
        }
    }

    @Test
    void addModuleArtifactShouldAddOneArtifact() throws Exception {
        final File artifactFile = File.createTempFile("junit", null, temporaryFolder);
        AssemblyArtifact artifact =
                TestStubs.artifact("GROUPID", "artifact", "version", "jar", "compile", artifactFile);

        final Project project = createProject("group", "artifact", "version", null);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getFinalName()).thenReturn("final-name");

        final Archiver archiver = mock(Archiver.class);
        when(archiver.getDestFile()).thenReturn(new File("junk"));
        when(archiver.getOverrideDirectoryMode()).thenReturn(0222);
        when(archiver.getOverrideFileMode()).thenReturn(0222);

        final ModuleBinaries binaries = new ModuleBinaries();
        binaries.setOutputDirectory("out");
        binaries.setOutputFileNameMapping("artifact");
        binaries.setUnpack(false);
        binaries.setFileMode("777");
        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        this.phase.addModuleArtifact(artifact, project, archiver, configSource, binaries);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getSession();
        verify(configSource, atLeastOnce()).getProject();

        verify(archiver).addFile(artifactFile, "out/artifact", 511);
        verify(archiver, atLeastOnce()).getDestFile();
        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
        verify(archiver).setFileMode(511);
        verify(archiver).setFileMode(146);
    }

    @Test
    void addModuleSourceFileSetsShouldReturnImmediatelyIfSourcesIsNull() throws Exception {
        this.phase.addModuleSourceFileSets(null, null, null, null);
    }

    @Test
    void addModuleSourceFileSetsShouldAddOneSourceDirectory() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getFinalName()).thenReturn("final-name");
        when(configSource.getProject()).thenReturn(project);

        final Set<Project> projects = singleton(project);

        final FileSet fs = new FileSet();
        fs.setDirectory("/src");
        fs.setDirectoryMode("777");
        fs.setFileMode("777");

        final ModuleSources sources = new ModuleSources();
        sources.addFileSet(fs);

        // the logger sends a debug message with this info inside the addFileSet(..) method..
        final Archiver archiver = mock(Archiver.class);
        when(archiver.getOverrideDirectoryMode()).thenReturn(-1);
        when(archiver.getOverrideFileMode()).thenReturn(-1);

        DefaultAssemblyArchiverTest.setupInterpolators(configSource, project);

        this.phase.addModuleSourceFileSets(sources, projects, archiver, configSource);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getArchiveBaseDirectory();
        verify(configSource, atLeastOnce()).getFinalName();
        verify(configSource, atLeastOnce()).getProject();
        verify(configSource, atLeastOnce()).getSession();

        verify(archiver).getOverrideDirectoryMode();
        verify(archiver).getOverrideFileMode();
    }

    @Test
    void getModuleProjectsShouldReturnNothingWhenReactorContainsOnlyCurrentProject() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);

        final List<Project> projects = Collections.singletonList(project);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getProject()).thenReturn(project);
        when(configSource.getReactorProjects()).thenReturn(projects);

        final ModuleSet moduleSet = new ModuleSet();
        moduleSet.setIncludeSubModules(true);

        final Set<Project> moduleProjects = ModuleSetAssemblyPhase.getModuleProjects(moduleSet, configSource, logger);

        assertTrue(moduleProjects.isEmpty());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getReactorProjects();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void getModuleProjectsShouldReturnNothingWhenReactorContainsTwoSiblingProjects() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);
        final Project project2 = createProject("group", "artifact2", "version", null);

        final List<Project> projects = new ArrayList<>();
        projects.add(project);
        projects.add(project2);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getReactorProjects()).thenReturn(projects);
        when(configSource.getProject()).thenReturn(project);

        final ModuleSet moduleSet = new ModuleSet();
        moduleSet.setIncludeSubModules(true);

        final Set<Project> moduleProjects = ModuleSetAssemblyPhase.getModuleProjects(moduleSet, configSource, logger);

        assertTrue(moduleProjects.isEmpty());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getReactorProjects();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void getModuleProjectsShouldUseAllReactorProjectsRegardlessOfReactorOrder() throws Exception {
        final Project parent = createProject("group", "parent", "version", null);

        final Project aggregator = TestStubs.project(
                TestStubs.model("group", "aggregator", "version"),
                new File(temporaryFolder, "aggregator/pom.xml").toPath(),
                null,
                "jar");

        final Project module = createProject("group", "module", "version", aggregator);
        final Project distribution = createProject("group", "distribution", "version", aggregator);

        final List<Project> projects = Arrays.asList(parent, aggregator, module, distribution);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getReactorProjects()).thenReturn(projects);
        when(configSource.getProject()).thenReturn(distribution);

        final ModuleSet moduleSet = new ModuleSet();
        moduleSet.setUseAllReactorProjects(true);
        moduleSet.setIncludeSubModules(true);

        final Set<Project> moduleProjects = ModuleSetAssemblyPhase.getModuleProjects(moduleSet, configSource, logger);

        assertEquals(projects, new ArrayList<>(moduleProjects));
        verify(configSource).getReactorProjects();
    }

    @Test
    void getModuleProjectsShouldReturnModuleOfCurrentProject() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);
        final Project project2 = createProject("group", "artifact2", "version", project);

        final List<Project> projects = new ArrayList<>();
        projects.add(project);
        projects.add(project2);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getReactorProjects()).thenReturn(projects);
        when(configSource.getProject()).thenReturn(project);

        final ModuleSet moduleSet = new ModuleSet();
        moduleSet.setIncludeSubModules(true);

        final Set<Project> moduleProjects = ModuleSetAssemblyPhase.getModuleProjects(moduleSet, configSource, logger);

        assertFalse(moduleProjects.isEmpty());

        final Project result = moduleProjects.iterator().next();

        assertEquals("artifact2", result.getArtifactId());

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getReactorProjects();
        verify(configSource, atLeastOnce()).getProject();
    }

    @Test
    void getModuleProjectsShouldReturnDescendentModulesOfCurrentProject() throws Exception {
        final Project project = createProject("group", "artifact", "version", null);
        final Project project2 = createProject("group", "artifact2", "version", project);
        final Project project3 = createProject("group", "artifact3", "version", project2);

        final List<Project> projects = new ArrayList<>();
        projects.add(project);
        projects.add(project2);
        projects.add(project3);

        final AssemblerConfigurationSource configSource = mockConfigSource();
        when(configSource.getReactorProjects()).thenReturn(projects);
        when(configSource.getProject()).thenReturn(project);

        final ModuleSet moduleSet = new ModuleSet();
        moduleSet.setIncludeSubModules(true);

        final Set<Project> moduleProjects = ModuleSetAssemblyPhase.getModuleProjects(moduleSet, configSource, logger);

        assertEquals(2, moduleProjects.size());

        final List<Project> check = new ArrayList<>();
        check.add(project2);
        check.add(project3);

        verifyResultIs(check, moduleProjects);

        // result of easymock migration, should be assert of expected result instead of verifying methodcalls
        verify(configSource).getReactorProjects();
        verify(configSource, atLeastOnce()).getProject();
    }

    private void verifyResultIs(final List<Project> check, final Set<Project> moduleProjects) {
        boolean failed = false;

        final Set<Project> checkTooMany = new HashSet<>(moduleProjects);
        check.forEach(checkTooMany::remove);

        if (!checkTooMany.isEmpty()) {
            failed = true;

            System.out.println("Unexpected projects in output: ");

            for (final Project project : checkTooMany) {
                System.out.println(project.getId());
            }
        }

        final Set<Project> checkTooFew = new HashSet<>(check);
        checkTooFew.removeAll(moduleProjects);

        if (!checkTooFew.isEmpty()) {
            failed = true;

            System.out.println("Expected projects missing from output: ");

            for (final Project project : checkTooMany) {
                System.out.println(project.getId());
            }
        }

        if (failed) {
            fail("See system output for more information.");
        }
    }

    private final List<Object[]> modulesOfProjects = new ArrayList<>();

    private Project createProject(
            final String groupId, final String artifactId, final String version, final Project parentProject) {
        return createProject(groupId, artifactId, version, parentProject, "jar");
    }

    private Project createProject(
            final String groupId,
            final String artifactId,
            final String version,
            final Project parentProject,
            final String packaging) {
        final Model model = TestStubs.model(groupId, artifactId, version).withPackaging(packaging);

        final File pomFile;
        if (parentProject == null) {
            final File basedir = temporaryFolder;
            pomFile = new File(basedir, "pom.xml");
        } else {
            final File parentBase = parentProject.getBasedir().toFile();
            pomFile = new File(parentBase, artifactId + "/pom.xml");
        }

        final Project project = TestStubs.project(model, pomFile.toPath(), null, packaging);

        // a module is declared in the model of its parent, which is an immutable object
        final List<String> modules = new ArrayList<>();
        lenient().when(project.getModel()).thenAnswer(invocation -> model.withModules(modules));
        modulesOfProjects.add(new Object[] {project, modules});

        if (parentProject != null) {
            for (Object[] entry : modulesOfProjects) {
                if (entry[0] == parentProject) {
                    @SuppressWarnings("unchecked")
                    final List<String> parentModules = (List<String>) entry[1];
                    parentModules.add(artifactId);
                }
            }
        }

        return project;
    }
}
