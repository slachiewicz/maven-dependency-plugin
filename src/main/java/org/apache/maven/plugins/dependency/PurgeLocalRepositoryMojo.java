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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;
import org.apache.maven.shared.artifact.filter.resolve.AbstractFilter;
import org.apache.maven.shared.artifact.filter.resolve.AndFilter;
import org.apache.maven.shared.artifact.filter.resolve.PatternExclusionsFilter;
import org.apache.maven.shared.artifact.filter.resolve.PatternInclusionsFilter;
import org.apache.maven.shared.artifact.filter.resolve.ScopeFilter;
import org.apache.maven.shared.artifact.filter.resolve.TransformableFilter;
import org.apache.maven.shared.artifact.filter.resolve.transform.ArtifactIncludeFilterTransformer;
import org.codehaus.plexus.util.FileUtils;

/**
 * When run on a project, remove the project dependencies from the local repository, and optionally re-resolve them.
 * Outside of a project, remove the manually given dependencies.
 *
 * @author jdcasey
 * @since 2.0
 */
@Mojo(name = "purge-local-repository", projectRequired = false)
public class PurgeLocalRepositoryMojo implements org.apache.maven.api.plugin.Mojo {

    private static final String VERSION_FUZZINESS = "version";

    private static final String ARTIFACT_ID_FUZZINESS = "artifactId";

    private static final String GROUP_ID_FUZZINESS = "groupId";

    /**
     * The current Maven project, {@code null} outside of a project.
     */
    @Inject
    @Nullable
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * This mojo execution, used to determine if it was launched from the lifecycle or the command-line. The
     * Maven 4 API does not tell where an execution comes from, so an execution with the id {@code default-cli}
     * is taken as launched from the command-line.
     */
    @Inject
    private MojoExecution mojoExecution;

    /**
     * The list of dependencies in the form of groupId:artifactId which should BE deleted/purged from the local
     * repository. Note that using this parameter will deactivate the normal process for purging the current project
     * dependency tree. If this parameter is used, only the included artifacts will be purged. The manualIncludes
     * parameter should not be used in combination with the includes/excludes parameters.
     *
     * @since 2.6
     */
    @Parameter
    private List<String> manualIncludes;

    /**
     * Comma-separated list of groupId:artifactId entries, which should be used to manually include artifacts for
     * deletion. This is a command-line alternative to the <code>manualIncludes</code> parameter, since List parameters
     * are not currently compatible with CLI specification.
     *
     * @since 2.6
     */
    @Parameter(property = "manualInclude")
    private String manualInclude;

    /**
     * The list of dependencies in the form of groupId:artifactId which should BE deleted/refreshed.
     *
     * @since 2.6
     */
    @Parameter
    private List<String> includes;

    /**
     * Comma-separated list of groupId:artifactId entries, which should be used to include artifacts for
     * deletion/refresh. This is a command-line alternative to the <code>includes</code> parameter, since List
     * parameters are not currently compatible with CLI specification.
     *
     * @since 2.6
     */
    @Parameter(property = "include")
    private String include;

    /**
     * The list of dependencies in the form of groupId:artifactId which should NOT be deleted/refreshed.
     */
    @Parameter
    private List<String> excludes;

    /**
     * Comma-separated list of groupId:artifactId entries, which should be used to exclude artifacts from
     * deletion/refresh. This is a command-line alternative to the <code>excludes</code> parameter, since List
     * parameters are not currently compatible with CLI specification.
     */
    @Parameter(property = "exclude")
    private String exclude;

    /**
     * Whether to re-resolve the artifacts once they have been deleted from the local repository. If you are running
     * this mojo from the command-line, you may want to disable this. By default, artifacts will be re-resolved.
     */
    @Parameter(property = "reResolve", defaultValue = "true")
    private boolean reResolve;

    /**
     * Determines how liberally the plugin will delete an artifact from the local repository. Values are: <br/>
     * <ul>
     * <li><b>file</b> - Eliminate only the artifact's file.</li>
     * <li><b>version</b> <i>(default)</i> - Eliminate all files associated with the version of the artifact.</li>
     * <li><b>artifactId</b> - Eliminate all files associated with the artifact's artifactId.</li>
     * <li><b>groupId</b> - Eliminate all files associated with the artifact's groupId.</li>
     * </ul>
     */
    @Parameter(property = "resolutionFuzziness", defaultValue = "version")
    private String resolutionFuzziness;

    /**
     * Whether this mojo should act on all transitive dependencies. Default value is true.
     */
    @Parameter(property = "actTransitively", defaultValue = "true")
    private boolean actTransitively;

    /**
     * Whether this plugin should output verbose messages. Default is false.
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * Whether to purge only snapshot artifacts.
     *
     * @since 2.4
     */
    @Parameter(property = "snapshotsOnly", defaultValue = "false")
    private boolean snapshotsOnly;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    private class DirectDependencyFilter extends AbstractFilter {
        private final Project projectArtifact;

        private final List<org.apache.maven.api.DependencyCoordinates> directDependencies;

        DirectDependencyFilter(
                Project projectArtifact, List<org.apache.maven.api.DependencyCoordinates> directDependencies) {
            this.projectArtifact = projectArtifact;
            this.directDependencies = directDependencies;
        }

        @Override
        public boolean accept(
                org.apache.maven.shared.artifact.filter.resolve.Node node,
                List<org.apache.maven.shared.artifact.filter.resolve.Node> parents) {

            if (artifactsGAMatch(node, projectArtifact.getGroupId(), projectArtifact.getArtifactId())) {
                return true;
            }
            for (org.apache.maven.api.DependencyCoordinates dep : directDependencies) {
                if (this.artifactsGAMatch(node, dep.getGroupId(), dep.getArtifactId())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Verify if the given node matches the given group id and artifact id.
         */
        private boolean artifactsGAMatch(
                org.apache.maven.shared.artifact.filter.resolve.Node node, String groupId, String artifactId) {
            if (node.getDependency() == null) {
                return false;
            }

            if (!node.getDependency().getGroupId().equals(groupId)) {
                log.debug("Different groupId: " + node.getDependency() + "  " + groupId);
                return false;
            }
            if (!node.getDependency().getArtifactId().equals(artifactId)) {
                log.debug("Different artifactId: " + node.getDependency() + "  " + artifactId);
                return false;
            }
            return true;
        }
    }

    private class SnapshotsFilter extends AbstractFilter {
        @Override
        public boolean accept(
                org.apache.maven.shared.artifact.filter.resolve.Node node,
                List<org.apache.maven.shared.artifact.filter.resolve.Node> parents) {
            if (node.getDependency() == null) {
                return false;
            } else {
                return session.isVersionSnapshot(node.getDependency().getVersion());
            }
        }
    }

    @Override
    public void execute() throws MojoException {
        if (isSkip()) {
            log.info("Skipping plugin execution");
            return;
        }

        if (!(manualInclude == null || manualInclude.isEmpty())) {
            manualIncludes = this.parseIncludes(manualInclude);
        }
        // If it's a manual purge, the only step is to delete from the local repo
        if (manualIncludes != null && !manualIncludes.isEmpty()) {
            manualPurge(manualIncludes);
            return;
        }

        if (project == null) {
            throw new MojoException("A project is required unless manualInclude(s) are given");
        }

        Set<Dependency> purgedArtifacts = new HashSet<>();
        if (shouldPurgeAllProjectsInReactor()) {
            for (Project reactorProject : session.getProjects()) {
                purgeLocalRepository(reactorProject, purgedArtifacts);
            }
        } else {
            purgeLocalRepository(project, purgedArtifacts);
        }
    }

    /**
     * Determines if all projects in the reactor should be purged from their dependencies. When this goal is started on
     * the command-line, it is always the case. When it is bound to a phase in the lifecycle, it is never the case.
     *
     * @return <code>true</code> if all projects in the reactor should be purged, <code>false</code> otherwise
     */
    private boolean shouldPurgeAllProjectsInReactor() {
        boolean fromCli = "default-cli".equals(mojoExecution.getExecutionId());
        return session.getProjects().size() > 1 && fromCli;
    }

    /**
     * Purges the local repository for the dependencies in the given Maven project.
     *
     * @param theProject maven project
     * @param purgedArtifacts the artifacts that were already purged
     * @throws MojoException in case of errors during the purge
     */
    private void purgeLocalRepository(Project theProject, Set<Dependency> purgedArtifacts) throws MojoException {
        List<org.apache.maven.api.DependencyCoordinates> dependencies = theProject.getDependencies();

        TransformableFilter dependencyFilter = createPurgeArtifactsFilter(theProject, dependencies, purgedArtifacts);

        Set<Dependency> resolvedArtifactsToPurge =
                getFilteredResolvedArtifacts(theProject, dependencies, dependencyFilter);

        if (resolvedArtifactsToPurge.isEmpty()) {
            log.info("No artifacts included for purge for project: " + getProjectKey(theProject));
            return;
        }

        purgeArtifacts(theProject, resolvedArtifactsToPurge);
        purgedArtifacts.addAll(resolvedArtifactsToPurge);

        if (reResolve) {
            log.info("Re-resolving dependencies");
            try {
                reResolveArtifacts(theProject, resolvedArtifactsToPurge);
            } catch (RuntimeException e) {
                String failureMessage = "Failed to refresh project dependencies for: " + theProject.getId();
                throw new MojoException(failureMessage, e);
            }
        }
    }

    /**
     * Purge/Delete artifacts from the local repository according to the given patterns.
     *
     * @param theIncludes the includes
     * @throws MojoException in case of an error
     */
    private void manualPurge(List<String> theIncludes) throws MojoException {
        MessageBuilder messageBuilder =
                session.getService(MessageBuilderFactory.class).builder();

        log.info(messageBuilder
                .a("Deleting ")
                .strong(theIncludes.size())
                .a(" manual ")
                .a(theIncludes.size() != 1 ? "dependencies" : "dependency")
                .a(" from ")
                .strong(localRepositoryDirectory())
                .build());

        for (String gavPattern : theIncludes) {
            if (gavPattern == null || gavPattern.isEmpty()) {
                log.debug("Skipping empty gav pattern");
                continue;
            }

            String relativePath = gavToPath(gavPattern);
            if (relativePath == null || relativePath.isEmpty()) {
                log.debug("Skipping empty relative path for gav pattern: " + gavPattern);
                continue;
            }

            File purgeDir = new File(localRepositoryDirectory(), relativePath);
            if (purgeDir.exists()) {
                log.debug("Deleting directory: " + purgeDir);
                try {
                    FileUtils.deleteDirectory(purgeDir);
                } catch (IOException e) {
                    throw new MojoException("Unable to purge directory: " + purgeDir);
                }
            } else {
                log.debug("Directory: " + purgeDir + " doesn't exist");
            }
        }
    }

    /**
     * Convert a groupId:artifactId:version to a file system path.
     *
     * @param gav the groupId:artifactId:version string
     * @return the corresponding path
     */
    private String gavToPath(String gav) {
        if (gav == null || gav.isEmpty()) {
            return null;
        }

        String[] pathComponents = gav.split(":");

        StringBuilder path = new StringBuilder(pathComponents[0].replace('.', '/'));

        for (int i = 1; i < pathComponents.length; ++i) {
            path.append("/").append(pathComponents[i]);
        }

        return path.toString();
    }

    /**
     * Create the includes exclude filter to use when resolving and purging dependencies Also excludes any "system"
     * scope dependencies.
     *
     * @param theProject the Maven project
     * @param dependencies the dependencies to use as a reference if we're excluding transitive dependencies
     * @param purgedArtifacts the artifacts already purged
     * @return the created filter
     */
    private TransformableFilter createPurgeArtifactsFilter(
            Project theProject,
            List<org.apache.maven.api.DependencyCoordinates> dependencies,
            Set<Dependency> purgedArtifacts) {
        List<TransformableFilter> subFilters = new ArrayList<>();

        // System dependencies should never be purged
        subFilters.add(ScopeFilter.excluding(DependencyScope.SYSTEM.id()));

        if (this.snapshotsOnly) {
            subFilters.add(new SnapshotsFilter());
        }

        // The CLI includes/excludes overrides configuration in the pom
        if (!(this.include == null || this.include.isEmpty())) {
            this.includes = parseIncludes(this.include);
        }
        if (this.includes != null) {
            subFilters.add(new PatternInclusionsFilter(includes));
        }

        if (!(this.exclude == null || this.exclude.isEmpty())) {
            this.excludes = parseIncludes(this.exclude);
        }
        if (this.excludes != null) {
            subFilters.add(new PatternExclusionsFilter(excludes));
        }

        if (!actTransitively) {
            subFilters.add(new DirectDependencyFilter(theProject, dependencies));
        }

        List<String> exclusions = new ArrayList<>(session.getProjects().size());
        // It doesn't make sense to include projects from the reactor here since they're likely not able to be resolved
        for (Project reactorProject : session.getProjects()) {
            exclusions.add(toPatternExcludes(reactorProject));
        }
        // There is no need to consider a second time artifacts that were already purged (re-resolved or not)
        for (Dependency purgedArtifact : purgedArtifacts) {
            exclusions.add(toPatternExcludes(purgedArtifact));
        }
        subFilters.add(new PatternExclusionsFilter(exclusions));

        return new AndFilter(subFilters);
    }

    private static String toString(org.apache.maven.api.DependencyCoordinates coordinate) {
        StringBuilder sb = new StringBuilder();
        sb.append(coordinate.getGroupId()).append(':').append(coordinate.getArtifactId());
        sb.append(':').append(coordinate.getType().getExtension());
        if (coordinate.getClassifier() != null && !coordinate.getClassifier().isEmpty()) {
            sb.append(':').append(coordinate.getClassifier());
        }
        sb.append(':').append(coordinate.getVersionConstraint());
        return sb.toString();
    }

    private String toPatternExcludes(Project theProject) {
        return theProject.getGroupId() + ":" + theProject.getArtifactId() + ":"
                + theProject.getPackaging().type().getExtension() + ":" + theProject.getVersion();
    }

    private String toPatternExcludes(Dependency artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getExtension() + ":"
                + artifact.getVersion();
    }

    /**
     * Convert comma separated list of includes to List object.
     *
     * @param theInclude the list of includes
     * @return the includes list
     */
    private List<String> parseIncludes(String theInclude) {
        List<String> theIncludes = new ArrayList<>();

        if (theInclude != null) {
            String[] elements = theInclude.split(",");
            theIncludes.addAll(Arrays.asList(elements));
        }

        return theIncludes;
    }

    /**
     * Collects the dependencies of the project, and keeps the ones the filter accepts. The dependencies are only
     * collected, not downloaded: what has to be deleted is found by its coordinates.
     *
     * @param theProject the project
     * @param dependencies the direct dependencies of the project
     * @param filter the filter
     * @return the dependencies to purge
     */
    private Set<Dependency> getFilteredResolvedArtifacts(
            Project theProject,
            List<org.apache.maven.api.DependencyCoordinates> dependencies,
            TransformableFilter filter) {
        Predicate<Dependency> artifactFilter = filter.transform(new ArtifactIncludeFilterTransformer());

        List<RemoteRepository> repositories =
                session.getService(ProjectManager.class).getRemoteProjectRepositories(theProject);
        try {
            Set<Dependency> accepted =
                    dependencyResolver.flatten(session, theProject, PathScope.MAIN_RUNTIME).getNodes().stream()
                            .map(Node::getDependency)
                            .filter(Objects::nonNull)
                            .filter(artifactFilter)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            // like Maven 3 did, resolve the files: an artifact that is missing is a reason to fall back
            session.resolveArtifacts(
                    accepted.stream().map(Dependency::toCoordinates).collect(Collectors.toList()), repositories);
            return accepted;
        } catch (RuntimeException e) {
            log.debug("Unable to resolve all dependencies for: " + getProjectKey(theProject), e);
            log.info("Unable to resolve all dependencies for: " + getProjectKey(theProject)
                    + ". Falling back to non-transitive mode for initial artifact resolution.");
        }

        Set<Dependency> resolvedArtifacts = new LinkedHashSet<>();

        for (org.apache.maven.api.DependencyCoordinates coordinate : dependencies) {
            try {
                Dependency dependency = ResolvedDependency.of(
                        session,
                        session.resolveArtifact(coordinate, repositories),
                        coordinate.getType().id(),
                        coordinate.getScope(),
                        null);
                if (artifactFilter.test(dependency)) {
                    resolvedArtifacts.add(dependency);
                }
            } catch (RuntimeException e) {
                log.debug("Unable to resolve artifact: " + toString(coordinate), e);
            }
        }
        return resolvedArtifacts;
    }

    private void purgeArtifacts(Project theProject, Set<Dependency> artifacts) {
        MessageBuilder messageBuilder =
                session.getService(MessageBuilderFactory.class).builder();

        log.info(messageBuilder
                .a("Deleting ")
                .strong(artifacts.size())
                .a(" ")
                .strong(actTransitively ? "transitive" : "direct")
                .a(artifacts.size() != 1 ? " dependencies" : " dependency")
                .a(" for project ")
                .strong(getProjectKey(theProject))
                .a(" from ")
                .strong(localRepositoryDirectory())
                .a(" with artifact ")
                .strong(resolutionFuzziness)
                .a(" resolution fuzziness")
                .build());

        for (Dependency artifact : artifacts) {
            verbose("Purging artifact: " + DependencyUtil.getId(artifact));

            File deleteTarget = findDeleteTarget(artifact);

            verbose("Deleting: " + deleteTarget);

            if (deleteTarget.isDirectory()) {
                try {
                    FileUtils.deleteDirectory(deleteTarget);
                } catch (IOException e) {
                    log.warn("Unable to purge local repository location: " + deleteTarget, e);
                }
            } else {
                if (!deleteTarget.delete()) {
                    deleteTarget.deleteOnExit();
                    log.warn("Unable to purge local repository location immediately: " + deleteTarget);
                }
            }
        }
    }

    private void reResolveArtifacts(Project theProject, Set<Dependency> artifacts) {
        List<Dependency> missingArtifacts = new ArrayList<>();
        List<RemoteRepository> repositories =
                session.getService(ProjectManager.class).getRemoteProjectRepositories(theProject);

        for (Dependency artifact : artifacts) {
            verbose("Resolving artifact: " + DependencyUtil.getId(artifact));
            try {
                new ResolverUtil(session, theProject).resolveArtifact(artifact, repositories);
            } catch (RuntimeException e) {
                verbose(e.getMessage());
                missingArtifacts.add(artifact);
            }
        }

        if (!missingArtifacts.isEmpty()) {
            StringBuilder message = new StringBuilder("required artifacts missing:");
            message.append(System.lineSeparator());
            for (Dependency missingArtifact : missingArtifacts) {
                message.append("  ")
                        .append(DependencyUtil.getId(missingArtifact))
                        .append(System.lineSeparator());
            }
            message.append(System.lineSeparator());
            message.append("for the artifact:");
            message.append(" ").append(getProjectKey(theProject));

            throw new MojoException(message.toString());
        }
    }

    private File localRepositoryDirectory() {
        return session.getLocalRepository().getPath().toFile();
    }

    private File findDeleteTarget(Dependency artifact) {
        // the path of the artifact in the local repository, whether it has been resolved or not
        File deleteTarget = session.getPathForLocalArtifact(artifact).toFile();

        if (GROUP_ID_FUZZINESS.equals(resolutionFuzziness)) {
            // get the groupId dir.
            deleteTarget = deleteTarget.getParentFile().getParentFile().getParentFile();
        } else if (ARTIFACT_ID_FUZZINESS.equals(resolutionFuzziness)) {
            // get the artifactId dir.
            deleteTarget = deleteTarget.getParentFile().getParentFile();
        } else if (VERSION_FUZZINESS.equals(resolutionFuzziness)) {
            // get the version dir.
            deleteTarget = deleteTarget.getParentFile();
        }
        // else it's file fuzziness.
        return deleteTarget;
    }

    private void verbose(String message) {
        if (verbose || log.isDebugEnabled()) {
            log.info(message);
        }
    }

    private String getProjectKey(Project project) {
        return project.getArtifactId();
    }

    /**
     * @return {@link #skip}
     */
    public boolean isSkip() {
        return skip;
    }

    /**
     * @param skip {@link #skip}
     */
    public void setSkip(boolean skip) {
        this.skip = skip;
    }
}
