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
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.utils.CopyUtil;
import org.apache.maven.plugins.dependency.utils.DependencyStatusSets;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;
import org.apache.maven.plugins.dependency.utils.filters.DestFileFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;

/**
 * Goal that copies the files for a project's dependencies from the repository to a directory.
 * The default location to copy to is target/dependencies.
 * If dependencies map to the same destination file, the goal fails before copying to avoid overwriting an artifact.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @since 1.0
 */
@Mojo(name = "copy-dependencies", defaultPhase = "process-sources")
public class CopyDependenciesMojo extends AbstractFromDependenciesMojo {
    /**
     * Also copy the pom of each artifact.
     *
     * @since 2.0
     */
    @Parameter(property = "mdep.copyPom", defaultValue = "false")
    protected boolean copyPom;

    /**
     * Either append the artifact's baseVersion or uniqueVersion to the filename. Will only be used if
     * {@link #isStripVersion()} is {@code false}.
     *
     * @since 2.6
     */
    @Parameter(property = "mdep.useBaseVersion", defaultValue = "true")
    protected boolean useBaseVersion = true;

    /**
     * Add parent poms to the list of copied dependencies (both current project pom parents and dependencies parents).
     * The {@code classifier} and {@code type} parameters do not change parent POM coordinates.
     * Inclusion and exclusion filters still apply.
     *
     * @since 2.8
     */
    @Parameter(property = "mdep.addParentPoms", defaultValue = "false")
    protected boolean addParentPoms;

    /**
     * Add the POMs of the BOMs that the project and its parents import with {@code <scope>import</scope>} in their
     * {@code dependencyManagement} (and of the parents and imports of those BOMs) to the list of copied dependencies,
     * so that the copied POMs stay resolvable. Use it together with {@link #addParentPoms}; it is a separate parameter
     * so that the output of {@code addParentPoms} does not change. Inclusion and exclusion filters still apply. An
     * import whose coordinates cannot be interpolated is skipped with a warning.
     *
     * @since 3.12.0
     */
    @Parameter(property = "mdep.addImportedBoms", defaultValue = "false")
    protected boolean addImportedBoms;

    /**
     * Also copy the signature files (.asc) of each artifact.
     *
     * @since 3.2.0
     */
    @Parameter(property = "mdep.copySignatures", defaultValue = "false")
    protected boolean copySignatures;

    private CopyUtil copyUtil;

    @Override
    protected boolean isResolvingAfterFiltering() {
        return true;
    }

    /**
     * Main entry into mojo. Gets the list of dependencies and iterates through calling copyArtifact.
     *
     * @throws MojoException with a message if an error occurs
     * @see #getDependencySets(boolean, boolean)
     * @see #copyArtifact(DownloadedDependency, boolean, boolean, boolean, boolean)
     */
    @Override
    protected void doExecute() throws MojoException {
        copyUtil = new CopyUtil(getLog());
        DependencyStatusSets dss =
                getDependencySets(this.failOnMissingClassifierArtifact, addParentPoms, addImportedBoms);
        Set<Dependency> artifacts = dss.getResolvedDependencies();

        if (!useRepositoryLayout) {
            Map<File, Dependency> copies = new HashMap<>();
            for (Dependency artifactItem : artifacts) {
                String destFileName = DependencyUtil.getFormattedFileName(
                        artifactItem, stripVersion, prependGroupId, useBaseVersion, stripClassifier);
                File destDir = DependencyUtil.getFormattedOutputDirectory(
                        useSubDirectoryPerScope,
                        useSubDirectoryPerType,
                        useSubDirectoryPerArtifact,
                        useRepositoryLayout,
                        stripVersion,
                        stripType,
                        outputDirectory,
                        artifactItem);
                File destFile = new File(destDir, destFileName)
                        .getAbsoluteFile()
                        .toPath()
                        .normalize()
                        .toFile();
                Dependency existingArtifact = copies.putIfAbsent(destFile, artifactItem);
                if (existingArtifact != null) {
                    throw new MojoException("Multiple artifacts map to the same destination file '" + destFile + "': "
                            + DependencyUtil.getId(existingArtifact) + " and " + DependencyUtil.getId(artifactItem)
                            + ". Consider using prependGroupId, useSubDirectoryPerArtifact, or useRepositoryLayout.");
                }
            }

            for (Dependency artifact : artifacts) {
                copyArtifact(
                        (DownloadedDependency) artifact,
                        isStripVersion(),
                        this.prependGroupId,
                        this.useBaseVersion,
                        this.stripClassifier);
            }
        } else {
            Session repositorySession = getResolverUtil().localRepositorySession(outputDirectory);

            artifacts.forEach(artifact -> installArtifact((DownloadedDependency) artifact, repositorySession));
            if (isCopyPom()) {
                // Artifacts that already exist may not yet have poms
                dss.getSkippedDependencies()
                        .forEach(artifact -> installMissingPom((DownloadedDependency) artifact, repositorySession));
            }
        }

        Set<Dependency> skippedArtifacts = dss.getSkippedDependencies();
        for (Dependency artifact : skippedArtifacts) {
            getLog().info(DependencyUtil.getId(artifact) + " already exists in destination.");
        }

        if (isCopyPom() && !useRepositoryLayout) {
            copyPoms(getOutputDirectory(), artifacts, this.stripVersion, this.stripClassifier, true);
            // Artifacts that already exist may not yet have poms
            copyPoms(getOutputDirectory(), skippedArtifacts, this.stripVersion, this.stripClassifier, false);
        }
    }

    /**
     * Install the artifact and the corresponding pom if copyPoms=true.
     */
    private void installArtifact(DownloadedDependency artifact, Session repositorySession) {
        try {
            getResolverUtil().installArtifact(artifact, repositorySession);
            installBaseSnapshot(artifact, repositorySession);
            copyChecksumFiles(artifact, repositorySession);

            if (!"pom".equals(artifact.getType().id()) && isCopyPom()) {
                DownloadedDependency pomArtifact = getResolvedPomArtifact(artifact);
                if (pomArtifact != null
                        && pomArtifact.getPath() != null
                        && pomArtifact.getPath().toFile().exists()) {
                    getResolverUtil().installArtifact(pomArtifact, repositorySession);
                    installBaseSnapshot(pomArtifact, repositorySession);
                    copyChecksumFiles(pomArtifact, repositorySession);
                }
            }
        } catch (RuntimeException e) {
            getLog().warn("unable to install " + artifact, e);
        }
    }

    /**
     * Install the pom of an artifact that was skipped because it already exists in the destination, unless the pom
     * exists there too.
     */
    private void installMissingPom(DownloadedDependency artifact, Session repositorySession) {
        if ("pom".equals(artifact.getType().id())) {
            return;
        }
        // Check the destination first, resolving the POM may mean a remote request.
        Artifact pom = repositorySession.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion().toString(),
                "",
                "pom",
                "pom");
        if (repositorySession.getPathForLocalArtifact(pom).toFile().exists()) {
            return;
        }
        try {
            DownloadedDependency pomArtifact = getResolvedPomArtifact(artifact);
            if (pomArtifact == null
                    || pomArtifact.getPath() == null
                    || !pomArtifact.getPath().toFile().exists()) {
                return;
            }
            getResolverUtil().installArtifact(pomArtifact, repositorySession);
            installBaseSnapshot(pomArtifact, repositorySession);
            copyChecksumFiles(pomArtifact, repositorySession);
        } catch (RuntimeException e) {
            getLog().warn("unable to install the pom of " + artifact, e);
        }
    }

    /**
     * Copies the checksum files next to the artifact in the source local repository to the matching location in the
     * target repository layout. The local repository names a timestamped snapshot by its base version, as the
     * installed artifact is.
     */
    private void copyChecksumFiles(DownloadedDependency artifact, Session repositorySession) {
        if (artifact.getPath() == null) {
            return;
        }
        File source = artifact.getPath().toFile();
        File destination = repositorySession
                .getPathForLocalArtifact(repositorySession.createArtifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion().toString(),
                        artifact.getClassifier(),
                        artifact.getExtension(),
                        artifact.getType().id()))
                .toFile();
        for (String extension : CHECKSUM_EXTENSIONS) {
            File checksum = new File(source.getPath() + extension);
            if (checksum.isFile()) {
                try {
                    copyUtil.copyFile(checksum, new File(destination.getPath() + extension));
                } catch (IOException e) {
                    getLog().warn("Failed to copy checksum file: " + checksum, e);
                }
            }
        }
    }

    private void installBaseSnapshot(DownloadedDependency artifact, Session repositorySession) {
        if (artifact.isSnapshot() && !artifact.getBaseVersion().equals(artifact.getVersion())) {
            getResolverUtil()
                    .installArtifact(artifact, artifact.getBaseVersion().toString(), repositorySession);
        }
    }

    /**
     * Copies the Artifact after building the destination file name if overridden. This method also checks if the
     * classifier is set and adds it to the destination file name if needed.
     *
     * @param artifact the object to be copied
     * @param removeVersion specifies if the version should be removed from the file name when copying
     * @param prependGroupId specifies if the group ID should be prefixed to the file while copying
     * @param theUseBaseVersion specifies if the baseVersion of the artifact should be used instead of the version
     * @throws MojoException with a message if an error occurs
     * @see #copyArtifact(DownloadedDependency, boolean, boolean, boolean, boolean)
     */
    protected void copyArtifact(
            DownloadedDependency artifact, boolean removeVersion, boolean prependGroupId, boolean theUseBaseVersion)
            throws MojoException {
        copyArtifact(artifact, removeVersion, prependGroupId, theUseBaseVersion, false);
    }

    private static final String SIGNATURE_EXTENSION = ".asc";

    private static final String[] CHECKSUM_EXTENSIONS = {".sha1", ".md5", ".sha256", ".sha512"};

    /**
     * Copies the Artifact after building the destination file name if overridden. This method also checks if the
     * classifier is set and adds it to the destination file name if needed.
     *
     * @param artifact the object to be copied
     * @param removeVersion specifies if the version should be removed from the file name when copying
     * @param prependGroupId specifies if the groupId should be prefixed to the file while copying
     * @param useBaseVersion specifies if the baseVersion of the artifact should be used instead of the version
     * @param removeClassifier specifies if the classifier should be removed from the file name when copying
     * @throws MojoException with a message if an error occurs
     * @see CopyUtil#copyArtifactFile(DownloadedDependency, File)
     * @see DependencyUtil#getFormattedOutputDirectory(boolean, boolean, boolean, boolean, boolean, boolean, File, Dependency)
     */
    protected void copyArtifact(
            DownloadedDependency artifact,
            boolean removeVersion,
            boolean prependGroupId,
            boolean useBaseVersion,
            boolean removeClassifier)
            throws MojoException {

        String destFileName = DependencyUtil.getFormattedFileName(
                artifact, removeVersion, prependGroupId, useBaseVersion, removeClassifier);

        File destDir = DependencyUtil.getFormattedOutputDirectory(
                useSubDirectoryPerScope,
                useSubDirectoryPerType,
                useSubDirectoryPerArtifact,
                useRepositoryLayout,
                stripVersion,
                stripType,
                outputDirectory,
                artifact);
        File destFile = new File(destDir, destFileName);
        if (destFile.exists()) {
            getLog().warn("Overwriting " + destFile);
        }
        try {
            copyUtil.copyArtifactFile(artifact, destFile);

            // Copy the signature file if the copySignatures flag is true
            if (copySignatures) {
                copySignatureFile(artifact, destDir, destFileName);
            }

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to copy artifact '" + artifact + "' (" + artifact.getPath() + ") to " + destFile, e);
        }
    }

    /**
     * Copies the signature file of the artifact to the destination directory, if it exists or can be resolved.
     * If the signature file does not exist and cannot be resolved, a warning is logged.
     *
     * @param artifact the artifact whose signature file should be copied
     * @param destDir the destination directory
     * @param destFileName the destination file name without the extension
     */
    private void copySignatureFile(DownloadedDependency artifact, File destDir, String destFileName) {
        File signatureFile = new File(artifact.getPath().toAbsolutePath() + SIGNATURE_EXTENSION);

        if (!signatureFile.exists()) {
            try {
                Artifact signatureArtifact = session.createArtifact(
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion().toString(),
                        artifact.getClassifier(),
                        "jar" + SIGNATURE_EXTENSION,
                        null);
                DownloadedArtifact resolvedSignature = getResolverUtil()
                        .resolveArtifact(signatureArtifact, getResolverUtil().getProjectRepositories());
                signatureFile = resolvedSignature.getPath().toFile();
            } catch (RuntimeException e) {
                getLog().warn("Failed to resolve signature file for artifact: " + artifact, e);
            }
        }

        if (signatureFile != null && signatureFile.exists()) {
            File signatureDestFile = new File(destDir, destFileName + SIGNATURE_EXTENSION);
            try {
                copyUtil.copyFile(signatureFile, signatureDestFile);
            } catch (IOException e) {
                getLog().warn("Failed to copy signature file: " + signatureFile, e);
            }
        } else {
            getLog().warn("Signature file for artifact " + artifact + " not found and could not be resolved.");
        }
    }

    /**
     * Copy the pom files associated with the artifacts.
     *
     * @param destDir the destination directory {@link File}
     * @param artifacts the artifacts {@link Dependency}
     * @param removeVersion remove version or not
     * @throws MojoException in case of errors
     */
    public void copyPoms(File destDir, Set<Dependency> artifacts, boolean removeVersion) throws MojoException {

        copyPoms(destDir, artifacts, removeVersion, false);
    }

    /**
     * Copy the pom files associated with the artifacts.
     *
     * @param destDir the destination directory {@link File}
     * @param artifacts the artifacts {@link Dependency}
     * @param removeVersion remove version or not
     * @param removeClassifier remove the classifier or not
     * @throws MojoException in case of errors
     */
    public void copyPoms(File destDir, Set<Dependency> artifacts, boolean removeVersion, boolean removeClassifier)
            throws MojoException {
        copyPoms(destDir, artifacts, removeVersion, removeClassifier, false);
    }

    private void copyPoms(
            File destDir, Set<Dependency> artifacts, boolean removeVersion, boolean removeClassifier, boolean overwrite)
            throws MojoException {

        for (Dependency artifact : artifacts) {
            DownloadedDependency pomArtifact = getResolvedPomArtifact(artifact);

            // Copy the pom
            if (pomArtifact != null
                    && pomArtifact.getPath() != null
                    && pomArtifact.getPath().toFile().exists()) {
                File pomDestFile = new File(
                        destDir,
                        DependencyUtil.getFormattedFileName(
                                pomArtifact, removeVersion, prependGroupId, useBaseVersion, removeClassifier));
                if (overwrite || !pomDestFile.exists()) {
                    try {
                        copyUtil.copyArtifactFile(pomArtifact, pomDestFile);
                    } catch (IOException e) {
                        throw new MojoException(
                                "Failed to copy artifact '" + pomArtifact + "' (" + pomArtifact.getPath() + ") to "
                                        + pomDestFile,
                                e);
                    }
                }
            }
        }
    }

    /**
     * @param artifact {@link Dependency}
     * @return the resolved pom of the artifact, {@code null} if it cannot be resolved
     */
    protected DownloadedDependency getResolvedPomArtifact(Dependency artifact) {

        DownloadedDependency pomArtifact = null;
        // Resolve the pom artifact using repos
        try {
            Artifact pom = session.createArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion().toString(),
                    "",
                    "pom",
                    null);
            List<RemoteRepository> repositories = getResolverUtil().getProjectRepositories();
            DownloadedArtifact resolvedPom = getResolverUtil().resolveArtifact(pom, repositories);
            pomArtifact = ResolvedDependency.of(session, resolvedPom, "pom", null, resolvedPom.getPath());
        } catch (RuntimeException e) {
            getLog().info(e.getMessage());
        }
        return pomArtifact;
    }

    @Override
    protected ArtifactsFilter getMarkedArtifactFilter() {
        return new DestFileFilter(
                this.overWriteReleases,
                this.overWriteSnapshots,
                this.overWriteIfNewer,
                this.useSubDirectoryPerArtifact,
                this.useSubDirectoryPerType,
                this.useSubDirectoryPerScope,
                this.useRepositoryLayout,
                this.stripVersion,
                this.prependGroupId,
                this.useBaseVersion,
                this.outputDirectory);
    }

    /**
     * @return true, if the pom of each artifact must be copied
     */
    public boolean isCopyPom() {
        return this.copyPom;
    }

    /**
     * @param copyPom true if the pom of each artifact must be copied
     */
    public void setCopyPom(boolean copyPom) {
        this.copyPom = copyPom;
    }
}
