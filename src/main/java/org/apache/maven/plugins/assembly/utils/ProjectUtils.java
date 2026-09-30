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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.api.Project;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.slf4j.Logger;

/**
 *
 */
public final class ProjectUtils {

    private ProjectUtils() {}

    public static String getClassifier(AssemblyArtifact artifact) {
        String classifier = artifact.getClassifier();
        if (classifier != null && classifier.length() == 0) {
            classifier = null;
        }
        return classifier;
    }

    /**
     * The object that descriptor expressions such as <code>${project.build.directory}</code> are evaluated against.
     * A Maven 4 {@link Project} has no {@code getBuild()}-style bean surface of its own, so the effective model stands
     * in for the Maven 3 {@code MavenProject}.
     */
    public static Object interpolationRoot(final Project project) {
        return project.getModel();
    }

    /**
     * The expressions a Maven 3 {@code MavenProject} answered that are not part of the model.
     */
    public static Properties interpolationExtras(final Project project) {
        final Properties extras = new Properties();
        if (project.getBasedir() != null) {
            extras.setProperty("basedir", project.getBasedir().toString());
        }
        if (project.getPomPath() != null) {
            extras.setProperty("file", project.getPomPath().toString());
        }
        extras.setProperty("id", project.getId());
        return extras;
    }

    /**
     * The properties of the effective model.
     */
    public static Properties properties(final Project project) {
        return toProperties(project.getModel().getProperties());
    }

    public static Properties toProperties(final Map<String, String> map) {
        final Properties properties = new Properties();
        if (map != null) {
            properties.putAll(map);
        }
        return properties;
    }

    public static Set<Project> getProjectModules(
            final Project project,
            final List<Project> reactorProjects,
            final boolean includeSubModules,
            final Logger logger)
            throws IOException {
        final Set<Project> singleParentSet = Collections.singleton(project);

        final Set<Project> moduleCandidates = new LinkedHashSet<>(reactorProjects);

        final Set<Project> modules = new LinkedHashSet<>();

        // we temporarily add the master project to the modules set, since this
        // set is pulling double duty as a set of
        // potential module parents in the tree rooted at the master
        // project...this allows us to use the same looping
        // algorithm below to discover both direct modules of the master project
        // AND modules of those direct modules.
        modules.add(project);

        int changed;

        do {
            changed = 0;

            for (final Iterator<Project> candidateIterator = moduleCandidates.iterator();
                    candidateIterator.hasNext(); ) {
                final Project moduleCandidate = candidateIterator.next();

                if (moduleCandidate.getPomPath() == null) {
                    logger.warn("Cannot compute whether " + moduleCandidate.getId() + " is a module of: "
                            + project.getId() + "; it does not have an associated POM file on the local filesystem.");
                    continue;
                }

                Set<Project> currentPotentialParents;
                if (includeSubModules) {
                    currentPotentialParents = new LinkedHashSet<>(modules);
                } else {
                    currentPotentialParents = singleParentSet;
                }

                for (final Project potentialParent : currentPotentialParents) {
                    if (potentialParent.getPomPath() == null) {
                        logger.warn("Cannot use: " + moduleCandidate.getId()
                                + " as a potential module-parent while computing the module set for: "
                                + project.getId()
                                + "; it does not have an associated POM file on the local filesystem.");
                        continue;
                    }

                    // if this parent has an entry for the module candidate in
                    // the path adjustments map, it's a direct
                    // module of that parent.
                    if (projectContainsModule(potentialParent, moduleCandidate)) {
                        // add the candidate to the list of modules (and
                        // potential parents)
                        modules.add(moduleCandidate);

                        // remove the candidate from the candidate pool, because
                        // it's been verified.
                        candidateIterator.remove();

                        // increment the change counter, to show that we
                        // verified a new module on this pass.
                        changed++;

                        // We need to move on to the next candidate since this one was just verified
                        break;
                    }
                }
            }
        } while (changed != 0);

        // remove the master project from the modules set, now that we're done
        // using it as a set of potential module
        // parents...
        modules.remove(project);

        return modules;
    }

    private static boolean projectContainsModule(final Project mainProject, final Project moduleProject)
            throws IOException {
        final List<String> modules = mainProject.getModel().getModules();
        final File basedir = mainProject.getBasedir().toFile();

        final File moduleFile = moduleProject.getPomPath().toFile().getCanonicalFile();

        File moduleBasedir = moduleProject.getBasedir() == null
                ? null
                : moduleProject.getBasedir().toFile();

        if (moduleBasedir == null) {
            moduleBasedir = moduleFile.getParentFile();

            if (moduleBasedir == null) {
                moduleBasedir = new File(".");
            }
        }

        moduleBasedir = moduleBasedir.getCanonicalFile();

        for (final String moduleSubpath : modules) {
            final File moduleDir = new File(basedir, moduleSubpath).getCanonicalFile();

            if (moduleDir.equals(moduleFile) || moduleDir.equals(moduleBasedir)) {
                return true;
            }
        }

        return false;
    }
}
