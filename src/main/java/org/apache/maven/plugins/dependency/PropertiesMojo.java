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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ParamArtifact;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;

/**
 * Goal that sets a property pointing to the artifact file for each project dependency. For each dependency (direct and
 * transitive) a project property will be set which follows the <code>groupId:artifactId:type:[classifier]</code> form
 * and contains the path to the resolved artifact.
 *
 * @author Paul Gier
 * @since 2.2
 */
@Mojo(name = "properties", defaultPhase = "initialize")
public class PropertiesMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    private DependencyResolver dependencyResolver;

    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Extra artifacts that can be provided to the plugin. For each artifact in this list a property will be set
     * similar to the project artifacts discovered. This allows callers to supply additional
     * resolved dependencies for which properties pointing to the artifact file shall be created.
     *
     * Example usage in plugin configuration:
     * <pre>
     * &lt;extraArtifacts&gt;
     *   &lt;extraArtifact&gt;
     *      &lt;artifact&gt;org.example:my-artifact:jar:1.0&lt;/artifact&gt;
     *   &lt;/extraArtifact&gt;
     *   &lt;extraArtifact&gt;
     *      &lt;groupId&gt;org.example&lt;/groupId&gt;
     *      &lt;artifactId&gt;my-artifact&lt;/artifactId&gt;
     *      &lt;version&gt;1.0&lt;/version&gt;
     *   &lt;/extraArtifact&gt;
     * &lt;/extraArtifacts&gt;
     * </pre>
     * @since 3.11.0
     */
    @Parameter
    private List<ParamArtifact> extraArtifacts;

    /**
     * Main entry into mojo. Gets the list of dependencies and iterates through setting a property for each artifact.
     * Gets the list of declared plugin's extra artifacts and iterates through setting a property for each artifact.
     * @throws MojoException with a message if an error occurs
     */
    @Override
    public void execute() throws MojoException {
        if (isSkip()) {
            log.info("Skipping plugin execution");
            return;
        }

        ProjectManager projectManager = session.getService(ProjectManager.class);
        Map<Dependency, Path> artifacts = dependencyResolver
                .resolve(session, project, PathScope.TEST_RUNTIME)
                .getDependencies();

        for (Map.Entry<Dependency, Path> artifact : artifacts.entrySet()) {
            projectManager.setProperty(
                    project,
                    DependencyUtil.getDependencyConflictId(artifact.getKey()),
                    artifact.getValue().toAbsolutePath().toString());
        }

        if (extraArtifacts != null) {
            ResolverUtil resolverUtil = new ResolverUtil(session, project);
            try {
                for (ParamArtifact paramArtifact : extraArtifacts) {

                    if (!paramArtifact.isDataSet()) {
                        throw new MojoException("You must specify an artifact OR GAV separately");
                    }

                    Artifact artifact = resolverUtil.createArtifactFromParams(paramArtifact);
                    DownloadedArtifact resolved =
                            resolverUtil.resolveArtifact(artifact, resolverUtil.getProjectRepositories());

                    projectManager.setProperty(
                            project,
                            toConflictId(resolved),
                            resolved.getPath().toAbsolutePath().toString());
                }
            } catch (RuntimeException e) {
                throw new MojoException("Couldn't download artifact: " + e.getMessage(), e);
            }
        }
    }

    public boolean isSkip() {
        return skip;
    }

    private String toConflictId(Artifact artifact) {
        // The conflict ID is the same as the one used by Maven's Artifact class, which is
        // groupId:artifactId:type[:classifier]
        StringBuilder sb = new StringBuilder();
        sb.append(artifact.getGroupId())
                .append(":")
                .append(artifact.getArtifactId())
                .append(":")
                .append(artifact.getExtension());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            sb.append(":").append(artifact.getClassifier());
        }
        return sb.toString();
    }
}
