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

import java.util.List;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
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
 * Resolves a single artifact, eventually transitively, from the specified remote repositories. Caveat: will always
 * check the central repository defined in the super pom. You could use a mirror entry in your <code>settings.xml</code>
 */
@Mojo(name = "get", projectRequired = false)
public class GetMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    @Nullable
    private Project project;

    /**
     * Repositories in the format {@code id::[layout::]url} or just URLs, separated by comma. That is,
     * <code>
     * central::default::https://repo.maven.apache.org/maven2,myrepo::https://repo.acme.com,https://repo.acme2.com
     * </code>
     * <p>
     * The comma-separated form works both on the command line and as the text of a single
     * <code>&lt;remoteRepositories&gt;</code> element. In a POM the repositories may also be listed one per
     * element:
     * <pre>
     * &lt;remoteRepositories&gt;
     *   &lt;remoteRepository&gt;central::default::https://repo.maven.apache.org/maven2&lt;/remoteRepository&gt;
     *   &lt;remoteRepository&gt;https://repo.acme.com&lt;/remoteRepository&gt;
     * &lt;/remoteRepositories&gt;
     * </pre>
     */
    @Parameter(property = "remoteRepositories")
    private List<String> remoteRepositories;

    /**
     * Resolve transitively, retrieving the specified artifact and all of its dependencies.
     */
    @Parameter(property = "transitive", defaultValue = "true")
    private boolean transitive = true;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoException {
        if (isSkip()) {
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

        Artifact resolved;
        List<RemoteRepository> repositories;
        ResolverUtil resolverUtil = new ResolverUtil(session, project);
        try {
            resolved = resolverUtil.createArtifactFromParams(paramArtifact);
            repositories = resolverUtil.remoteRepositories(remoteRepositories, "always");
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        }

        try {
            if (transitive) {
                log.info("Resolving " + resolved + " with transitive dependencies");
                resolverUtil.resolveDependencies(resolved, repositories);
            } else {
                log.info("Resolving " + resolved);
                resolverUtil.resolveArtifact(resolved, repositories);
            }
        } catch (RuntimeException e) {
            throw new MojoException("Couldn't download artifact: " + e.getMessage(), e);
        }
    }

    /**
     * @return {@link #skip}
     */
    protected boolean isSkip() {
        return skip;
    }

    /**
     * The groupId of the artifact to resolve. Ignored if {@code artifact} is used.
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
     * The artifactId of the artifact to resolve. Ignored if {@code artifact} is used.
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
     * The version of the artifact to resolve. Ignored if {@code artifact} is used.
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
     * The classifier of the artifact to resolve. Ignored if {@code artifact} is used.
     *
     * @since 2.3
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
     * The packaging of the artifact to resolve. Ignored if {@code artifact} is used.
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
     * A string of the form groupId:artifactId:version[:packaging[:classifier]].
     */
    @Parameter(property = "artifact")
    private String artifact;

    /**
     * @param artifact the value to set
     */
    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }
}
