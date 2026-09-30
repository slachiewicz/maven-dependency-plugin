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
package org.apache.maven.plugins.dependency.resolvers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.plugins.dependency.fromDependencies.AbstractDependencyFilterMojo;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ClassifierFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.artifact.filter.collection.TypeFilter;

/**
 * Goal that resolves all project dependencies, including plugins and reports and their dependencies.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @author Maarten Mulders
 * @author Lisa Hardy
 * @since 2.0
 */
@Mojo(name = "go-offline")
public class GoOfflineMojo extends AbstractDependencyFilterMojo {

    /**
     * Don't resolve plugins and artifacts that are in the current reactor.
     *
     * @since 2.7
     */
    @Parameter(property = "excludeReactor", defaultValue = "true")
    protected boolean excludeReactor;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * Main entry into mojo. Gets the list of dependencies, resolves all that are not in the Reactor, and iterates
     * through displaying the resolved versions.
     *
     * @throws MojoException with a message if an error occurs
     */
    @Override
    protected void doExecute() throws MojoException {

        try {
            final Set<Plugin> plugins = getProjectPlugins();

            for (Plugin plugin : plugins) {
                Artifact artifact = getResolverUtil().resolvePlugin(plugin);

                logMessage("Resolved plugin: " + DependencyUtil.getFormattedFileName(artifact, false));
                if (!excludeTransitive) {
                    logMessage("Resolved plugin dependency:");
                    Map<Dependency, Path> artifacts =
                            getResolverUtil().resolveDependencies(plugin, getDependencyFilter());
                    for (Dependency a : artifacts.keySet()) {
                        logMessage("      " + DependencyUtil.getFormattedFileName(a, false));
                    }
                }
            }

            final List<Dependency> dependencies = resolveDependencyArtifacts();

            for (Dependency artifact : dependencies) {
                logMessage("Resolved dependency: " + DependencyUtil.getFormattedFileName(artifact, false));
            }

        } catch (ArtifactFilterException | RuntimeException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private Predicate<org.apache.maven.api.model.Dependency> getDependencyFilter() {
        if (excludeReactor) {
            return new ExcludeReactorProjectsDependencyFilter(session.getProjects());
        } else {
            return __ -> true;
        }
    }

    private Set<Plugin> getProjectPlugins() {
        Predicate<Plugin> pluginsFilter = new PluginsIncludeExcludeFilter(
                toList(includeGroupIds),
                toList(excludeGroupIds),
                toList(includeArtifactIds),
                toList(excludeArtifactIds));

        Predicate<Plugin> reactorExclusionFilter = plugin -> true;
        if (excludeReactor) {
            reactorExclusionFilter = new PluginsReactorExcludeFilter(session.getProjects());
        }

        return getResolverUtil().getProjectPlugins(getProject()).stream()
                .filter(reactorExclusionFilter)
                .filter(pluginsFilter)
                .collect(Collectors.toSet());
    }

    private List<String> toList(String list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(DependencyUtil.cleanToBeTokenizedString(list).split(","));
    }

    private void logMessage(String message) {
        if (isSilent()) {
            getLog().debug(message);
        } else {
            getLog().info(message);
        }
    }

    /**
     * Resolves the dependencies of the project that the filters let through, with their transitive dependencies.
     *
     * @return the resolved dependencies
     * @throws ArtifactFilterException in case of a filter problem
     */
    protected List<Dependency> resolveDependencyArtifacts() throws ArtifactFilterException {
        // the filters work on dependencies, the resolver needs the coordinates they were made of
        Map<Dependency, DependencyCoordinates> coordinates = new LinkedHashMap<>();
        for (DependencyCoordinates dependency : getProject().getDependencies()) {
            Artifact artifact = session.createArtifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersionConstraint().toString(),
                    dependency.getClassifier(),
                    dependency.getType().getExtension(),
                    dependency.getType().id());
            coordinates.put(
                    ResolvedDependency.of(
                            session, artifact, dependency.getType().id(), dependency.getScope(), null),
                    dependency);
        }

        Set<Dependency> filtered = getArtifactsFilter().filter(coordinates.keySet());

        List<DependencyCoordinates> dependableCoordinates = new ArrayList<>();
        for (Dependency dependency : filtered) {
            dependableCoordinates.add(coordinates.get(dependency));
        }

        return new ArrayList<>(dependencyResolver
                .resolve(DependencyResolverRequest.builder()
                        .session(session)
                        .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                        .rootArtifact(getProject().getPomArtifact())
                        .dependencies(dependableCoordinates)
                        .managedDependencies(getProject().getManagedDependencies())
                        .repositories(getResolverUtil().getProjectRepositories())
                        .pathScope(PathScope.TEST_RUNTIME)
                        .build())
                .getDependencies()
                .keySet());
    }

    // TODO: refactor this to use Resolver API filters
    protected FilterArtifacts getArtifactsFilter() {
        final FilterArtifacts filter = new FilterArtifacts();

        if (excludeReactor) {
            filter.addFilter(new ExcludeReactorProjectsArtifactFilter(session.getProjects(), getLog()));
        }

        filter.addFilter(new ScopeFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeScope),
                DependencyUtil.cleanToBeTokenizedString(this.excludeScope)));

        filter.addFilter(new TypeFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeTypes),
                DependencyUtil.cleanToBeTokenizedString(this.excludeTypes)));

        filter.addFilter(new ClassifierFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeClassifiers),
                DependencyUtil.cleanToBeTokenizedString(this.excludeClassifiers)));

        filter.addFilter(new GroupIdFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeGroupIds),
                DependencyUtil.cleanToBeTokenizedString(this.excludeGroupIds)));

        filter.addFilter(new ArtifactIdFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeArtifactIds),
                DependencyUtil.cleanToBeTokenizedString(this.excludeArtifactIds)));

        return filter;
    }

    @Override
    protected ArtifactsFilter getMarkedArtifactFilter() {
        return null;
    }
}
