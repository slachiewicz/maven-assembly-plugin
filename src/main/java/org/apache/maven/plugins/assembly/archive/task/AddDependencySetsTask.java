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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectBuilderResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.apache.maven.plugins.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugins.assembly.format.ReaderFormatter;
import org.apache.maven.plugins.assembly.functions.MavenProjects;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.UnpackOptions;
import org.apache.maven.plugins.assembly.utils.AssemblyFormatUtils;
import org.apache.maven.plugins.assembly.utils.FilterUtils;
import org.apache.maven.plugins.assembly.utils.TypeConversionUtils;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.components.io.functions.InputStreamTransformer;
import org.codehaus.plexus.interpolation.fixed.FixedStringSearchInterpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class AddDependencySetsTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddDependencySetsTask.class);

    private static final List<String> NON_ARCHIVE_DEPENDENCY_TYPES;

    static {
        final List<String> nonArch = new ArrayList<>();

        nonArch.add("pom");

        NON_ARCHIVE_DEPENDENCY_TYPES = Collections.unmodifiableList(nonArch);
    }

    private final List<DependencySet> dependencySets;

    private final Project project;

    private final Set<AssemblyArtifact> resolvedArtifacts;

    private Project moduleProject;

    private String defaultOutputDirectory;

    private String defaultOutputFileNameMapping;

    private AssemblyArtifact moduleArtifact;

    public AddDependencySetsTask(
            final List<DependencySet> dependencySets,
            final Set<AssemblyArtifact> resolvedArtifacts,
            final Project project) {
        this.dependencySets = dependencySets;
        this.resolvedArtifacts = resolvedArtifacts;
        this.project = project;
    }

    public void execute(final Archiver archiver, final AssemblerConfigurationSource configSource)
            throws ArchiveCreationException, AssemblyFormattingException, InvalidAssemblerConfigurationException {
        if ((dependencySets == null) || dependencySets.isEmpty()) {
            LOGGER.debug("No dependency sets specified.");
            return;
        }

        final List<DependencyCoordinates> deps = project.getDependencies();
        if ((deps == null) || deps.isEmpty()) {
            LOGGER.debug("Project " + project.getId() + " has no dependencies. Skipping dependency set addition.");
        }

        for (final DependencySet dependencySet : dependencySets) {
            addDependencySet(dependencySet, archiver, configSource);
        }
    }

    void addDependencySet(
            final DependencySet dependencySet, final Archiver archiver, final AssemblerConfigurationSource configSource)
            throws AssemblyFormattingException, ArchiveCreationException, InvalidAssemblerConfigurationException {
        LOGGER.debug("Processing DependencySet (output=" + dependencySet.getOutputDirectory() + ")");

        if (!dependencySet.isUseTransitiveDependencies() && dependencySet.isUseTransitiveFiltering()) {
            LOGGER.warn("DependencySet has nonsensical configuration: useTransitiveDependencies == false "
                    + "AND useTransitiveFiltering == true. Transitive filtering flag will be ignored.");
        }

        final Set<AssemblyArtifact> dependencyArtifacts =
                resolveDependencyArtifacts(configSource.getSession(), dependencySet);

        if (!unpackTransformsContent(dependencySet) && dependencyArtifacts.size() > 1) {
            checkMultiArtifactOutputConfig(dependencySet);
        }

        LOGGER.debug("Adding " + dependencyArtifacts.size() + " dependency artifacts.");

        UnpackOptions unpackOptions = dependencySet.getUnpackOptions();
        InputStreamTransformer fileSetTransformers = isUnpackWithOptions(dependencySet)
                ? ReaderFormatter.getFileSetTransformers(
                        configSource,
                        unpackOptions.isFiltered(),
                        new HashSet<>(unpackOptions.getNonFilteredFileExtensions()),
                        unpackOptions.getLineEnding())
                : null;

        for (final AssemblyArtifact depArtifact : dependencyArtifacts) {
            Project depProject;
            try {
                depProject = buildProject(configSource.getSession(), depArtifact);
            } catch (final RuntimeException e) {
                LOGGER.debug("Error retrieving POM of module-dependency: " + depArtifact.getId() + "; Reason: "
                        + e.getMessage() + "\n\nContinuing without a project instance.");

                depProject = null;
            }

            if (NON_ARCHIVE_DEPENDENCY_TYPES.contains(depArtifact.getType())) {
                addNonArchiveDependency(depArtifact, depProject, dependencySet, archiver, configSource);
            } else {
                addNormalArtifact(dependencySet, depArtifact, depProject, archiver, configSource, fileSetTransformers);
            }
        }
    }

    /**
     * Builds the project of a dependency from its POM, as the Maven 3 {@code ProjectBuilder.build(Artifact, ...)}
     * did. The Maven 4 builder takes a POM file, so the POM is resolved first.
     */
    private Project buildProject(Session session, AssemblyArtifact depArtifact) {
        final List<RemoteRepository> repositories =
                session.getService(ProjectManager.class).getRemoteProjectRepositories(project);
        final ArtifactCoordinates pomCoordinates = session.createArtifactCoordinates(
                depArtifact.getGroupId(), depArtifact.getArtifactId(), depArtifact.getBaseVersion(), "pom");
        final ProjectBuilderRequest request = ProjectBuilderRequest.builder()
                .session(session)
                .path(session.resolveArtifact(pomCoordinates, repositories).getPath())
                .processPlugins(false)
                .repositories(repositories)
                .build();
        final ProjectBuilderResult result =
                session.getService(ProjectBuilder.class).build(request);
        return result.getProject().orElse(null);
    }

    private boolean isUnpackWithOptions(DependencySet dependencySet) {
        return dependencySet.isUnpack() && dependencySet.getUnpackOptions() != null;
    }

    private boolean unpackTransformsContent(DependencySet dependencySet) {
        return isUnpackWithOptions(dependencySet) && isContentModifyingOption(dependencySet.getUnpackOptions());
    }

    private boolean isContentModifyingOption(UnpackOptions opts) {
        return (opts.isFiltered() || opts.getLineEnding() != null);
    }

    private void checkMultiArtifactOutputConfig(final DependencySet dependencySet) {
        String dir = dependencySet.getOutputDirectory();
        if (dir == null) {
            dir = defaultOutputDirectory;
        }

        String mapping = dependencySet.getOutputFileNameMapping();
        if (mapping == null) {
            mapping = defaultOutputFileNameMapping;
        }

        if ((dir == null || !dir.contains("${")) && (mapping == null || !mapping.contains("${"))) {
            LOGGER.warn("NOTE: Your assembly specifies a dependencySet that matches multiple artifacts, but "
                    + "specifies a concrete output format. THIS MAY RESULT IN ONE OR MORE ARTIFACTS BEING "
                    + "OBSCURED!\n\n" + "Output directory: '" + dir + "'\nOutput filename mapping: '" + mapping
                    + "'");
        }
    }

    private void addNormalArtifact(
            final DependencySet dependencySet,
            final AssemblyArtifact depArtifact,
            final Project depProject,
            final Archiver archiver,
            final AssemblerConfigurationSource configSource,
            InputStreamTransformer fileSetTransformers)
            throws AssemblyFormattingException, ArchiveCreationException {
        LOGGER.debug("Adding dependency artifact " + depArtifact.getId() + ".");

        String encoding = isUnpackWithOptions(dependencySet)
                ? dependencySet.getUnpackOptions().getEncoding()
                : null;
        Charset charset = encoding != null ? Charset.forName(encoding) : null;
        final AddArtifactTask task = new AddArtifactTask(depArtifact, fileSetTransformers, charset);

        task.setProject(depProject);
        task.setModuleProject(moduleProject);
        task.setModuleArtifact(moduleArtifact);
        task.setOutputDirectory(dependencySet.getOutputDirectory(), defaultOutputDirectory);
        task.setFileNameMapping(dependencySet.getOutputFileNameMapping(), defaultOutputFileNameMapping);

        final int dirMode = TypeConversionUtils.modeToInt(dependencySet.getDirectoryMode(), LOGGER);
        if (dirMode != -1) {
            task.setDirectoryMode(dirMode);
        }

        final int fileMode = TypeConversionUtils.modeToInt(dependencySet.getFileMode(), LOGGER);
        if (fileMode != -1) {
            task.setFileMode(fileMode);
        }

        task.setUnpack(dependencySet.isUnpack());

        final UnpackOptions opts = dependencySet.getUnpackOptions();
        if (isUnpackWithOptions(dependencySet)) {
            task.setIncludes(opts.getIncludes());
            task.setExcludes(opts.getExcludes());
            task.setUsingDefaultExcludes(opts.isUseDefaultExcludes());
        }

        task.execute(archiver, configSource);
    }

    Set<AssemblyArtifact> resolveDependencyArtifacts(final Session session, final DependencySet dependencySet)
            throws InvalidAssemblerConfigurationException {
        final Set<AssemblyArtifact> dependencyArtifacts = new LinkedHashSet<>();
        if (resolvedArtifacts != null) {
            dependencyArtifacts.addAll(resolvedArtifacts);
        }

        if (dependencySet.isUseProjectArtifact()) {
            final AssemblyArtifact projectArtifact = MavenProjects.mainArtifact(session, project);
            if (projectArtifact.getFile() != null) {
                dependencyArtifacts.add(projectArtifact);
            } else {
                LOGGER.warn("Cannot include project artifact: " + projectArtifact
                        + "; it doesn't have an associated file or directory.");
            }
        }

        if (dependencySet.isUseProjectAttachments()) {
            final List<AssemblyArtifact> attachments = MavenProjects.attachedArtifacts(session, project);
            if (attachments != null) {
                for (final AssemblyArtifact attachment : attachments) {
                    if (attachment.getFile() != null) {
                        dependencyArtifacts.add(attachment);
                    } else {
                        LOGGER.warn("Cannot include attached artifact: " + project.getId() + " for project: "
                                + project.getId() + "; it doesn't have an associated file or directory.");
                    }
                }
            }
        }

        if (dependencySet.isUseTransitiveFiltering()) {
            LOGGER.debug("Filtering dependency artifacts USING transitive dependency path information.");
        } else {
            LOGGER.debug("Filtering dependency artifacts WITHOUT transitive dependency path information.");
        }

        final Predicate<AssemblyArtifact> filter = FilterUtils.newScopeFilter(dependencySet.getScope());

        FilterUtils.filterArtifacts(
                dependencyArtifacts,
                dependencySet.getIncludes(),
                dependencySet.getExcludes(),
                dependencySet.isUseStrictFiltering(),
                dependencySet.isUseTransitiveFiltering(),
                LOGGER,
                filter);

        return dependencyArtifacts;
    }

    private void addNonArchiveDependency(
            final AssemblyArtifact depArtifact,
            final Project depProject,
            final DependencySet dependencySet,
            final Archiver archiver,
            final AssemblerConfigurationSource configSource)
            throws AssemblyFormattingException, ArchiveCreationException {
        final File source = depArtifact.getFile();

        String outputDirectory = dependencySet.getOutputDirectory();

        FixedStringSearchInterpolator moduleProjectInterpolator =
                AssemblyFormatUtils.moduleProjectInterpolator(configSource.getSession(), moduleProject);
        // the artifact itself stands in for the project when the POM of a dependency could not be built
        FixedStringSearchInterpolator artifactProjectInterpolator = FixedStringSearchInterpolator.create(
                AssemblyFormatUtils.artifactProjectInterpolator(configSource.getSession(), depProject),
                AssemblyFormatUtils.artifactInterpolator(depArtifact));
        outputDirectory = AssemblyFormatUtils.getOutputDirectory(
                outputDirectory,
                depProject == null ? null : depProject.getBuild().getFinalName(),
                configSource,
                moduleProjectInterpolator,
                artifactProjectInterpolator);

        final String destName = AssemblyFormatUtils.evaluateFileNameMapping(
                dependencySet.getOutputFileNameMapping(),
                depArtifact,
                configSource.getProject(),
                moduleArtifact,
                configSource,
                moduleProjectInterpolator,
                artifactProjectInterpolator);

        String target;

        // omit the last char if ends with / or \\
        if (outputDirectory.endsWith("/") || outputDirectory.endsWith("\\")) {
            target = outputDirectory + destName;
        } else {
            target = outputDirectory + "/" + destName;
        }

        try {
            final int mode = TypeConversionUtils.modeToInt(dependencySet.getFileMode(), LOGGER);
            if (mode > -1) {
                archiver.addFile(source, target, mode);
            } else {
                archiver.addFile(source, target);
            }
        } catch (final ArchiverException e) {
            throw new ArchiveCreationException("Error adding file to archive: " + e.getMessage(), e);
        }
    }

    public List<DependencySet> getDependencySets() {
        return dependencySets;
    }

    public void setDefaultOutputDirectory(final String defaultOutputDirectory) {
        this.defaultOutputDirectory = defaultOutputDirectory;
    }

    public void setDefaultOutputFileNameMapping(final String defaultOutputFileNameMapping) {
        this.defaultOutputFileNameMapping = defaultOutputFileNameMapping;
    }

    public void setModuleProject(final Project moduleProject) {
        this.moduleProject = moduleProject;
    }

    public void setModuleArtifact(final AssemblyArtifact moduleArtifact) {
        this.moduleArtifact = moduleArtifact;
    }
}
