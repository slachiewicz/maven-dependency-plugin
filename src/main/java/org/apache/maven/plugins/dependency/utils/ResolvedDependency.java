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

import java.nio.file.Path;
import java.util.Objects;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;
import org.apache.maven.api.services.TypeRegistry;

/**
 * A {@link Dependency} together with the file it was resolved to. This replaces the Maven 3
 * {@code org.apache.maven.artifact.Artifact}, which carried the resolved file, the scope and the
 * artifact handler at once, and which has no equivalent in the Maven 4 API.
 */
public final class ResolvedDependency implements DownloadedDependency {

    private final Session session;

    private final String groupId;

    private final String artifactId;

    private final Version version;

    private final Version baseVersion;

    private final String classifier;

    private final String extension;

    private final Type type;

    private final DependencyScope scope;

    private final boolean optional;

    private final boolean snapshot;

    private final Path path;

    private ResolvedDependency(
            Session session,
            Artifact artifact,
            Type type,
            String extension,
            DependencyScope scope,
            boolean optional,
            Path path) {
        this.session = session;
        this.groupId = artifact.getGroupId();
        this.artifactId = artifact.getArtifactId();
        this.version = artifact.getVersion();
        this.baseVersion = artifact.getBaseVersion();
        this.classifier = artifact.getClassifier() == null ? "" : artifact.getClassifier();
        this.extension = extension;
        this.type = type;
        this.scope = scope;
        this.optional = optional;
        this.snapshot = artifact.isSnapshot();
        this.path = path;
    }

    /**
     * Wraps a dependency with the path it has been resolved to.
     *
     * @param session the session
     * @param dependency the dependency
     * @param path the resolved path, may be {@code null} if the dependency was not resolved
     * @return the resolved dependency
     */
    public static ResolvedDependency of(Session session, Dependency dependency, Path path) {
        if (dependency instanceof ResolvedDependency && Objects.equals(((ResolvedDependency) dependency).path, path)) {
            return (ResolvedDependency) dependency;
        }
        return new ResolvedDependency(
                session,
                dependency,
                dependency.getType(),
                dependency.getExtension(),
                dependency.getScope(),
                dependency.isOptional(),
                path);
    }

    /**
     * Wraps a resolved artifact, for which a type and scope have to be supplied as the Maven 4 artifact has none.
     * A type the registry does not know is treated as a {@code jar} that keeps the requested extension, like
     * Maven 3 did with a missing artifact handler.
     *
     * @param session the session
     * @param artifact the artifact
     * @param typeId the id of the type ({@code jar}, {@code test-jar}...)
     * @param scope the scope, {@code compile} if {@code null}
     * @param path the resolved path, may be {@code null} if the artifact was not resolved
     * @return the resolved dependency
     */
    public static ResolvedDependency of(
            Session session, Artifact artifact, String typeId, DependencyScope scope, Path path) {
        TypeRegistry registry = session.getService(TypeRegistry.class);
        Type type = registry.lookup(typeId != null ? typeId : Type.JAR).orElse(null);
        String extension = type != null ? type.getExtension() : artifact.getExtension();
        if (type == null) {
            type = registry.require(Type.JAR);
        }
        return new ResolvedDependency(
                session, artifact, type, extension, scope != null ? scope : DependencyScope.COMPILE, false, path);
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    public String getArtifactId() {
        return artifactId;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public Version getBaseVersion() {
        return baseVersion;
    }

    @Override
    public String getClassifier() {
        return classifier;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public DependencyScope getScope() {
        return scope;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }

    @Override
    public boolean isSnapshot() {
        return snapshot;
    }

    @Override
    public DependencyCoordinates toCoordinates() {
        return session.createDependencyCoordinates(this);
    }

    /**
     * @return the coordinates of the artifact, without scope and type
     */
    public ArtifactCoordinates toArtifactCoordinates() {
        return session.createArtifactCoordinates(groupId, artifactId, version.toString(), classifier, extension, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolvedDependency)) {
            return false;
        }
        ResolvedDependency that = (ResolvedDependency) o;
        return groupId.equals(that.groupId)
                && artifactId.equals(that.artifactId)
                && version.equals(that.version)
                && Objects.equals(classifier, that.classifier)
                && extension.equals(that.extension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier, extension);
    }

    /**
     * The Maven 3 {@code Artifact#getId()} form: {@code groupId:artifactId:type:[classifier:]version}.
     *
     * @return the identifier
     */
    public String getId() {
        return DependencyUtil.getId(this);
    }

    @Override
    public String toString() {
        return getId();
    }
}
