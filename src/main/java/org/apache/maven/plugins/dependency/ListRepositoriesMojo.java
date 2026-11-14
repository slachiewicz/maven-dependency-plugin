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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;

/**
 * Goal that collects all project dependencies and then lists the dependency repositories used by the build and
 * transitive dependencies, along with the plugin repositories used by the build.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @since 2.2
 */
@Mojo(name = "list-repositories")
public class ListRepositoriesMojo extends AbstractDependencyMojo {

    /**
     * Displays a list of the repositories used by this build.
     *
     * @throws MojoException with a message if an error occurs
     */
    @Override
    protected void doExecute() throws MojoException {
        Project project = getProject();

        Map<String, RemoteRepository> repositories = new LinkedHashMap<>();
        try {
            // only collect the graph, nothing is downloaded
            DependencyResolverResult result = session.getService(DependencyResolver.class)
                    .collect(DependencyResolverRequest.builder()
                            .session(session)
                            .requestType(DependencyResolverRequest.RequestType.COLLECT)
                            .project(project)
                            .pathScope(PathScope.TEST_RUNTIME)
                            .build());
            collectRepositories(result.getRoot(), repositories);
        } catch (DependencyResolverException e) {
            throw new MojoException(e.getMessage(), e);
        }

        Map<String, RemoteRepository> pluginRepositories = new LinkedHashMap<>();
        session.getService(ProjectManager.class)
                .getRemotePluginRepositories(project)
                .forEach(repo -> pluginRepositories.put(key(repo), repo));

        if (repositories.isEmpty() && pluginRepositories.isEmpty()) {
            getLog().info("No remote repository is used by this build." + System.lineSeparator());
            return;
        }

        StringBuilder message = new StringBuilder();
        prepareRemoteRepositoriesList(
                message, "Project remote repositories used by this build:", repositories.values());
        prepareRemoteRepositoriesList(message, "Plugin repositories used by this build:", pluginRepositories.values());

        getLog().info(message);
    }

    private void collectRepositories(Node node, Map<String, RemoteRepository> repositories) {
        for (RemoteRepository repo : node.getRemoteRepositories()) {
            repositories.putIfAbsent(key(repo), repo);
        }
        debugLogNodeRepo(node);
        for (Node child : node.getChildren()) {
            collectRepositories(child, repositories);
        }
    }

    private static String key(RemoteRepository repo) {
        return repo.getId() + '|' + repo.getUrl();
    }

    private void debugLogNodeRepo(Node node) {
        if (!getLog().isDebugEnabled()) {
            return;
        }

        getLog().debug("Node: " + node.asString() + " resolved from:");
        node.getRemoteRepositories().forEach(repo -> getLog().debug(" - " + describe(repo)));
    }

    private static String describe(RemoteRepository repo) {
        return repo.getId() + " (" + repo.getUrl() + ", " + repo.getType() + ")";
    }

    private void prepareRemoteRepositoriesList(
            StringBuilder message, String heading, Collection<RemoteRepository> repositories) {
        if (repositories.isEmpty()) {
            return;
        }

        message.append(heading).append(System.lineSeparator());
        repositories.forEach(
                repo -> message.append(" * ").append(describe(repo)).append(System.lineSeparator()));
    }
}
