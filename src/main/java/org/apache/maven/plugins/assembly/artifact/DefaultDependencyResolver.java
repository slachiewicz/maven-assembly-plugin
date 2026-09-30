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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.archive.phase.ModuleSetAssemblyPhase;
import org.apache.maven.plugins.assembly.functions.MavenProjects;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.ModuleBinaries;
import org.apache.maven.plugins.assembly.model.ModuleSet;
import org.apache.maven.plugins.assembly.utils.FilterUtils;
import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
@Singleton
@Named
public class DefaultDependencyResolver implements DependencyResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDependencyResolver.class);

    @Override
    public Map<DependencySet, Set<AssemblyArtifact>> resolveDependencySets(
            final Assembly assembly,
            ModuleSet moduleSet,
            final AssemblerConfigurationSource configSource,
            List<DependencySet> dependencySets)
            throws DependencyResolutionException {
        Map<DependencySet, Set<AssemblyArtifact>> result = new LinkedHashMap<>();

        for (DependencySet dependencySet : dependencySets) {

            final Project currentProject = configSource.getProject();

            final ResolutionManagementInfo info = new ResolutionManagementInfo();
            updateDependencySetResolutionRequirements(configSource.getSession(), dependencySet, info, currentProject);
            updateModuleSetResolutionRequirements(moduleSet, dependencySet, info, configSource);

            result.put(dependencySet, info.getArtifacts());
        }
        return result;
    }

    @Override
    public Map<DependencySet, Set<AssemblyArtifact>> resolveDependencySets(
            final Assembly assembly,
            final AssemblerConfigurationSource configSource,
            List<DependencySet> dependencySets)
            throws DependencyResolutionException {
        Map<DependencySet, Set<AssemblyArtifact>> result = new LinkedHashMap<>();

        for (DependencySet dependencySet : dependencySets) {

            final Project currentProject = configSource.getProject();

            final ResolutionManagementInfo info = new ResolutionManagementInfo();
            updateDependencySetResolutionRequirements(configSource.getSession(), dependencySet, info, currentProject);

            result.put(dependencySet, info.getArtifacts());
        }
        return result;
    }

    void updateModuleSetResolutionRequirements(
            ModuleSet set,
            DependencySet dependencySet,
            final ResolutionManagementInfo requirements,
            final AssemblerConfigurationSource configSource)
            throws DependencyResolutionException {
        final ModuleBinaries binaries = set.getBinaries();
        if (binaries != null) {
            Set<Project> projects;
            try {
                projects = ModuleSetAssemblyPhase.getModuleProjects(set, configSource, LOGGER);
            } catch (final ArchiveCreationException e) {
                throw new DependencyResolutionException(
                        "Error determining project-set for moduleSet with binaries.", e);
            }

            if (binaries.isIncludeDependencies()) {
                updateDependencySetResolutionRequirements(
                        configSource.getSession(), dependencySet, requirements, projects.toArray(new Project[0]));
            }
        }
    }

    void updateDependencySetResolutionRequirements(
            Session session,
            final DependencySet set,
            final ResolutionManagementInfo requirements,
            final Project... projects)
            throws DependencyResolutionException {
        for (final Project project : projects) {
            if (project == null) {
                continue;
            }

            Set<AssemblyArtifact> dependencyArtifacts;
            try {
                // we need to resolve the project again according to the requested scope. Without transitive
                // dependencies only the declared ones, as mediated by the resolver, are wanted.
                dependencyArtifacts = resolve(session, set.getScope(), project, set.isUseTransitiveDependencies());
            } catch (RuntimeException e) {
                throw new DependencyResolutionException(e.getMessage(), e);
            }

            requirements.addArtifacts(dependencyArtifacts);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Dependencies for project: {} are:\n{}",
                        project.getId(),
                        StringUtils.join(dependencyArtifacts.iterator(), "\n"));
            }
        }
    }

    private Set<AssemblyArtifact> resolve(Session session, String scope, Project project, boolean transitive) {
        // same semantics as DependencyFilterUtils.classpathFilter(scope): the direct dependencies are selected by scope
        final Set<String> scopes = FilterUtils.classpathScopes(Collections.singleton(scope));

        final List<DependencyCoordinates> dependencies = project.getDependencies().stream()
                .filter(d -> scopes.contains(d.getScope().id()))
                .collect(Collectors.toList());

        final AssemblyArtifact projectArtifact = MavenProjects.mainArtifact(session, project);

        // the trail is what lets useTransitiveFiltering match on a dependency's ancestors; it starts with the project
        final Map<String, AssemblyArtifact> artifacts = new LinkedHashMap<>();
        final List<RemoteRepository> repositories =
                session.getService(ProjectManager.class).getRemoteProjectRepositories(project);
        for (PathScope pathScope : pathScopes(scope)) {
            final DependencyResolverRequest request = DependencyResolverRequest.builder()
                    .session(session)
                    .requestType(DependencyResolverRequest.RequestType.COLLECT)
                    .rootArtifact(project.getPomArtifact())
                    .dependencies(dependencies)
                    .managedDependencies(project.getManagedDependencies())
                    .repositories(repositories)
                    .pathScope(pathScope)
                    .build();

            final DependencyResolverResult result = session.getService(
                            org.apache.maven.api.services.DependencyResolver.class)
                    .collect(request);

            final List<org.apache.maven.api.Dependency> trail = new ArrayList<>();
            trail.add(projectArtifact.toDependency());
            for (Node child : result.getRoot().getChildren()) {
                collect(session, repositories, child, transitive, scopes, trail, artifacts);
            }
        }
        return new LinkedHashSet<>(artifacts.values());
    }

    /**
     * The Maven 4 resolver needs a path scope. The Maven 3 code collected everything and filtered by
     * {@code DependencyFilterUtils.classpathFilter(scope)}, which is what the path scopes below correspond to.
     */
    private static List<PathScope> pathScopes(String scope) {
        if (AssemblyArtifact.SCOPE_RUNTIME.equals(scope)) {
            return Collections.singletonList(PathScope.MAIN_RUNTIME);
        } else if (AssemblyArtifact.SCOPE_TEST.equals(scope)) {
            return Arrays.asList(PathScope.TEST_COMPILE, PathScope.TEST_RUNTIME);
        }
        return Collections.singletonList(PathScope.MAIN_COMPILE);
    }

    private void collect(
            Session session,
            List<RemoteRepository> repositories,
            Node node,
            boolean transitive,
            Set<String> scopes,
            List<org.apache.maven.api.Dependency> trail,
            Map<String, AssemblyArtifact> artifacts) {
        final org.apache.maven.api.Dependency dependency = node.getDependency();
        if (dependency == null) {
            return;
        }

        if (scopes.contains(dependency.getScope().id())) {
            final String key = dependency.getGroupId()
                    + ':'
                    + dependency.getArtifactId()
                    + ':'
                    + dependency.getExtension()
                    + ':'
                    + dependency.getClassifier()
                    + ':'
                    + dependency.getVersion();
            if (!artifacts.containsKey(key)) {
                final DownloadedArtifact downloaded = session.resolveArtifact(dependency, repositories);
                final File file = downloaded.getPath().toFile();
                artifacts.put(key, AssemblyArtifact.of(session, dependency, file, new ArrayList<>(trail)));
            }
        }

        if (transitive) {
            trail.add(dependency);
            try {
                for (Node child : node.getChildren()) {
                    collect(session, repositories, child, true, scopes, trail, artifacts);
                }
            } finally {
                trail.remove(trail.size() - 1);
            }
        }
    }
}
