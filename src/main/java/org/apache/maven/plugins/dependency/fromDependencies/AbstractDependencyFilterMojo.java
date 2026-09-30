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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;
import org.apache.maven.plugins.dependency.AbstractDependencyMojo;
import org.apache.maven.plugins.dependency.resolvers.ExcludeReactorProjectsArtifactFilter;
import org.apache.maven.plugins.dependency.utils.DependencyStatusSets;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.plugins.dependency.utils.filters.ExcludeOptionalArtifactsFilter;
import org.apache.maven.plugins.dependency.utils.filters.GroupIdFilter;
import org.apache.maven.plugins.dependency.utils.translators.ArtifactTranslator;
import org.apache.maven.plugins.dependency.utils.translators.ClassifierTypeTranslator;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ClassifierFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
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
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

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
     * Whether {@code includeGroupIds}/{@code excludeGroupIds} only match a listed group id exactly, the same way
     * {@code includeArtifactIds}/{@code excludeArtifactIds} match artifact ids. By default, a listed group id such
     * as {@code org.example} also matches every group id nested under it, such as {@code org.example.foo}, but
     * never an unrelated group id that merely shares the same string prefix, such as {@code org.example2}.
     *
     * @since 3.12.0
     */
    @Parameter(property = "groupIdsExactMatch", defaultValue = "false")
    protected boolean groupIdsExactMatch;

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
     * Whether optional artifacts must be excluded. Goals exposing the {@code excludeOptional} parameter override this.
     *
     * @return {@code false} by default
     */
    protected boolean isExcludeOptional() {
        return false;
    }

    /**
     * Whether artifacts that are reactor projects must be excluded. Goals exposing the {@code excludeReactor}
     * parameter override this.
     *
     * @return {@code false} by default
     */
    protected boolean isExcludeReactor() {
        return false;
    }

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
        return getDependencySets(stopOnFailure, includeParents, false);
    }

    /**
     * Method creates filters and filters the projects dependencies. This method also transforms the dependencies if
     * classifier is set. The dependencies are filtered in least specific to most specific order.
     *
     * @param stopOnFailure true to fail if artifacts can't be resolved false otherwise
     * @param includeParents <code>true</code> if parents should be included or not <code>false</code>
     * @param includeImportedBoms <code>true</code> if the import-scoped BOMs of the project and of the parents should
     *            be included or not <code>false</code>
     * @return DependencyStatusSets - Bean of TreeSets that contains information on the projects dependencies
     * @throws MojoException in case of errors
     */
    protected DependencyStatusSets getDependencySets(
            boolean stopOnFailure, boolean includeParents, boolean includeImportedBoms) throws MojoException {

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
                DependencyUtil.cleanToBeTokenizedString(this.excludeGroupIds),
                this.groupIdsExactMatch));

        filter.addFilter(new ArtifactIdFilter(
                DependencyUtil.cleanToBeTokenizedString(this.includeArtifactIds),
                DependencyUtil.cleanToBeTokenizedString(this.excludeArtifactIds)));

        if (isExcludeOptional()) {
            filter.addFilter(new ExcludeOptionalArtifactsFilter());
        }

        if (isExcludeReactor()) {
            filter.addFilter(new ExcludeReactorProjectsArtifactFilter(session.getProjects(), getLog()));
        }

        ArtifactsFilter patternsFilter = getPatternsFilter();
        if (patternsFilter != null) {
            filter.addFilter(patternsFilter);
        }

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
        if (includeImportedBoms) {
            Set<String> visitedBoms = new HashSet<>();
            for (Dependency dep : artifacts) {
                addImportedBomArtifacts(pomPathOf(dep), null, parentArtifacts, visitedBoms);
            }
            addImportedBomArtifacts(getProject().getPomPath(), getProject(), parentArtifacts, visitedBoms);
        }

        // perform filtering
        try {
            artifacts = filter.filter(artifacts);
            parentArtifacts = filter.filter(parentArtifacts);
        } catch (ArtifactFilterException e) {
            throw new MojoException(e.getMessage(), e);
        }

        // with a classifier the originals only seed the translation and only the translated artifacts are resolved
        if (isResolvingAfterFiltering() && (classifier == null || classifier.isEmpty())) {
            artifacts = resolveFilteredArtifacts(artifacts);
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

    /**
     * Resolves the artifacts that are still without a file, and keeps scope and optional flag of the dependency.
     */
    private Set<Dependency> resolveFilteredArtifacts(Set<Dependency> artifacts) throws MojoException {
        List<Dependency> unresolved = new ArrayList<>();
        Map<String, Dependency> resolved = new LinkedHashMap<>();
        for (Dependency artifact : artifacts) {
            if (artifact instanceof DownloadedDependency && ((DownloadedDependency) artifact).getPath() != null) {
                continue;
            }
            if (artifact.getScope() == DependencyScope.SYSTEM) {
                // never a repository artifact: Maven takes the file from the systemPath of the dependency
                resolved.put(artifact.key(), ResolvedDependency.of(session, artifact, getSystemPath(artifact)));
            } else {
                unresolved.add(artifact);
            }
        }
        if (!unresolved.isEmpty()) {
            Collection<DownloadedArtifact> results;
            try {
                results = session.resolveArtifacts(
                        unresolved.stream().map(Dependency::toCoordinates).collect(Collectors.toList()),
                        getResolverUtil().getProjectRepositories());
            } catch (RuntimeException e) {
                throw new MojoException(
                        "Could not resolve dependencies for project "
                                + getProject().getArtifactId() + ": " + e.getMessage(),
                        e);
            }
            Iterator<DownloadedArtifact> it = results.iterator();
            for (Dependency artifact : unresolved) {
                DownloadedArtifact result = it.next();
                // same as Maven when it resolves a project: a snapshot gets its timestamped version
                resolved.put(
                        artifact.key(),
                        ResolvedDependency.of(
                                session, result, artifact.getType().id(), artifact.getScope(), result.getPath()));
            }
        }

        Set<Dependency> result = new LinkedHashSet<>();
        for (Dependency artifact : artifacts) {
            result.add(resolved.getOrDefault(artifact.key(), artifact));
        }
        return result;
    }

    private Path getSystemPath(Dependency artifact) throws MojoException {
        for (org.apache.maven.api.model.Dependency dependency :
                getProject().getModel().getDependencies()) {
            if (artifact.getGroupId().equals(dependency.getGroupId())
                    && artifact.getArtifactId().equals(dependency.getArtifactId())
                    && dependency.getSystemPath() != null) {
                Path path = Paths.get(dependency.getSystemPath());
                if (!Files.isRegularFile(path)) {
                    throw new MojoException(
                            "The systemPath of " + DependencyUtil.getId(artifact) + " does not exist: " + path);
                }
                return path;
            }
        }
        throw new MojoException(
                "No systemPath is declared for the system scoped dependency " + DependencyUtil.getId(artifact));
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

    private Path pomPathOf(Dependency dependency) throws MojoException {
        try {
            Artifact pom = session.createArtifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion().toString(),
                    "",
                    "pom",
                    "pom");
            return getResolverUtil()
                    .resolveArtifact(pom, getResolverUtil().getProjectRepositories())
                    .getPath();
        } catch (RuntimeException e) {
            throw new MojoException("Could not resolve the POM of " + DependencyUtil.getId(dependency), e);
        }
    }

    private Model readModel(Path pom) {
        try {
            return session.getService(ModelXmlFactory.class)
                    .read(XmlReaderRequest.builder().path(pom).strict(false).build());
        } catch (RuntimeException e) {
            throw new MojoException("Could not read " + pom, e);
        }
    }

    /**
     * The original model of a POM followed by the original models of its parents. The effective model no longer
     * lists the import-scoped entries, so they are read from the original models.
     */
    private List<Model> modelChain(Path pom) {
        List<Model> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Model current = readModel(pom);
        while (current != null) {
            chain.add(current);
            Parent parent = current.getParent();
            if (parent == null || !seen.add(parent.getGroupId() + ':' + parent.getArtifactId())) {
                break;
            }
            Artifact parentPom = session.createArtifact(
                    parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), "", "pom", "pom");
            current = readModel(getResolverUtil()
                    .resolveArtifact(parentPom, getResolverUtil().getProjectRepositories())
                    .getPath());
        }
        return chain;
    }

    /**
     * Adds the POMs of the BOMs that a POM and its parents import in their dependencyManagement, together with the
     * parents and imports of those BOMs.
     *
     * @param pom the POM
     * @param project the project the POM belongs to, whose active profiles apply, or <code>null</code>
     */
    private void addImportedBomArtifacts(Path pom, Project project, Set<Dependency> artifacts, Set<String> visited)
            throws MojoException {
        List<Model> chain = modelChain(pom);
        Set<String> activeProfiles = new HashSet<>();
        if (project != null) {
            project.getEffectiveActiveProfiles().forEach(profile -> activeProfiles.add(profile.getId()));
        }
        for (Model current : chain) {
            List<org.apache.maven.api.model.Dependency> managedDependencies = new ArrayList<>();
            if (current.getDependencyManagement() != null) {
                managedDependencies.addAll(current.getDependencyManagement().getDependencies());
            }
            for (Profile profile : current.getProfiles()) {
                if (activeProfiles.contains(profile.getId()) && profile.getDependencyManagement() != null) {
                    managedDependencies.addAll(profile.getDependencyManagement().getDependencies());
                }
            }
            for (org.apache.maven.api.model.Dependency managed : managedDependencies) {
                if (!"import".equals(managed.getScope()) || !"pom".equals(managed.getType())) {
                    continue;
                }
                String groupId = interpolate(managed.getGroupId(), chain, project);
                String artifactId = interpolate(managed.getArtifactId(), chain, project);
                String version = interpolate(managed.getVersion(), chain, project);
                if (groupId.contains("${") || artifactId.contains("${") || version.contains("${")) {
                    getLog().warn("Skipping imported BOM " + managed.getGroupId() + ":" + managed.getArtifactId() + ":"
                            + managed.getVersion() + ", its coordinates cannot be interpolated.");
                    continue;
                }
                if (!visited.add(groupId + ':' + artifactId + ':' + version)) {
                    continue;
                }
                try {
                    DownloadedArtifact bom = getResolverUtil()
                            .resolveArtifact(
                                    session.createArtifact(groupId, artifactId, version, "", "pom", "pom"),
                                    getResolverUtil().getProjectRepositories());
                    artifacts.add(ResolvedDependency.of(session, bom, "pom", null, bom.getPath()));

                    addParentArtifacts(readModel(bom.getPath()).getParent(), artifacts);
                    addImportedBomArtifacts(bom.getPath(), null, artifacts, visited);
                } catch (RuntimeException e) {
                    throw new MojoException(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Returns the value of an environment variable, a seam for tests.
     *
     * @param name name of the environment variable
     * @return the value, or {@code null} if it is not set
     */
    String getEnvironmentVariable(String name) {
        return System.getenv(name);
    }

    String interpolate(String value, List<Model> chain, Project project) {
        if (value == null) {
            return "";
        }
        Model model = chain.get(0);
        Model parent = chain.size() > 1 ? chain.get(1) : null;
        Matcher matcher = EXPRESSION.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            switch (key) {
                case "project.groupId":
                case "pom.groupId":
                    replacement = firstNonNull(model.getGroupId(), parent != null ? parent.getGroupId() : null);
                    break;
                case "project.artifactId":
                case "pom.artifactId":
                    replacement = model.getArtifactId();
                    break;
                case "project.version":
                case "pom.version":
                    replacement = firstNonNull(model.getVersion(), parent != null ? parent.getVersion() : null);
                    break;
                case "project.parent.groupId":
                case "pom.parent.groupId":
                    replacement = model.getParent() != null ? model.getParent().getGroupId() : null;
                    break;
                case "project.parent.artifactId":
                case "pom.parent.artifactId":
                    replacement = model.getParent() != null ? model.getParent().getArtifactId() : null;
                    break;
                case "project.parent.version":
                case "pom.parent.version":
                case "parent.version":
                    replacement = model.getParent() != null ? model.getParent().getVersion() : null;
                    break;
                default:
                    replacement = session.getUserProperties().get(key);
                    for (int i = 0; replacement == null && i < chain.size(); i++) {
                        replacement = chain.get(i).getProperties().get(key);
                    }
                    if (replacement == null && project != null) {
                        replacement = project.getModel().getProperties().get(key);
                    }
                    if (replacement == null) {
                        replacement = session.getSystemProperties().get(key);
                    }
                    if (replacement == null && key.startsWith("env.")) {
                        replacement = getEnvironmentVariable(key.substring("env.".length()));
                    }
            }
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /**
     * Creates the translator that maps the dependencies to the artifacts to resolve when a classifier is set.
     *
     * @return the translator
     */
    protected ArtifactTranslator createArtifactTranslator() {
        return new ClassifierTypeTranslator(session, this.classifier, this.type);
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
            ArtifactTranslator translator = createArtifactTranslator();
            Collection<Artifact> coordinates = translator.translate(artifacts, getLog());

            // resolve the translated artifacts
            resolvedArtifacts = resolve(new LinkedHashSet<>(coordinates), stopOnFailure, unResolvedArtifacts);

            // skip the translated artifacts that are already up to date in the destination: the destination file
            // name depends on the classifier, so the decision cannot be taken on the original artifacts
            status = filterMarkedDependencies(resolvedArtifacts);
            resolvedArtifacts = status.getResolvedDependencies();
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
     * @param unresolved receives the artifacts that could not be resolved
     * @return the resolved artifacts. {@link Dependency}.
     * @throws MojoException in case of error
     */
    private Set<Dependency> resolve(Set<Artifact> artifacts, boolean stopOnFailure, Set<Dependency> unresolved)
            throws MojoException {

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
                unresolved.add(ResolvedDependency.of(session, artifact, artifact.getExtension(), null, null));
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

    /**
     * Return an {@link ArtifactsFilter} applied after the other filters of {@link #getDependencySets}, for goals
     * that match artifacts by {@code [groupId]:[artifactId]:[type]:[version]} patterns.
     *
     * @return the filter, or {@code null} (the default) if the goal has no such patterns
     */
    protected ArtifactsFilter getPatternsFilter() {
        return null;
    }

    /**
     * Tells whether this goal asks Maven for the dependency graph only and resolves the artifacts that are left after
     * filtering itself. This way a dependency that the filters drop, for example a {@code provided} module of the same
     * reactor that has not been built, is never resolved.
     *
     * @return {@code true} if the artifacts are collected first and only the filtered ones are resolved
     */
    protected boolean isResolvingAfterFiltering() {
        return false;
    }

    private Set<Dependency> collectArtifacts(Project project, Set<Dependency> directDependencies) {
        if ((isCollectOnly() || isResolvingAfterFiltering()) && (graphRoots == null || graphRoots.isEmpty())) {
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
