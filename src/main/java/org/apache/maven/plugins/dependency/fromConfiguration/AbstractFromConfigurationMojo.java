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
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.AbstractDependencyMojo;
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

            try {
                artifactItem.setNeedsProcessing(checkIfProcessingNeeded(artifactItem));
            } catch (ArtifactFilterException e) {
                throw new MojoException(e.getMessage(), e);
            }
        }
        return artifactItems;
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
            Artifact artifact = repositorySession.createArtifact(
                    artifactItem.getGroupId(),
                    artifactItem.getArtifactId(),
                    artifactItem.getVersion(),
                    artifactItem.getClassifier(),
                    null,
                    artifactItem.getType());

            DownloadedArtifact resolvedArtifact = getResolverUtil()
                    .resolveArtifactWithFallback(
                            artifact, getResolverUtil().getProjectRepositories(), repositorySession);
            return ResolvedDependency.of(
                    session, resolvedArtifact, artifactItem.getType(), null, resolvedArtifact.getPath());
        } catch (RuntimeException e) {
            throw new MojoException("Unable to find/resolve artifact.", e);
        }
    }

    /**
     * Tries to find missing version from dependency list and dependency management. If found, the artifact is updated
     * with the correct version. It will first look for an exact match on artifactId/groupId/classifier/type and if it
     * doesn't find a match, it will try again looking for artifactId and groupId only.
     *
     * @param artifact representing configured file
     * @throws MojoException
     */
    private void fillMissingArtifactVersion(ArtifactItem artifact) throws MojoException {
        Project project = getProject();
        List<DependencyCoordinates> deps = project == null ? Collections.emptyList() : project.getDependencies();
        List<DependencyCoordinates> depMngt =
                project == null ? Collections.emptyList() : project.getManagedDependencies();

        if (!findDependencyVersion(artifact, deps, false)
                && !findDependencyVersion(artifact, depMngt, false)
                && !findDependencyVersion(artifact, deps, true)
                && !findDependencyVersion(artifact, depMngt, true)) {
            throw new MojoException("Unable to find artifact version of " + artifact.getGroupId() + ":"
                    + artifact.getArtifactId() + " in either dependency list or in project's dependency management.");
        }
    }

    /**
     * Tries to find missing version from a list of dependencies. If found, the artifact is updated with the correct
     * version.
     *
     * @param artifact representing configured file
     * @param dependencies list of dependencies to search
     * @param looseMatch only look at artifactId and groupId
     * @return the found dependency
     */
    private boolean findDependencyVersion(
            ArtifactItem artifact, List<DependencyCoordinates> dependencies, boolean looseMatch) {
        for (DependencyCoordinates dependency : dependencies) {
            String depClassifier = dependency.getClassifier();
            if (depClassifier != null && depClassifier.isEmpty()) {
                depClassifier = null;
            }
            if (Objects.equals(dependency.getArtifactId(), artifact.getArtifactId())
                    && Objects.equals(dependency.getGroupId(), artifact.getGroupId())
                    && (looseMatch || Objects.equals(depClassifier, artifact.getClassifier()))
                    && (looseMatch || Objects.equals(dependency.getType().id(), artifact.getType()))) {
                artifact.setVersion(dependency.getVersionConstraint().toString());

                return true;
            }
        }

        return false;
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
     * @param artifact the artifact
     * @throws MojoException in case of an error
     */
    public void setArtifact(String artifact) throws MojoException {
        if (artifact != null) {
            String packaging = "jar";
            String classifier;
            String[] tokens = StringUtils.split(artifact, ":");
            if (tokens.length < 3 || tokens.length > 5) {
                throw new MojoException("Invalid artifact, "
                        + "you must specify groupId:artifactId:version[:packaging[:classifier]] " + artifact);
            }
            String groupId = tokens[0];
            String artifactId = tokens[1];
            String version = tokens[2];
            if (tokens.length >= 4) {
                packaging = tokens[3];
            }
            if (tokens.length == 5) {
                classifier = tokens[4];
            } else {
                classifier = null;
            }

            ArtifactItem artifactItem = new ArtifactItem();
            artifactItem.setGroupId(groupId);
            artifactItem.setArtifactId(artifactId);
            artifactItem.setVersion(version);
            artifactItem.setType(packaging);
            artifactItem.setClassifier(classifier);

            setArtifactItems(Collections.singletonList(artifactItem));
        }
    }
}
