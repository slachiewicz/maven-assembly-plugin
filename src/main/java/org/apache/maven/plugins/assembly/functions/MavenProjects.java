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
package org.apache.maven.plugins.assembly.functions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.slf4j.Logger;

/**
 *
 */
public class MavenProjects {
    public static void without(Iterable<Project> source, String packagingType, MavenProjectConsumer consumer) {
        for (Project project : source) {
            if (!packagingType.equals(project.getPackaging().id())) {
                consumer.accept(project);
            }
        }
    }

    public static void select(Iterable<Project> source, String packagingType, MavenProjectConsumer consumer) {
        for (Project project : source) {
            if (packagingType.equals(project.getPackaging().id())) {
                consumer.accept(project);
            }
        }
    }

    public static void select(
            Iterable<Project> source,
            String packagingType,
            MavenProjectConsumer include,
            MavenProjectConsumer excluded) {
        for (Project project : source) {
            if (packagingType.equals(project.getPackaging().id())) {
                include.accept(project);
            } else {
                excluded.accept(project);
            }
        }
    }

    /**
     * The main artifact of the project, as {@code MavenProject#getArtifact()} used to return it: always present, with
     * a file only once the project has been packaged.
     */
    public static AssemblyArtifact mainArtifact(Session session, Project project) {
        final ArtifactManager artifactManager = session.getService(ArtifactManager.class);
        final java.util.Optional<ProducedArtifact> main = project.getMainArtifact();
        final ProducedArtifact artifact = main.orElseGet(project::getPomArtifact);
        // a project with pom packaging produces no main artifact, as with Maven 3 its artifact has no file
        final java.nio.file.Path path =
                main.isPresent() ? artifactManager.getPath(artifact).orElse(null) : null;
        return AssemblyArtifact.ofProduced(
                session, artifact, project.getPackaging().type().id(), path == null ? null : path.toFile());
    }

    /**
     * The artifacts attached to the project, as {@code MavenProject#getAttachedArtifacts()} used to return them.
     */
    public static List<AssemblyArtifact> attachedArtifacts(Session session, Project project) {
        final ProjectManager projectManager = session.getService(ProjectManager.class);
        final ArtifactManager artifactManager = session.getService(ArtifactManager.class);
        final List<AssemblyArtifact> result = new ArrayList<>();
        for (final ProducedArtifact attachment : projectManager.getAttachedArtifacts(project)) {
            result.add(AssemblyArtifact.ofProduced(
                    session,
                    attachment,
                    attachment.getExtension(),
                    artifactManager
                            .getPath(attachment)
                            .map(java.nio.file.Path::toFile)
                            .orElse(null)));
        }
        return result;
    }

    /**
     * Finds the attached artifact with the given classifier.
     *
     * @return the artifact, or {@code null} if the project has no attachment with that classifier
     */
    public static AssemblyArtifact findArtifactByClassifier(Session session, Project project, String classifier) {
        for (final AssemblyArtifact attachment : attachedArtifacts(session, project)) {
            if (classifier.equals(attachment.getClassifier())) {
                return attachment;
            }
        }
        return null;
    }

    public static MavenProjectConsumer log(final Logger logger) {
        return new MavenProjectConsumer() {
            @Override
            public void accept(Project project) {
                final String projectId = project.getGroupId() + ":" + project.getArtifactId();

                logger.debug("Excluding POM-packaging module: " + projectId);
            }
        };
    }

    public static MavenProjectConsumer addTo(final Set<Project> set) {
        return new MavenProjectConsumer() {
            @Override
            public void accept(Project project) {
                set.add(project);
            }
        };
    }
}
