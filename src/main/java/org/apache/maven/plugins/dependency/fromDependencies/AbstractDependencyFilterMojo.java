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
package org.apache.maven.plugins.dependency.fromDependencies;

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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;
import org.apache.maven.plugins.dependency.AbstractDependencyMojo;
import org.apache.maven.plugins.dependency.utils.DependencyStatusSets;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.plugins.dependency.utils.translators.ArtifactTranslator;
import org.apache.maven.plugins.dependency.utils.translators.ClassifierTypeTranslator;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ClassifierFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ProjectTransitivityFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.artifact.filter.collection.TypeFilter;

/**
 * Class that encapsulates the plugin parameters, and contains methods that handle dependency filtering.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @see org.apache.maven.plugins.dependency.AbstractDependencyMojo
 */
public abstract class AbstractDependencyFilterMojo extends AbstractDependencyMojo {

    /**
     * Overwrite release artifacts.
     *
     * @since 1.0
     */
    @Parameter(property = "overWriteReleases", defaultValue = "false")
    protected boolean overWriteReleases;

    /**
     * Overwrite snapshot artifacts.
     *
     * @since 1.0
     */
    @Parameter(property = "overWriteSnapshots", defaultValue = "false")
    protected boolean overWriteSnapshots;

    /**
     * Overwrite artifacts that don't exist or are older than the source.
     *
     * @since 2.0
     */
    @Parameter(property = "overWriteIfNewer", defaultValue = "true")
    protected boolean overWriteIfNewer;

    /**
     * If we should exclude transitive dependencies.
     *
     * @since 2.0
     */
    @Parameter(property = "excludeTransitive", defaultValue = "false")
    protected boolean excludeTransitive;

    /**
     * Comma-separated list of Types to include. Empty String indicates include everything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "includeTypes", defaultValue = "")
    protected String includeTypes;

    /**
     * Comma-separated list of Types to exclude. Empty String indicates don't exclude anything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "excludeTypes", defaultValue = "")
    protected String excludeTypes;

    /**
     * Scope threshold to include. An empty string indicates include all dependencies (default).<br>
     * The scope threshold value being interpreted is the scope as
     * Maven filters for creating a classpath, not as specified in the pom. In summary:
     * <ul>
     * <li><code>runtime</code> include scope gives runtime and compile dependencies,</li>
     * <li><code>compile</code> include scope gives compile, provided, and system dependencies,</li>
     * <li><code>test</code> include scope gives all dependencies (equivalent to default),</li>
     * <li><code>provided</code> include scope just gives provided dependencies,</li>
     * <li><code>system</code> include scope just gives system dependencies.</li>
     * </ul>
     *
     * @since 2.0
     */
    @Parameter(property = "includeScope", defaultValue = "")
    protected String includeScope;

    /**
     * Scope threshold to exclude, if no value is defined for include.
     * An empty string indicates no dependencies (default).  Unlike the other
     * exclusion parameters, this property does not support a comma-delimited
     * list of scope exclusions. Just one scope may be excluded at a time.<br>
     * The scope threshold value being interpreted is the scope as
     * Maven filters for creating a classpath, not as specified in the pom. In summary:
     * <ul>
     * <li><code>runtime</code> exclude scope excludes runtime and compile dependencies,</li>
     * <li><code>compile</code> exclude scope excludes compile, provided, and system dependencies,</li>
     * <li><code>test</code> exclude scope excludes all dependencies, then not really a legitimate option: it will
     * fail, you probably meant to configure includeScope = compile</li>
     * <li><code>provided</code> exclude scope just excludes provided dependencies,</li>
     * <li><code>system</code> exclude scope just excludes system dependencies.</li>
     * </ul>
     *
     * @since 2.0
     */
    @Parameter(property = "excludeScope", defaultValue = "")
    protected String excludeScope;

    /**
     * Comma-separated list of Classifiers to include. Empty string indicates include everything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "includeClassifiers", defaultValue = "")
    protected String includeClassifiers;

    /**
     * Comma-separated list of Classifiers to exclude. Empty String indicates don't exclude anything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "excludeClassifiers", defaultValue = "")
    protected String excludeClassifiers;

    /**
     * Specify classifier to look for. Example: sources.
     *
     * @since 2.0
     */
    @Parameter(property = "classifier", defaultValue = "")
    protected String classifier;

    /**
     * Specify type to look for when constructing artifact based on classifier. Example: java-source,jar,war.
     *
     * @since 2.0
     */
    @Parameter(property = "type", defaultValue = "")
    protected String type;

    /**
     * Comma-separated list of artifact IDs to exclude.
     *
     * @since 2.0
     */
    @Parameter(property = "excludeArtifactIds", defaultValue = "")
    protected String excludeArtifactIds;

    /**
     * Comma-separated list of artifact IDs to include. Empty String indicates include everything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "includeArtifactIds", defaultValue = "")
    protected String includeArtifactIds;

    /**
     * Comma-separated list of group IDs to exclude.
     *
     * @since 2.0
     */
    @Parameter(property = "excludeGroupIds", defaultValue = "")
    protected String excludeGroupIds;

    /**
     * Comma-separated list of group IDs to include. Empty string indicates include everything (default).
     *
     * @since 2.0
     */
    @Parameter(property = "includeGroupIds", defaultValue = "")
    protected String includeGroupIds;

    /**
     * Directory to store flag files.
     *
     * @since 2.0
     */
    @Parameter(
            property = "markersDirectory",
            defaultValue = "${project.build.directory}/dependency-maven-plugin-markers")
    protected File markersDirectory;

    /**
     * Prepend the group ID during copy.
     *
     * @since 2.2
     */
    @Parameter(property = "mdep.prependGroupId", defaultValue = "false")
    protected boolean prependGroupId = false;

    /**
     * By default, this goal uses the project itself as the root of the dependency tree.
     * With graphRoots, you can select a subtree of dependencies based on groupId and artifactId.
     * After that, the general include/exclude filters can be applied.
     *
     * @since 3.10.0
     */
    @Parameter
    private List<GraphRoot> graphRoots;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * Return an {@link ArtifactsFilter} indicating which artifacts must be filtered out.
     *
     * @return an {@link ArtifactsFilter} indicating which artifacts must be filtered out
     */
    protected abstract ArtifactsFilter getMarkedArtifactFilter();

    /**
     * Retrieves dependencies, either direct only or all including transitive.
     *
     * @param stopOnFailure true to fail if resolution does not work or false not to fail
     * @return a set of artifacts
     * @throws MojoException in case of errors
     */
    protected Set<Dependency> getResolvedDependencies(boolean stopOnFailure) throws MojoException {

        DependencyStatusSets status = getDependencySets(stopOnFailure);

        return status.getResolvedDependencies();
    }

    /**
     * @param stopOnFailure true/false
     * @return {@link DependencyStatusSets}
     * @throws MojoException in case of an error
     */
    protected DependencyStatusSets getDependencySets(boolean stopOnFailure) throws MojoException {
        return getDependencySets(stopOnFailure, false);
    }

    /**
     * Method creates filters and filters the projects dependencies. This method also transforms the dependencies if
     * classifier is set. The dependencies are filtered in least specific to most specific order.
     *
     * @param stopOnFailure true to fail if artifacts can't be resolved false otherwise
     * @param includeParents <code>true</code> if parents should be included or not <code>false</code>
     * @return DependencyStatusSets - Bean of TreeSets that contains information on the projects dependencies
     * @throws MojoException in case of errors
     */
    protected DependencyStatusSets getDependencySets(boolean stopOnFailure, boolean includeParents)
            throws MojoException {

        if ("test".equals(this.excludeScope)) {
            throw new MojoException("Excluding every artifact inside 'test' resolution scope means "
                    + "excluding everything: you probably want includeScope='compile', "
                    + "read parameters documentation for detailed explanations");
        }

        // start with all artifacts.
        Set<Dependency> artifacts;
        Set<Dependency> directDependencies = new LinkedHashSet<>();

        try {
            artifacts = collectArtifacts(getProject(), directDependencies);
        } catch (RuntimeException e) {
            throw new MojoException("Failed to collect artifacts", e);
        }

        // add filters in well known order, least specific to most specific
        FilterArtifacts filter = new FilterArtifacts();

        filter.addFilter(new ProjectTransitivityFilter(directDependencies, this.excludeTransitive));

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

        // A POM can be both a dependency to translate and a parent to copy unchanged.
        Set<Dependency> parentArtifacts = new LinkedHashSet<>();
        if (includeParents) {
            // add dependencies parents
            for (Dependency dep : artifacts) {
                addParentArtifacts(parentOfArtifact(dep), parentArtifacts);
            }

            // add current project parent
            addParentArtifacts(getProject().getModel().getParent(), parentArtifacts);
        }

        // perform filtering
        try {
            artifacts = filter.filter(artifacts);
            parentArtifacts = filter.filter(parentArtifacts);
        } catch (ArtifactFilterException e) {
            throw new MojoException(e.getMessage(), e);
        }

        // transform artifacts if classifier is set
        DependencyStatusSets status;
        if (classifier != null && !classifier.isEmpty()) {
            status = getClassifierTranslatedDependencies(artifacts, stopOnFailure);
            DependencyStatusSets parentStatus = filterMarkedDependencies(parentArtifacts);
            status.getResolvedDependencies().addAll(parentStatus.getResolvedDependencies());
            status.getSkippedDependencies().addAll(parentStatus.getSkippedDependencies());
        } else {
            artifacts.addAll(parentArtifacts);
            status = filterMarkedDependencies(artifacts);
        }

        return status;
    }

    private Parent readParent(Path pom) {
        try {
            return session.getService(ModelXmlFactory.class)
                    .read(XmlReaderRequest.builder().path(pom).strict(false).build())
                    .getParent();
        } catch (RuntimeException e) {
            throw new MojoException("Could not read the parent of " + pom, e);
        }
    }

    /**
     * Adds the parents of a POM, which {@code Project#getParent()} cannot walk for a POM that does not belong to the
     * reactor: the chain is followed by reading the {@code parent} element of each POM that is resolved.
     */
    private void addParentArtifacts(Parent parent, Set<Dependency> artifacts) throws MojoException {
        List<RemoteRepository> repositories = getResolverUtil().getProjectRepositories();
        Set<String> seen = new HashSet<>();
        while (parent != null) {
            Artifact parentPom = session.createArtifact(
                    parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), "", "pom", "pom");
            if (!seen.add(parentPom.key())) {
                break;
            }

            ResolvedDependency probe = ResolvedDependency.of(session, parentPom, "pom", null, null);
            if (artifacts.contains(probe)) {
                // artifact already in the set
                break;
            }
            try {
                DownloadedArtifact resolvedArtifact = getResolverUtil().resolveArtifact(parentPom, repositories);

                artifacts.add(
                        ResolvedDependency.of(session, resolvedArtifact, "pom", null, resolvedArtifact.getPath()));
                parent = readParent(resolvedArtifact.getPath());
            } catch (RuntimeException e) {
                throw new MojoException(e.getMessage(), e);
            }
        }
    }

    private Parent parentOfArtifact(Dependency dependency) throws MojoException {
        try {
            Artifact pom = session.createArtifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion().toString(),
                    "",
                    "pom",
                    "pom");
            DownloadedArtifact resolvedPom =
                    getResolverUtil().resolveArtifact(pom, getResolverUtil().getProjectRepositories());
            return readParent(resolvedPom.getPath());
        } catch (RuntimeException e) {
            throw new MojoException("Could not build project for " + DependencyUtil.getId(dependency), e);
        }
    }

    /**
     * Transform artifacts.
     *
     * @param artifacts set of artifacts {@link Dependency}
     * @param stopOnFailure true/false
     * @return DependencyStatusSets - Bean of TreeSets that contains information on the projects dependencies
     * @throws MojoException in case of an error
     */
    private DependencyStatusSets getClassifierTranslatedDependencies(Set<Dependency> artifacts, boolean stopOnFailure)
            throws MojoException {
        Set<Dependency> unResolvedArtifacts = new LinkedHashSet<>();
        Set<Dependency> resolvedArtifacts = artifacts;
        DependencyStatusSets status = new DependencyStatusSets();

        // possibly translate artifacts into a new set of artifacts based on the
        // classifier and type
        // if this did something, we need to resolve the new artifacts
        if (classifier != null && !classifier.isEmpty()) {
            ArtifactTranslator translator = new ClassifierTypeTranslator(session, this.classifier, this.type);
            Collection<Artifact> coordinates = translator.translate(artifacts, getLog());

            status = filterMarkedDependencies(artifacts);

            // the unskipped artifacts are in the resolved set.
            artifacts = status.getResolvedDependencies();

            // resolve the rest of the artifacts
            resolvedArtifacts = resolve(new LinkedHashSet<>(coordinates), stopOnFailure);

            // calculate the artifacts not resolved.
            unResolvedArtifacts.addAll(artifacts);
            unResolvedArtifacts.removeAll(resolvedArtifacts);
        }

        // return a bean of all 3 sets.
        status.setResolvedDependencies(resolvedArtifacts);
        status.setUnResolvedDependencies(unResolvedArtifacts);

        return status;
    }

    /**
     * Filter the marked dependencies.
     *
     * @param artifacts the artifacts set {@link Dependency}
     * @return status set {@link DependencyStatusSets}
     * @throws MojoException in case of an error
     */
    protected DependencyStatusSets filterMarkedDependencies(Set<Dependency> artifacts) throws MojoException {
        // remove files that have markers already
        FilterArtifacts filter = new FilterArtifacts();
        filter.clearFilters();
        filter.addFilter(getMarkedArtifactFilter());

        Set<Dependency> unMarkedArtifacts;
        try {
            unMarkedArtifacts = filter.filter(artifacts);
        } catch (ArtifactFilterException e) {
            throw new MojoException(e.getMessage(), e);
        }

        // calculate the skipped artifacts
        Set<Dependency> skippedArtifacts = new LinkedHashSet<>(artifacts);
        skippedArtifacts.removeAll(unMarkedArtifacts);

        return new DependencyStatusSets(unMarkedArtifacts, null, skippedArtifacts);
    }

    /**
     * @param artifacts the set of artifacts
     * @param stopOnFailure <code>true</code> if we should fail with exception if an artifact couldn't be resolved
     *            <code>false</code> otherwise
     * @return the resolved artifacts. {@link Dependency}.
     * @throws MojoException in case of error
     */
    private Set<Dependency> resolve(Set<Artifact> artifacts, boolean stopOnFailure) throws MojoException {

        Set<Dependency> resolvedArtifacts = new LinkedHashSet<>();
        List<RemoteRepository> repositories = getResolverUtil().getProjectRepositories();
        for (Artifact artifact : artifacts) {
            try {
                DownloadedArtifact resolveArtifact = getResolverUtil().resolveArtifact(artifact, repositories);
                resolvedArtifacts.add(ResolvedDependency.of(
                        session, resolveArtifact, artifact.getExtension(), null, resolveArtifact.getPath()));
            } catch (RuntimeException ex) {
                // an error occurred during resolution, log it and continue
                getLog().debug("error resolving: " + artifact, ex);
                if (stopOnFailure) {
                    throw new MojoException("error resolving: " + artifact, ex);
                }
            }
        }
        return resolvedArtifacts;
    }

    /**
     * @return {@code true} if the dependencies are only collected, without resolving (downloading) their files
     */
    protected boolean isCollectOnly() {
        return false;
    }

    private Set<Dependency> collectArtifacts(Project project, Set<Dependency> directDependencies) {
        if (isCollectOnly() && (graphRoots == null || graphRoots.isEmpty())) {
            DependencyResolverResult result = dependencyResolver.flatten(session, project, PathScope.TEST_RUNTIME);
            for (Node child : result.getRoot().getChildren()) {
                if (child.getDependency() != null) {
                    directDependencies.add(child.getDependency());
                }
            }
            Map<String, Path> flattened = new LinkedHashMap<>();
            for (Node node : result.getNodes()) {
                if (node.getDependency() != null) {
                    flattened.put(node.getDependency().key(), null);
                }
            }
            return inPreorder(result.getRoot(), flattened, dependency -> null);
        }
        if (graphRoots == null || graphRoots.isEmpty()) {
            // resolve the project dependencies for all scopes
            DependencyResolverResult result = dependencyResolver.resolve(session, project, PathScope.TEST_RUNTIME);
            for (Node child : result.getRoot().getChildren()) {
                if (child.getDependency() != null) {
                    directDependencies.add(child.getDependency());
                }
            }
            Map<String, Path> resolved = new LinkedHashMap<>();
            for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
                resolved.put(entry.getKey().key(), entry.getValue());
            }
            return inPreorder(result.getRoot(), resolved, dependency -> resolved.get(dependency.key()));
        } else {
            // Re-resolve artifacts, but only for the matching graph nodes
            List<DependencyMatcher> filterMatchers =
                    graphRoots.stream().map(GraphRootMatcher::new).collect(Collectors.toList());

            DependencyMatcher subTreeMatcher = new OrDependencyMatcher(filterMatchers);

            // the direct dependencies of the project, whatever the roots are
            for (Node child : dependencyResolver
                    .collect(session, project, PathScope.TEST_RUNTIME)
                    .getRoot()
                    .getChildren()) {
                if (child.getDependency() != null) {
                    directDependencies.add(child.getDependency());
                }
            }

            Set<Dependency> artifacts = new HashSet<>();
            for (DependencyCoordinates dep : project.getDependencies()) {
                if (subTreeMatcher.matches(dep)) {
                    artifacts.addAll(resolveDependencyArtifacts(dep));
                }
            }
            return artifacts;
        }
    }

    /**
     * Orders the dependencies like Maven 3 did for {@code MavenProject#getArtifacts()}: in the pre-order of the
     * dependency graph, while the Maven 4 resolver flattens it level by level.
     */
    private Set<Dependency> inPreorder(Node root, Map<String, Path> keys, Function<Dependency, Path> paths) {
        Set<Dependency> ordered = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        List<Node> stack = new ArrayList<>(root.getChildren());
        Collections.reverse(stack);
        while (!stack.isEmpty()) {
            Node node = stack.remove(stack.size() - 1);
            Dependency dependency = node.getDependency();
            if (dependency != null && keys.containsKey(dependency.key()) && seen.add(dependency.key())) {
                ordered.add(ResolvedDependency.of(session, dependency, paths.apply(dependency)));
            }
            List<Node> children = new ArrayList<>(node.getChildren());
            Collections.reverse(children);
            stack.addAll(children);
        }
        return ordered;
    }

    private Set<Dependency> resolveDependencyArtifacts(DependencyCoordinates root) {
        Map<Dependency, Path> resolved =
                getResolverUtil().resolveDependencies(root, getResolverUtil().getProjectRepositories());
        return resolved.entrySet().stream()
                .map(e -> ResolvedDependency.of(session, e.getKey(), e.getValue()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * @return returns the markersDirectory
     */
    public File getMarkersDirectory() {
        return this.markersDirectory;
    }

    /**
     * @param theMarkersDirectory the markersDirectory to set
     */
    public void setMarkersDirectory(File theMarkersDirectory) {
        this.markersDirectory = theMarkersDirectory;
    }

    // TODO: Set marker files.

    /**
     * @return true, if the groupId should be prepended to the filename
     */
    public boolean isPrependGroupId() {
        return prependGroupId;
    }

    /**
     * @param prependGroupId true if the groupId must be prepended during the copy
     */
    public void setPrependGroupId(boolean prependGroupId) {
        this.prependGroupId = prependGroupId;
    }
}
