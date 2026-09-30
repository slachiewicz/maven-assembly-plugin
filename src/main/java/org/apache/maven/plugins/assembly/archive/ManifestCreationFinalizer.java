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
package org.apache.maven.plugins.assembly.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.shared.archiver.MavenArchiveConfiguration;
import org.apache.maven.shared.archiver.MavenArchiver;
import org.apache.maven.shared.archiver.MavenArchiverException;
import org.codehaus.plexus.archiver.AbstractArchiveFinalizer;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.ManifestException;

/**
 *
 */
public class ManifestCreationFinalizer extends AbstractArchiveFinalizer {

    private final Project project;

    private final Session session;

    private final MavenArchiveConfiguration archiveConfiguration;

    // TODO: I'd really prefer to rewrite MavenArchiver as either a
    // separate manifest creation utility (and to
    // create an include pom.properties etc into another archiver), or
    // an implementation of an archiver
    // (the first is preferable).
    private final MavenArchiver mavenArchiver = new MavenArchiver();

    public ManifestCreationFinalizer(
            final Session session, final Project project, final MavenArchiveConfiguration archiveConfiguration) {
        this.session = session;
        this.project = project;
        this.archiveConfiguration = archiveConfiguration;
    }

    @Override
    public void finalizeArchiveCreation(final Archiver archiver) {
        if (archiveConfiguration != null) {
            try {
                Manifest manifest;
                final File manifestFile = archiveConfiguration.getManifestFile() == null
                        ? null
                        : archiveConfiguration.getManifestFile().toFile();

                if (manifestFile != null) {
                    try (InputStream in = new FileInputStream(manifestFile)) {
                        manifest = new Manifest(in);
                    } catch (final FileNotFoundException e) {
                        throw new ArchiverException("Manifest not found: " + e.getMessage(), e);
                    } catch (final IOException e) {
                        throw new ArchiverException("Error processing manifest: " + e.getMessage(), e);
                    }
                } else {
                    manifest = mavenArchiver.getManifest(session, project, archiveConfiguration);
                }

                if (manifest != null
                        && !archiveConfiguration.getManifestEntries().isEmpty()) {
                    for (Map.Entry<String, String> entry :
                            archiveConfiguration.getManifestEntries().entrySet()) {
                        manifest.getMainSection()
                                .addConfiguredAttribute(new Manifest.Attribute(entry.getKey(), entry.getValue()));
                    }
                }

                if ((manifest != null) && (archiver instanceof JarArchiver)) {
                    final JarArchiver jarArchiver = (JarArchiver) archiver;
                    jarArchiver.addConfiguredManifest(manifest);
                }
            } catch (final ManifestException | MavenArchiverException e) {
                throw new ArchiverException("Error creating manifest: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<String> getVirtualFiles() {
        if (archiveConfiguration != null) {
            try {
                if (mavenArchiver.getManifest(session, project, archiveConfiguration.getManifest()) != null) {
                    return Collections.singletonList("META-INF/MANIFEST.MF");
                }
            } catch (final MavenArchiverException ignore) {
                // noop
            }
        }

        return null;
    }
}
