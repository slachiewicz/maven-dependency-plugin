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
package org.apache.maven.plugins.dependency.fromConfiguration;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.api.Artifact;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.AbstractDependencyMojo;
import org.apache.maven.plugins.dependency.utils.ArtifactCoordinates;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.plugins.dependency.utils.filters.ArtifactItemFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;

/**
 * Abstract parent class used by mojos that get Artifact information from the plugin configuration as an ArrayList of
 * ArtifactItems.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @see ArtifactItem
 */
public abstract class AbstractFromConfigurationMojo extends AbstractDependencyMojo {
    /**
     * Default output location used for mojo, unless overridden in ArtifactItem.
     *
     * @since 1.0
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/dependency")
    private File outputDirectory;

    /**
     * Overwrite release artifacts.
     *
     * @since 1.0
     */
    @Parameter(property = "mdep.overWriteReleases", defaultValue = "false")
    private boolean overWriteReleases;

    /**
     * Overwrite snapshot artifacts.
     *
     * @since 1.0
     */
    @Parameter(property = "mdep.overWriteSnapshots", defaultValue = "false")
    private boolean overWriteSnapshots;

    /**
     * Overwrite if newer.
     *
     * @since 2.0
     * @deprecated use 'overWriteIfNewer' or 'mdep.overWriteIfNewer' as this does nothing now
     */
    @Deprecated
    @Parameter(property = "mdep.overIfNewer", defaultValue = "true")
    private boolean overIfNewer;

    /**
     * Overwrite if newer.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.overWriteIfNewer", defaultValue = "true")
    private boolean overWriteIfNewer;

    /**
     * Collection of ArtifactItems to work on. (ArtifactItem contains groupId, artifactId, version, type, classifier,
     * outputDirectory, destFileName, overWrite and encoding.) See <a href="./usage.html">Usage</a> for details.
     *
     * @since 1.0
     */
    @Parameter
    private List<ArtifactItem> artifactItems;

    /**
     * Path to override default local repository during plugin's execution. To remove all downloaded artifacts as part
     * of the build, set this value to a location under your project's target directory.
     *
     * @since 2.2
     */
    @Parameter
    private File localRepositoryDirectory;

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
     * <p>
     * The artifacts are resolved from these repositories in addition to the repositories of the project.
     *
     * @since 3.12.0
     */
    @Parameter(property = "remoteRepositories")
    private List<String> remoteRepositories;

    /**
     * Fail if an item of {@code artifactItems} (or the {@code artifact} configured in the POM) is not declared as a
     * dependency of the project, or is declared with a different version. Maven orders the reactor by the declared
     * dependencies, not by plugin configuration, so an undeclared item that is built by another module of the reactor
     * may be missing or stale. Declare such an item as a dependency with scope {@code provided} or {@code test}.
     * <p>
     * A dependency of type {@code pom} with the same groupId, artifactId and version also satisfies the check for
     * any type and classifier. Such a declaration adds the transitive dependencies of the module to the classpath
     * (with the declared scope, {@code compile} by default) and shows as {@code g:a:pom:v} in
     * {@code dependency:tree}. Use scope {@code provided} or {@code test} and, to avoid the transitive dependencies,
     * an exclusion of {@code *:*}.
     * </p>
     * <p>
     * Items match on groupId, artifactId, extension and classifier, where {@code test-jar} equals {@code jar} with
     * the classifier {@code tests}. An item without a version takes its version from the
     * project, as for the goal itself, and is then checked like any other item. A version
     * range in the declaration matches an item version it contains; a version range in an item matches only an
     * identical declaration, as the goal resolves it to the highest available version.
     * </p>
     * <p>
     * The check does not apply when the goal is invoked directly from the command line, such as
     * {@code mvn dependency:copy}. An execution that is bound to the lifecycle is checked, even when the property is
     * given on the command line.
     * </p>
     *
     * @since 3.12.0
     */
    @Parameter(property = "mdep.requireExplicitDependencies", defaultValue = "false")
    private boolean requireExplicitDependencies;

    @Inject
    private MojoExecution mojoExecution;

    private DependencyVersionResolver dependencyVersionResolver;

    abstract ArtifactItemFilter getMarkedArtifactFilter(ArtifactItem item);

    /**
     * ArtifactItems is filled by either field injection or by setArtifact().
     *
     * @throws MojoException in case of an error
     */
    protected void verifyRequirements() throws MojoException {
        if (artifactItems == null || artifactItems.isEmpty()) {
            throw new MojoException("Either artifact or artifactItems is required ");
        }
    }

    /**
     * Fails if {@link #requireExplicitDependencies} is set and an item is not declared as a dependency. Must run
     * after {@link #verifyRequirements()}. Nothing is resolved for an item whose groupId and artifactId are not
     * declared at all; a missing version of any other item is looked up first.
     *
     * @throws MojoException if an item is not declared, or a missing version cannot be determined
     */
    protected void verifyExplicitDependencies() throws MojoException {
        if (!requireExplicitDependencies) {
            return;
        }
        // the Maven 4 API does not tell where an execution comes from, the id of one that Maven makes up for a
        // goal on the command line is the best there is
        if (mojoExecution != null && "default-cli".equals(mojoExecution.getExecutionId())) {
            getLog().warn("requireExplicitDependencies is ignored when the goal is invoked from the command line");
            return;
        }
        for (ArtifactItem item : artifactItems) {
            if (item.getVersion() == null || item.getVersion().isEmpty()) {
                fillMissingArtifactVersion(item);
            }
        }
        List<String> violations =
                new ExplicitDependencyMatcher(session, getProject().getDependencies()).findViolations(artifactItems);
        if (!violations.isEmpty()) {
            throw new MojoException("requireExplicitDependencies is set, but artifactItems must be "
                    + "declared as dependencies of the project, so that Maven orders the reactor by them:"
                    + System.lineSeparator() + "  " + String.join(System.lineSeparator() + "  ", violations)
                    + System.lineSeparator() + "Declare each of them in the dependencies of the project, "
                    + "for example with scope provided or test, and use the same version.");
        }
    }

    /**
     * Preprocesses the list of ArtifactItems. This method defaults the outputDirectory if not set and creates the
     * output Directory if it doesn't exist.
     *
     * @param processArtifactItemsRequest preprocessing instructions
     * @return an ArrayList of preprocessed ArtifactItems
     * @throws MojoException with a message if an error occurs
     * @see ArtifactItem
     */
    protected List<ArtifactItem> getProcessedArtifactItems(ProcessArtifactItemsRequest processArtifactItemsRequest)
            throws MojoException {

        boolean removeVersion = processArtifactItemsRequest.isRemoveVersion();
        boolean prependGroupId = processArtifactItemsRequest.isPrependGroupId();
        boolean useBaseVersion = processArtifactItemsRequest.isUseBaseVersion();

        boolean removeClassifier = processArtifactItemsRequest.isRemoveClassifier();

        if (artifactItems == null || artifactItems.isEmpty()) {
            throw new MojoException("There are no artifactItems configured.");
        }

        for (ArtifactItem artifactItem : artifactItems) {
            this.getLog().debug("Configured Artifact: " + artifactItem.toString());

            if (artifactItem.getOutputDirectory() == null) {
                artifactItem.setOutputDirectory(this.outputDirectory);
            }
            artifactItem.getOutputDirectory().mkdirs();

            // make sure we have a version.
            if (artifactItem.getVersion() == null || artifactItem.getVersion().isEmpty()) {
                fillMissingArtifactVersion(artifactItem);
            }

            artifactItem.setArtifact(this.getArtifact(artifactItem));

            if (artifactItem.getDestFileName() == null
                    || artifactItem.getDestFileName().isEmpty()) {
                artifactItem.setDestFileName(DependencyUtil.getFormattedFileName(
                        artifactItem.getArtifact(), removeVersion, prependGroupId, useBaseVersion, removeClassifier));
            }

            applyMojoConfiguration(artifactItem);

            try {
                artifactItem.setNeedsProcessing(checkIfProcessingNeeded(artifactItem));
            } catch (ArtifactFilterException e) {
                throw new MojoException(e.getMessage(), e);
            }
        }
        return artifactItems;
    }

    /**
     * Copies mojo level settings that are not configured on the item into the item. Runs before the marker lookup, so
     * the marker file name is computed from the same settings that are used when the marker is written.
     *
     * @param artifactItem the item to complete
     */
    void applyMojoConfiguration(ArtifactItem artifactItem) {
        // nothing to apply by default
    }

    private boolean checkIfProcessingNeeded(ArtifactItem item) throws ArtifactFilterException {
        return "true".equalsIgnoreCase(item.getOverWrite())
                || getMarkedArtifactFilter(item).isArtifactIncluded(item);
    }

    private Session createSessionForLocalRepo() {
        if (localRepositoryDirectory != null) {
            // "clone" the session and replace the local repository
            getLog().debug("localRepoPath: " + localRepositoryDirectory);
            return session.withLocalRepository(session.createLocalRepository(localRepositoryDirectory.toPath()));
        }
        return session;
    }

    /**
     * Resolves the Artifact from the remote repository if necessary. If no version is specified, it will be retrieved
     * from the dependency list or from the DependencyManagement section of the pom.
     *
     * @param artifactItem containing information about artifact from plugin configuration
     * @return artifact object representing the specified file
     * @throws MojoException if the version can't be found in DependencyManagement
     */
    protected ResolvedDependency getArtifact(ArtifactItem artifactItem) throws MojoException {
        try {
            Session repositorySession = createSessionForLocalRepo();
            List<RemoteRepository> repositories = getRemoteRepositories();

            String version = artifactItem.getVersion().trim();
            if (version.startsWith("[") || version.startsWith("(")) {
                version = repositorySession
                        .resolveHighestVersion(
                                repositorySession.createArtifactCoordinates(
                                        artifactItem.getGroupId(),
                                        artifactItem.getArtifactId(),
                                        version,
                                        artifactItem.getClassifier(),
                                        null,
                                        artifactItem.getType()),
                                repositories)
                        .orElseThrow(() -> new MojoException("No version of " + artifactItem.getGroupId() + ":"
                                + artifactItem.getArtifactId() + " matches the range " + artifactItem.getVersion()))
                        .toString();
                getLog().debug("Version range " + artifactItem.getVersion() + " resolved to " + version);
            }

            Artifact artifact = repositorySession.createArtifact(
                    artifactItem.getGroupId(),
                    artifactItem.getArtifactId(),
                    version,
                    artifactItem.getClassifier(),
                    null,
                    artifactItem.getType());

            DownloadedArtifact resolvedArtifact =
                    getResolverUtil().resolveArtifactWithFallback(artifact, repositories, repositorySession);
            return ResolvedDependency.of(
                    session, resolvedArtifact, artifactItem.getType(), null, resolvedArtifact.getPath());
        } catch (MojoException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MojoException("Unable to find/resolve artifact.", e);
        }
    }

    private List<RemoteRepository> getRemoteRepositories() {
        if (remoteRepositories == null || remoteRepositories.isEmpty()) {
            return getResolverUtil().getProjectRepositories();
        }
        return getResolverUtil().remoteRepositories(remoteRepositories);
    }

    /**
     * Tries to find a missing version from the dependency list, the dependency management and finally the dependency
     * graph of the requested {@code dependencyScope}. It will first look for an exact match on
     * artifactId/groupId/classifier/type and if it doesn't find a match, it will try again looking for artifactId and
     * groupId only.
     *
     * @param artifact representing configured file
     * @throws MojoException if no version can be found
     */
    private void fillMissingArtifactVersion(ArtifactItem artifact) throws MojoException {
        if (dependencyVersionResolver == null) {
            dependencyVersionResolver = new DependencyVersionResolver(getProject(), this::createSessionForLocalRepo);
        }
        artifact.setVersion(dependencyVersionResolver.resolveVersion(artifact));
    }

    /**
     * @param requireExplicitDependencies {@link #requireExplicitDependencies}
     * @since 3.12.0
     */
    public void setRequireExplicitDependencies(boolean requireExplicitDependencies) {
        this.requireExplicitDependencies = requireExplicitDependencies;
    }

    /**
     * @param remoteRepositories {@link #remoteRepositories}
     * @since 3.12.0
     */
    public void setRemoteRepositories(List<String> remoteRepositories) {
        this.remoteRepositories = remoteRepositories;
    }

    /**
     * @return returns the artifactItems
     */
    public List<ArtifactItem> getArtifactItems() {
        return this.artifactItems;
    }

    /**
     * @param theArtifactItems the artifactItems to set
     */
    public void setArtifactItems(List<ArtifactItem> theArtifactItems) {
        this.artifactItems = theArtifactItems;
    }

    /**
     * @return returns the outputDirectory
     */
    public File getOutputDirectory() {
        return this.outputDirectory;
    }

    /**
     * @param theOutputDirectory the outputDirectory to set
     */
    public void setOutputDirectory(File theOutputDirectory) {
        this.outputDirectory = theOutputDirectory;
    }

    /**
     * @return returns the overWriteIfNewer
     */
    public boolean isOverWriteIfNewer() {
        return this.overWriteIfNewer;
    }

    /**
     * @param theOverWriteIfNewer the overWriteIfNewer to set
     */
    public void setOverWriteIfNewer(boolean theOverWriteIfNewer) {
        this.overWriteIfNewer = theOverWriteIfNewer;
    }

    /**
     * @return returns the overWriteReleases
     */
    public boolean isOverWriteReleases() {
        return this.overWriteReleases;
    }

    /**
     * @param theOverWriteReleases the overWriteReleases to set
     */
    public void setOverWriteReleases(boolean theOverWriteReleases) {
        this.overWriteReleases = theOverWriteReleases;
    }

    /**
     * @return returns the overWriteSnapshots
     */
    public boolean isOverWriteSnapshots() {
        return this.overWriteSnapshots;
    }

    /**
     * @param theOverWriteSnapshots the overWriteSnapshots to set
     */
    public void setOverWriteSnapshots(boolean theOverWriteSnapshots) {
        this.overWriteSnapshots = theOverWriteSnapshots;
    }

    /**
     * @param localRepositoryDirectory {@link #localRepositoryDirectory}
     */
    public void setLocalRepositoryDirectory(File localRepositoryDirectory) {
        this.localRepositoryDirectory = localRepositoryDirectory;
    }

    /**
     * @param artifact the artifact, in one of the forms described by {@link ArtifactCoordinates}
     * @throws MojoException in case of an error
     */
    public void setArtifact(String artifact) throws MojoException {
        if (artifact != null) {
            // Only the six token form keeps empty tokens, so that an empty classifier can precede a destFileName.
            // Otherwise empty tokens collapse, as before: "g:a:v::jar" has the packaging "jar".
            String[] tokens = StringUtils.splitPreserveAllTokens(artifact, ":");
            if (tokens.length != 6) {
                tokens = StringUtils.split(artifact, ":");
            }
            ArtifactCoordinates coordinates;
            try {
                coordinates = ArtifactCoordinates.parse(tokens, artifact, true);
            } catch (IllegalArgumentException e) {
                throw new MojoException(e.getMessage(), e);
            }
            if (coordinates.getDestFileName() != null
                    && coordinates.getDestFileName().isEmpty()) {
                throw new MojoException("Invalid artifact, the destFileName must not be empty " + artifact);
            }

            ArtifactItem artifactItem = new ArtifactItem();
            artifactItem.setGroupId(coordinates.getGroupId());
            artifactItem.setArtifactId(coordinates.getArtifactId());
            artifactItem.setVersion(coordinates.getVersion());
            artifactItem.setType(
                    coordinates.getPackaging() != null
                                    && !coordinates.getPackaging().isEmpty()
                            ? coordinates.getPackaging()
                            : "jar");
            artifactItem.setClassifier(
                    coordinates.getClassifier() != null
                                    && !coordinates.getClassifier().isEmpty()
                            ? coordinates.getClassifier()
                            : null);
            if (coordinates.getDestFileName() != null
                    && !coordinates.getDestFileName().isEmpty()) {
                artifactItem.setDestFileName(coordinates.getDestFileName());
            }

            setArtifactItems(Collections.singletonList(artifactItem));
        }
    }
}
