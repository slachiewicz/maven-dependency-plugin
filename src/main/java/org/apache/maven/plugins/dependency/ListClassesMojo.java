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
package org.apache.maven.plugins.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.utils.ParamArtifact;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;

/**
 * Retrieves and lists all classes contained in the specified artifact from the specified remote repositories.
 *
 * @since 3.1.3
 */
@Mojo(name = "list-classes", projectRequired = false)
public class ListClassesMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    @Nullable
    private Project project;

    /**
     * The group ID of the artifact to download. Ignored if {@code artifact} is used.
     *
     * @since 3.1.3
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * @param groupId the value to set
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * The artifact ID of the artifact to download. Ignored if {@code artifact} is used.
     *
     * @since 3.1.3
     */
    @Parameter(property = "artifactId")
    private String artifactId;

    /**
     * @param artifactId the value to set
     */
    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    /**
     * The version of the artifact to download. Ignored if {@code artifact} is used.
     *
     * @since 3.1.3
     */
    @Parameter(property = "version")
    private String version;

    /**
     * @param version the value to set
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * The classifier of the artifact to download. Ignored if {@code artifact} is used.
     *
     * @since 3.1.3
     */
    @Parameter(property = "classifier")
    private String classifier;

    /**
     * @param classifier the value to set
     */
    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    /**
     * The packaging of the artifact to download. Ignored if {@code artifact} is used.
     *
     * @since 3.1.3
     */
    @Parameter(property = "packaging", defaultValue = "jar")
    private String packaging;

    /**
     * @param packaging the value to set
     */
    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    /**
     * A string of the form {@code groupId:artifactId:version[:packaging[:classifier]]}, or, since 3.12.0, the form
     * that Maven's error messages and {@code dependency:list} print,
     * {@code groupId:artifactId:type[:classifier]:version[:scope]}. The form is told apart by which part looks like
     * a version, as described for the {@code artifact} parameter of {@code dependency:get}.
     *
     * @since 3.1.3
     */
    @Parameter(property = "artifact")
    private String artifact;

    /**
     * @param artifact the value to set
     */
    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    /**
     * Repositories in the format {@code id::[layout::]url} or just URLs. That is,
     * <code>
     * central::default::https://repo.maven.apache.org/maven2,myrepo::https://repo.acme.com,https://repo.acme2.com
     * </code>
     *
     * @since 3.1.3
     */
    @Parameter(property = "remoteRepositories")
    private List<String> remoteRepositories;

    /**
     * Download transitively, retrieving the specified artifact and all of its dependencies.
     *
     * @since 3.1.3
     */
    @Parameter(property = "transitive", defaultValue = "false")
    private boolean transitive = false;

    /**
     * Skip plugin execution completely.
     *
     * @since 3.6.0
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoException {
        if (skip) {
            log.info("Skipping plugin execution");
            return;
        }

        ParamArtifact paramArtifact = new ParamArtifact();
        paramArtifact.setGroupId(groupId);
        paramArtifact.setArtifactId(artifactId);
        paramArtifact.setVersion(version);
        paramArtifact.setClassifier(classifier);
        paramArtifact.setPackaging(packaging);
        paramArtifact.setArtifact(artifact);

        if (!paramArtifact.isDataSet()) {
            throw new MojoException("You must specify an artifact OR GAV separately, "
                    + "e.g. -Dartifact=org.apache.maven.plugins:maven-downloader-plugin:1.0 OR "
                    + "-DgroupId=org.apache.maven.plugins -DartifactId=maven-downloader-plugin -Dversion=1.0");
        }

        ResolverUtil resolverUtil = new ResolverUtil(session, project);
        Artifact resolved = resolverUtil.createArtifactFromParams(paramArtifact);

        try {
            if (transitive) {
                Map<Dependency, Path> artifacts =
                        resolverUtil.resolveDependencies(resolved, resolverUtil.remoteRepositories(remoteRepositories));

                for (Path a : artifacts.values()) {
                    printClassesFromArtifactResult(a.toFile());
                }
            } else {
                DownloadedArtifact a =
                        resolverUtil.resolveArtifact(resolved, resolverUtil.remoteRepositories(remoteRepositories));
                printClassesFromArtifactResult(a.getPath().toFile());
            }
        } catch (IOException | RuntimeException e) {
            throw new MojoException("Couldn't download artifact: " + e.getMessage(), e);
        }
    }

    private void printClassesFromArtifactResult(File file) throws IOException {
        // open jar file in try-with-resources statement to guarantee the file closes after use regardless of errors
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // filter out files that do not end in .class
                if (!entryName.endsWith(".class")) {
                    continue;
                }

                // remove .class from the end and change format to use periods instead of forward slashes
                String className =
                        entryName.substring(0, entryName.length() - 6).replace('/', '.');
                log.info(className);
            }
        }
    }
}
