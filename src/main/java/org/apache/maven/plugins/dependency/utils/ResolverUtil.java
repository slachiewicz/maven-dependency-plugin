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
package org.apache.maven.plugins.dependency.utils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.Relocation;
import org.apache.maven.api.model.ReportPlugin;
import org.apache.maven.api.model.Reporting;
import org.apache.maven.api.model.Repository;
import org.apache.maven.api.model.RepositoryPolicy;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.TypeRegistry;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;

/**
 * Helper class for using the Maven 4 resolution services. Unlike its Maven 3 ancestor it is not a component: the
 * mojos create it with their session and project.
 */
public class ResolverUtil {

    private final Session session;

    private final Project project;

    /**
     * @param session the session
     * @param project the project, may be {@code null} when running outside of a project
     */
    public ResolverUtil(Session session, Project project) {
        this.session = Objects.requireNonNull(session, "session");
        this.project = project;
    }

    /**
     * @return the remote repositories of the current project, or the ones of the session outside of a project
     */
    public List<RemoteRepository> getProjectRepositories() {
        if (project != null) {
            return session.getService(ProjectManager.class).getRemoteProjectRepositories(project);
        }
        return session.getRemoteRepositories();
    }

    /**
     * Returns a copy of the current session using the supplied local repository directory.
     *
     * @param localRepositoryDirectory alternate local repository directory
     * @return session
     */
    public Session localRepositorySession(File localRepositoryDirectory) {
        Objects.requireNonNull(localRepositoryDirectory, "localRepositoryDirectory");
        return session.withLocalRepository(session.createLocalRepository(localRepositoryDirectory.toPath()));
    }

    /**
     * Installs a file into the local repository of the supplied session.
     *
     * @param artifact the artifact to install, its path must be set
     * @param target session holding the target local repository
     */
    public void installArtifact(DownloadedArtifact artifact, Session target) {
        ProducedArtifact produced = target.createProducedArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion().toString(),
                artifact.getClassifier(),
                artifact.getExtension(),
                null);
        target.setArtifactPath(produced, artifact.getPath());
        target.installArtifacts(produced);
    }

    /**
     * Installs a file under an explicit version, used to also install the base version of a timestamped snapshot.
     *
     * @param artifact the artifact to install, its path must be set
     * @param version the version to install it as
     * @param target session holding the target local repository
     */
    public void installArtifact(DownloadedArtifact artifact, String version, Session target) {
        ProducedArtifact produced = target.createProducedArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                version,
                artifact.getClassifier(),
                artifact.getExtension(),
                null);
        target.setArtifactPath(produced, artifact.getPath());
        target.installArtifacts(produced);
    }

    /**
     * Resolve a given artifact. Like the Maven 3 version, it reads the POM of the artifact first, which downloads it
     * and follows its relocation, if any.
     *
     * @param artifact an artifact to resolve
     * @param repositories remote repositories list
     * @return resolved artifact
     */
    public DownloadedArtifact resolveArtifact(Artifact artifact, List<RemoteRepository> repositories) {
        return resolveArtifact(artifact, repositories, session);
    }

    /**
     * Resolve a given artifact with another session, typically one with another local repository. Like the Maven 3
     * version, it reads the POM of the artifact first, which downloads it and follows its relocation, if any.
     *
     * @param artifact an artifact to resolve
     * @param repositories remote repositories list
     * @param target the session to resolve with
     * @return resolved artifact
     */
    public DownloadedArtifact resolveArtifact(Artifact artifact, List<RemoteRepository> repositories, Session target) {
        return target.resolveArtifact(followRelocations(artifact, repositories, target), repositories);
    }

    /**
     * Resolve a given artifact, falling back to its original coordinates when its POM cannot be read.
     *
     * @param artifact an artifact to resolve
     * @param repositories remote repositories list
     * @param target the session to resolve with
     * @return resolved artifact
     */
    public DownloadedArtifact resolveArtifactWithFallback(
            Artifact artifact, List<RemoteRepository> repositories, Session target) {
        try {
            return resolveArtifact(artifact, repositories, target);
        } catch (RuntimeException descriptorException) {
            try {
                return target.resolveArtifact(artifact, repositories);
            } catch (RuntimeException resolutionException) {
                resolutionException.addSuppressed(descriptorException);
                throw resolutionException;
            }
        }
    }

    /**
     * The Maven 4 API has no artifact descriptor reader, which is what follows relocations in Maven 3. This reads the
     * POM of the artifact and follows {@code distributionManagement/relocation} the same way.
     */
    private Artifact followRelocations(Artifact artifact, List<RemoteRepository> repositories, Session target) {
        Artifact current = artifact;
        Set<String> seen = new HashSet<>();
        while (seen.add(current.key())) {
            Artifact pom = target.createArtifact(
                    current.getGroupId(),
                    current.getArtifactId(),
                    current.getVersion().toString(),
                    "",
                    "pom",
                    "pom");
            DownloadedArtifact resolvedPom = target.resolveArtifact(pom, repositories);

            Relocation relocation;
            try {
                Model model = target.getService(ModelXmlFactory.class)
                        .read(XmlReaderRequest.builder()
                                .path(resolvedPom.getPath())
                                .strict(false)
                                .build());
                relocation = model.getDistributionManagement() == null
                        ? null
                        : model.getDistributionManagement().getRelocation();
            } catch (RuntimeException e) {
                // a POM that cannot be read is not relocated
                return current;
            }
            if (relocation == null) {
                return current;
            }
            current = target.createArtifact(
                    relocation.getGroupId() != null ? relocation.getGroupId() : current.getGroupId(),
                    relocation.getArtifactId() != null ? relocation.getArtifactId() : current.getArtifactId(),
                    relocation.getVersion() != null
                            ? relocation.getVersion()
                            : current.getVersion().toString(),
                    current.getClassifier(),
                    current.getExtension(),
                    null);
        }
        return current;
    }

    /**
     * Resolve transitive dependencies for artifact. The artifact itself comes first in the result.
     *
     * @param artifact an artifact to resolve
     * @param repositories remote repositories list
     * @return the artifact and its transitive dependencies, with the files they were resolved to
     */
    public Map<Dependency, Path> resolveDependencies(Artifact artifact, List<RemoteRepository> repositories) {
        return resolveDependencies(session.createDependencyCoordinates(artifact.toCoordinates()), repositories);
    }

    /**
     * Resolve transitive dependencies for a dependency. The dependency itself comes first in the result.
     *
     * @param root the dependency to resolve
     * @param repositories remote repositories list
     * @return the dependency and its transitive dependencies, with the files they were resolved to
     */
    public Map<Dependency, Path> resolveDependencies(DependencyCoordinates root, List<RemoteRepository> repositories) {
        return resolveDependencies(root, null, null, repositories);
    }

    /**
     * Resolve transitive dependencies for a dependency. The dependency itself comes first in the result.
     *
     * @param root the dependency to resolve
     * @param dependencies additional dependencies of the root, may be {@code null}
     * @param managedDependencies managed dependencies, may be {@code null}
     * @param repositories remote repositories list
     * @return the dependency and its transitive dependencies, with the files they were resolved to
     */
    public Map<Dependency, Path> resolveDependencies(
            DependencyCoordinates root,
            List<DependencyCoordinates> dependencies,
            List<DependencyCoordinates> managedDependencies,
            List<RemoteRepository> repositories) {
        DependencyResolverRequest.DependencyResolverRequestBuilder builder = DependencyResolverRequest.builder()
                .session(session)
                .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                .root(root)
                .pathScope(PathScope.TEST_RUNTIME)
                .repositories(repositories);
        if (dependencies != null) {
            builder.dependencies(dependencies);
        }
        if (managedDependencies != null) {
            builder.managedDependencies(managedDependencies);
        }
        DependencyResolverResult result =
                session.getService(DependencyResolver.class).resolve(builder.build());

        // the resolver leaves the root out of the flattened dependencies, resolve it by itself
        Map<Dependency, Path> resolved = new LinkedHashMap<>();
        DownloadedArtifact rootArtifact = session.resolveArtifact(root, repositories);
        ResolvedDependency rootDependency = ResolvedDependency.of(
                session,
                rootArtifact,
                root.getType() != null ? root.getType().id() : null,
                root.getScope(),
                rootArtifact.getPath());
        resolved.put(rootDependency, rootArtifact.getPath());
        resolved.putAll(result.getDependencies());
        return resolved;
    }

    /**
     * Prepare a remote repositories list for given descriptions.
     *
     * @param repositories remote repositories descriptions
     * @return a list of remote repositories
     */
    public List<RemoteRepository> remoteRepositories(List<String> repositories) {
        return remoteRepositories(repositories, null);
    }

    /**
     * Prepare a remote repositories list for given descriptions and update policy.
     *
     * @param repositories remote repositories descriptions, {@code id::layout::url}, {@code id::url} or {@code url}
     * @param updatePolicy repository update policy, or {@code null} to use the default
     * @return a list of remote repositories
     */
    public List<RemoteRepository> remoteRepositories(List<String> repositories, String updatePolicy) {
        List<RemoteRepository> projectRepositories = getProjectRepositories();
        if (repositories == null || repositories.isEmpty()) {
            return projectRepositories;
        }

        List<RemoteRepository> result = new ArrayList<>(projectRepositories);
        result.addAll(repositories.stream()
                .map(repository -> prepareRemoteRepository(repository, updatePolicy))
                .collect(Collectors.toList()));
        return result;
    }

    // protected for testing purpose
    protected RemoteRepository prepareRemoteRepository(String repository, String updatePolicy) {
        String[] items = Objects.requireNonNull(repository, "repository must be not null")
                .split("::");
        if (items.length > 3) {
            throw new IllegalArgumentException("Invalid repository: " + repository);
        }
        String id = "temp";
        String type = null;
        String url;
        switch (items.length) {
            case 3:
                id = items[0].trim();
                type = items[1].trim();
                url = items[2].trim();
                break;
            case 2:
                id = items[0].trim();
                url = items[1].trim();
                break;
            case 1:
                url = items[0].trim();
                break;
            default:
                throw new IllegalArgumentException("Invalid repository: " + repository);
        }

        if (type == null || type.isEmpty()) {
            type = "default";
        }

        RepositoryPolicy policy = RepositoryPolicy.newBuilder()
                .enabled("true")
                .updatePolicy(updatePolicy)
                .build();
        return session.createRemoteRepository(Repository.newBuilder()
                .id(id)
                .url(url)
                .layout(type)
                .releases(policy)
                .snapshots(policy)
                .build());
    }

    /**
     * Create an artifact based on configuration from Mojo.
     *
     * @param paramArtifact an artifact configuration
     * @return new artifact
     */
    public Artifact createArtifactFromParams(ParamArtifact paramArtifact) {
        Objects.requireNonNull(paramArtifact);
        if (paramArtifact.getArtifact() != null) {
            return createArtifactFromString(paramArtifact);
        } else {
            return createArtifact(
                    paramArtifact.getGroupId(),
                    paramArtifact.getArtifactId(),
                    paramArtifact.getVersion(),
                    paramArtifact.getClassifier(),
                    paramArtifact.getPackaging());
        }
    }

    private Artifact createArtifactFromString(ParamArtifact paramArtifact) {
        // groupId:artifactId:version[:packaging[:classifier]].
        String artifact = paramArtifact.getArtifact();
        String[] items = artifact.split(":");
        if (items.length < 3 || items.length > 5) {
            throw new IllegalArgumentException("Invalid artifact format: " + artifact
                    + ", expected groupId:artifactId:version[:packaging[:classifier]]");
        }

        return createArtifact(
                items[0],
                items[1],
                items[2],
                items.length > 4 ? items[4] : paramArtifact.getClassifier(),
                items.length > 3 ? items[3] : paramArtifact.getPackaging());
    }

    private Artifact createArtifact(
            String groupId, String artifactId, String version, String classifier, String packaging) {
        Type type = session.getService(TypeRegistry.class).require(packaging != null ? packaging : Type.JAR);
        return session.createArtifact(groupId, artifactId, version, classifier, type.getExtension(), type.id());
    }

    /**
     * Retrieve all plugins used in a project either in the build, plugin management, or reporting section.
     *
     * @param project a project
     * @return a collection of plugins
     */
    public Collection<Plugin> getProjectPlugins(Project project) {
        Model model = project.getModel();
        Build build = model.getBuild();

        List<Plugin> reportPlugins =
                Optional.ofNullable(model.getReporting())
                        .map(Reporting::getPlugins)
                        .orElse(Collections.emptyList())
                        .stream()
                        .map(p -> toPlugin(p, build))
                        .collect(Collectors.toList());

        List<Plugin> projectPlugins =
                Optional.ofNullable(build).map(PluginContainer::getPlugins).orElse(Collections.emptyList());

        List<Plugin> managementPlugins = Optional.ofNullable(build)
                .map(Build::getPluginManagement)
                .map(PluginContainer::getPlugins)
                .orElse(Collections.emptyList());

        LinkedHashSet<Plugin> result = new LinkedHashSet<>(reportPlugins.size() + projectPlugins.size());
        result.addAll(reportPlugins);
        result.addAll(projectPlugins);
        result.addAll(managementPlugins);
        return result;
    }

    private Plugin toPlugin(ReportPlugin reportPlugin, Build build) {
        // first look in the pluginManagement section
        Plugin plugin = Optional.ofNullable(build)
                .map(Build::getPluginManagement)
                .map(PluginContainer::getPluginsAsMap)
                .orElseGet(Collections::emptyMap)
                .get(reportPlugin.getKey());

        if (plugin == null) {
            plugin = Optional.ofNullable(build)
                    .map(PluginContainer::getPluginsAsMap)
                    .orElseGet(Collections::emptyMap)
                    .get(reportPlugin.getKey());
        }

        if (plugin == null) {
            plugin = Plugin.newBuilder()
                    .groupId(reportPlugin.getGroupId())
                    .artifactId(reportPlugin.getArtifactId())
                    .version(reportPlugin.getVersion())
                    .build();
        } else if (reportPlugin.getVersion() != null) {
            // override the version with the one from the report plugin if specified
            plugin = plugin.withVersion(reportPlugin.getVersion());
        }

        if (plugin.getVersion() == null) {
            plugin = plugin.withVersion("RELEASE");
        }

        return plugin;
    }

    private Artifact toArtifact(Plugin plugin) {
        return session.createArtifact(
                plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion(), "", "jar", Type.MAVEN_PLUGIN);
    }

    /**
     * Resolve given plugin artifact.
     *
     * @param plugin a plugin to resolve
     * @return resolved artifact
     */
    public DownloadedArtifact resolvePlugin(Plugin plugin) {
        return resolveArtifact(
                toArtifact(plugin), session.getService(ProjectManager.class).getRemotePluginRepositories(project));
    }

    /**
     * Resolve transitive dependencies for plugin. The Maven 4 API cannot carry the scope or the exclusions of the
     * dependencies of a plugin to the resolver, so the dependencies are resolved as compile dependencies.
     *
     * @param plugin a plugin to resolve
     * @param dependencyFilter a filter to apply to plugin dependencies
     * @return the plugin and its transitive dependencies, with the files they were resolved to
     */
    public Map<Dependency, Path> resolveDependencies(
            Plugin plugin, Predicate<org.apache.maven.api.model.Dependency> dependencyFilter) {
        List<DependencyCoordinates> pluginDependencies = plugin.getDependencies().stream()
                .filter(dependencyFilter)
                .map(d -> session.createDependencyCoordinates(session.createArtifactCoordinates(
                        d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getClassifier(), null, d.getType())))
                .collect(Collectors.toList());

        return resolveDependencies(
                session.createDependencyCoordinates(toArtifact(plugin).toCoordinates()),
                pluginDependencies,
                null,
                getProjectRepositories());
    }

    /**
     * Formats an artifact the way the Resolver did in {@code Artifact#toString()}:
     * {@code groupId:artifactId:extension[:classifier]:version}.
     *
     * @param artifact the artifact
     * @return the string form
     */
    public static String toString(Artifact artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append(artifact.getGroupId()).append(':').append(artifact.getArtifactId());
        sb.append(':').append(artifact.getExtension());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            sb.append(':').append(artifact.getClassifier());
        }
        sb.append(':').append(artifact.getVersion());
        return sb.toString();
    }
}
