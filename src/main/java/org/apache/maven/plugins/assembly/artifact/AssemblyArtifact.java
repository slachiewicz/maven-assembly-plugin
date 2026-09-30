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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;

/**
 * An artifact as the assembly plugin sees it: coordinates, type, scope and the file it was resolved to. This
 * replaces the Maven 3 {@code org.apache.maven.artifact.Artifact}, which had a file, a scope and a dependency trail
 * all in one mutable object. The Maven 4 API splits these across {@link Artifact}, {@link Dependency} and a separate
 * path, so the plugin keeps its own small immutable holder and exposes the getter names that descriptor expressions
 * such as <code>${artifact.artifactId}</code> or <code>${artifact.handler.extension}</code> rely on.
 */
public final class AssemblyArtifact {
    public static final String SCOPE_COMPILE = "compile";

    public static final String SCOPE_PROVIDED = "provided";

    public static final String SCOPE_RUNTIME = "runtime";

    public static final String SCOPE_SYSTEM = "system";

    public static final String SCOPE_TEST = "test";

    private final Session session;

    private final String groupId;

    private final String artifactId;

    private final String version;

    private final String baseVersion;

    private final String classifier;

    private final String type;

    private final String extension;

    private final String scope;

    private final boolean optional;

    private final boolean snapshot;

    private final File file;

    private final Dependency dependency;

    private final List<Dependency> ancestors;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AssemblyArtifact(
            Session session,
            String groupId,
            String artifactId,
            String version,
            String baseVersion,
            String classifier,
            String type,
            String extension,
            String scope,
            boolean optional,
            File file,
            Dependency dependency,
            List<Dependency> ancestors) {
        this.session = session;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.baseVersion = baseVersion != null ? baseVersion : version;
        this.classifier = classifier == null || classifier.isEmpty() ? null : classifier;
        this.type = type;
        this.extension = extension != null ? extension : type;
        this.scope = scope;
        this.optional = optional;
        this.snapshot = this.baseVersion != null && this.baseVersion.endsWith("SNAPSHOT");
        this.file = file;
        this.dependency = dependency;
        this.ancestors = ancestors == null ? Collections.emptyList() : Collections.unmodifiableList(ancestors);
    }

    /**
     * Wraps a resolved dependency.
     *
     * @param ancestors the dependencies between the project and {@code dependency}, nearest to the project first
     */
    public static AssemblyArtifact of(Session session, Dependency dependency, File file, List<Dependency> ancestors) {
        return new AssemblyArtifact(
                session,
                dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getVersion().toString(),
                dependency.getBaseVersion().toString(),
                dependency.getClassifier(),
                dependency.getType().id(),
                dependency.getExtension(),
                dependency.getScope().id(),
                dependency.isOptional(),
                file,
                dependency,
                ancestors);
    }

    /**
     * Wraps an artifact produced by a project (its main artifact or an attachment).
     */
    public static AssemblyArtifact ofProduced(Session session, Artifact artifact, String type, File file) {
        return new AssemblyArtifact(
                session,
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion().toString(),
                artifact.getBaseVersion().toString(),
                artifact.getClassifier(),
                type,
                artifact.getExtension(),
                SCOPE_COMPILE,
                false,
                file,
                null,
                null);
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getBaseVersion() {
        return baseVersion;
    }

    /**
     * @return the classifier, or {@code null} if there is none
     */
    public String getClassifier() {
        return classifier;
    }

    public boolean hasClassifier() {
        return classifier != null;
    }

    public String getType() {
        return type;
    }

    public String getExtension() {
        return extension;
    }

    public String getScope() {
        return scope;
    }

    public boolean isOptional() {
        return optional;
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public File getFile() {
        return file;
    }

    public AssemblyArtifact withFile(File newFile) {
        return new AssemblyArtifact(
                session,
                groupId,
                artifactId,
                version,
                baseVersion,
                classifier,
                type,
                extension,
                scope,
                optional,
                newFile,
                dependency,
                ancestors);
    }

    /**
     * @return the dependencies between the project and this artifact, nearest to the project first; empty for a
     *         direct dependency
     */
    public List<Dependency> getAncestors() {
        return ancestors;
    }

    /**
     * Same format as {@code org.apache.maven.artifact.Artifact#getId()}: {@code groupId:artifactId:type[:classifier]:version}.
     */
    public String getId() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(':').append(artifactId).append(':').append(type);
        if (classifier != null) {
            sb.append(':').append(classifier);
        }
        sb.append(':').append(version);
        return sb.toString();
    }

    public String getDependencyConflictId() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(':').append(artifactId).append(':').append(type);
        if (classifier != null) {
            sb.append(':').append(classifier);
        }
        return sb.toString();
    }

    /**
     * Stands in for the Maven 3 {@code ArtifactHandler} in expressions like {@code ${artifact.handler.extension}}.
     */
    public Handler getArtifactHandler() {
        return new Handler();
    }

    /**
     * @return this artifact as a Maven 4 {@link Dependency}, which is what the common-artifact-filters predicates
     *         accept
     */
    public Dependency toDependency() {
        if (dependency != null) {
            return dependency;
        }
        return new ProducedDependency();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssemblyArtifact)) {
            return false;
        }
        AssemblyArtifact that = (AssemblyArtifact) o;
        return Objects.equals(groupId, that.groupId)
                && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(version, that.version)
                && Objects.equals(type, that.type)
                && Objects.equals(classifier, that.classifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, type, classifier);
    }

    @Override
    public String toString() {
        return getId();
    }

    /**
     * The properties of the Maven 3 {@code ArtifactHandler} that descriptors commonly reference.
     */
    public final class Handler {
        public String getExtension() {
            return extension;
        }

        public String getPackaging() {
            return type;
        }

        public String getClassifier() {
            return classifier;
        }

        public String getDirectory() {
            return type + "s";
        }

        public String getLanguage() {
            return "java";
        }

        public boolean isAddedToClasspath() {
            return "jar".equals(type);
        }

        public boolean isIncludesDependencies() {
            return false;
        }
    }

    private final class ProducedDependency implements Dependency {
        @Override
        public Type getType() {
            return session.requireType(type);
        }

        @Override
        public DependencyScope getScope() {
            return session.requireDependencyScope(scope);
        }

        @Override
        public boolean isOptional() {
            return optional;
        }

        @Override
        public DependencyCoordinates toCoordinates() {
            ArtifactCoordinates coordinates =
                    session.createArtifactCoordinates(groupId, artifactId, version, classifier, extension, type);
            return session.createDependencyCoordinates(coordinates);
        }

        @Override
        public String getGroupId() {
            return groupId;
        }

        @Override
        public String getArtifactId() {
            return artifactId;
        }

        @Override
        public Version getVersion() {
            return session.parseVersion(version);
        }

        @Override
        public Version getBaseVersion() {
            return session.parseVersion(baseVersion);
        }

        @Override
        public String getClassifier() {
            return classifier == null ? "" : classifier;
        }

        @Override
        public String getExtension() {
            return extension;
        }

        @Override
        public boolean isSnapshot() {
            return snapshot;
        }

        @Override
        public String toString() {
            return getId();
        }
    }
}
