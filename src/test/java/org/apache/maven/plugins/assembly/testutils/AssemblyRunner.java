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
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.plugins.assembly.archive.DefaultAssemblyArchiver;
import org.apache.maven.plugins.assembly.archive.phase.AssemblyArchiverPhase;
import org.apache.maven.plugins.assembly.archive.phase.FileItemAssemblyPhase;
import org.apache.maven.plugins.assembly.archive.phase.FileSetAssemblyPhase;
import org.apache.maven.plugins.assembly.filter.ComponentsXmlArchiverFileFilter;
import org.apache.maven.plugins.assembly.filter.ContainerDescriptorHandler;
import org.apache.maven.plugins.assembly.filter.MetaInfServicesHandler;
import org.apache.maven.plugins.assembly.filter.MetaInfSpringHandler;
import org.apache.maven.plugins.assembly.filter.SimpleAggregatingDescriptorHandler;
import org.apache.maven.plugins.assembly.io.DefaultAssemblyReader;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.plugins.assembly.mojos.AbstractAssemblyMojo;
import org.apache.maven.shared.filtering.DefaultMavenReaderFilter;
import org.codehaus.plexus.archiver.manager.ServiceLoaderArchiverManager;
import org.codehaus.plexus.build.BuildContext;
import org.codehaus.plexus.interpolation.fixed.FixedStringSearchInterpolator;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Runs an assembly descriptor through the real {@link DefaultAssemblyArchiver} (real archivers, the
 * {@code files} and {@code fileSets} phases) against a project directory, without a mojo or Maven session.
 * Unit-test replacement for ITs that only check what a descriptor puts into the archive.
 */
public final class AssemblyRunner {

    private final File projectDir;

    private final PojoConfigSource configSource = new PojoConfigSource();

    private final Map<String, String> projectProperties = new LinkedHashMap<>();

    private Project project;

    public AssemblyRunner(File projectDir) {
        this.projectDir = projectDir;

        configSource.setBasedir(projectDir);
        configSource.setWorkingDirectory(new File(projectDir, "target/assembly/work"));
        configSource.setOutputDirectory(new File(projectDir, "target"));
        configSource.setTemporaryRootDirectory(new File(projectDir, "target/archiver-tmp"));
        configSource.setFinalName("it-1.0-SNAPSHOT");
        configSource.setEncoding("UTF-8");
        configSource.setTarLongFileMode("warn");
        configSource.setIgnoreDirFormatExtensions(true);
        configSource.setRootInterpolator(FixedStringSearchInterpolator.empty());
        configSource.setEnvInterpolator(FixedStringSearchInterpolator.empty());
        configSource.setEnvironmentInterpolator(FixedStringSearchInterpolator.empty());
        rebuildProject();
    }

    // the Maven 4 model is immutable, so a project property change builds the model and the project stand-in again
    private void rebuildProject() {
        Build build = Build.newBuilder()
                .directory(new File(projectDir, "target").getAbsolutePath())
                .outputDirectory(new File(projectDir, "target/classes").getAbsolutePath())
                .build();
        Model model = Model.newBuilder()
                .groupId("test")
                .artifactId("it")
                .version("1.0-SNAPSHOT")
                .build(build)
                .properties(projectProperties)
                .build();
        project = TestStubs.project(model, new File(projectDir, "pom.xml").toPath(), projectDir.toPath(), "jar");
        configSource.setMavenProject(project);
        configSource.setMainProjectInterpolator(AbstractAssemblyMojo.mainProjectInterpolator(project));
    }

    /**
     * Sets a property of the project, as a {@code <properties>} entry of the POM would.
     */
    public AssemblyRunner projectProperty(String name, String value) {
        projectProperties.put(name, value);
        rebuildProject();
        return this;
    }

    /**
     * Wires the real maven-filtering reader filter and a session that exposes the JVM system properties, so that
     * {@code <filtered>true</filtered>} resources are interpolated as they are in a build.
     */
    public AssemblyRunner withFiltering() {
        Session session = TestStubs.session();
        lenient().when(session.getSystemProperties()).thenReturn(TestStubs.toMap(System.getProperties()));
        configSource.setSession(session);
        configSource.setMavenReaderFilter(new DefaultMavenReaderFilter());
        return this;
    }

    public PojoConfigSource configSource() {
        return configSource;
    }

    public Project project() {
        return project;
    }

    public File projectDir() {
        return projectDir;
    }

    /**
     * Writes {@code content} to {@code relativePath} below the project directory (UTF-8).
     */
    public File write(String relativePath, String content) throws IOException {
        File f = new File(projectDir, relativePath);
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /**
     * Parses the descriptor the way the plugin does: read, then interpolate.
     */
    public Assembly parse(String descriptorXml) throws Exception {
        return new DefaultAssemblyReader()
                .readAssembly(new StringReader(descriptorXml), "test descriptor", projectDir, configSource);
    }

    /**
     * Assembly descriptor with the given id, a single {@code dir}-style format placeholder and the body inserted
     * after {@code includeBaseDirectory}.
     */
    public static String descriptor(String id, boolean includeBaseDirectory, String... body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<assembly><id>").append(id).append("</id>");
        sb.append("<formats><format>dir</format></formats>");
        sb.append("<includeBaseDirectory>").append(includeBaseDirectory).append("</includeBaseDirectory>");
        for (String b : body) {
            sb.append(b);
        }
        return sb.append("</assembly>").toString();
    }

    /**
     * Builds the assembly in the given format and returns the produced archive (or directory).
     */
    public File build(String descriptorXml, String format) throws Exception {
        Assembly assembly = parse(descriptorXml);
        List<AssemblyArchiverPhase> phases = Arrays.asList(new FileItemAssemblyPhase(), new FileSetAssemblyPhase());
        DefaultAssemblyArchiver archiver = new DefaultAssemblyArchiver(
                new ServiceLoaderArchiverManager(), phases, AssemblyRunner::handlers, mock(BuildContext.class));
        String fullName = configSource.getFinalName() + "-" + assembly.getId();
        return archiver.createArchive(assembly, fullName, format, configSource, null);
    }

    // the handlers the plugin registers as @Named components, new instances for every build
    private static Map<String, ContainerDescriptorHandler> handlers() {
        Map<String, ContainerDescriptorHandler> handlers = new LinkedHashMap<>();
        handlers.put("file-aggregator", new SimpleAggregatingDescriptorHandler());
        handlers.put("metaInf-services", new MetaInfServicesHandler());
        handlers.put("metaInf-spring", new MetaInfSpringHandler());
        handlers.put("plexus", new ComponentsXmlArchiverFileFilter());
        return handlers;
    }

    /**
     * Relative, '/'-separated paths of everything below {@code root} (directories carry a trailing '/'), sorted.
     */
    public static Set<String> listing(File root) throws IOException {
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            return walk.filter(p -> !p.equals(root.toPath()))
                    .map(p -> root.toPath().relativize(p).toString().replace(File.separatorChar, '/')
                            + (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
