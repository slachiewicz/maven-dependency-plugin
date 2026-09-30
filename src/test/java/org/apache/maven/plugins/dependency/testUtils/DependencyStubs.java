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
package org.apache.maven.plugins.dependency.testUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.Exclusion;
import org.apache.maven.api.Language;
import org.apache.maven.api.PathType;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;
import org.apache.maven.api.VersionConstraint;
import org.apache.maven.api.VersionRange;

/**
 * Test doubles for the Maven 4 API, which has no public factory for {@link Dependency}, {@link Type} or
 * {@link Version} outside of a {@code Session}.
 */
public final class DependencyStubs {

    private DependencyStubs() {}

    public static Dependency dependency(
            String groupId, String artifactId, String version, String scope, String type, String classifier) {
        return new StubDependency(groupId, artifactId, version, scope, type, classifier, false);
    }

    public static Dependency dependency(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            String classifier,
            boolean optional) {
        return new StubDependency(groupId, artifactId, version, scope, type, classifier, optional);
    }

    public static Dependency dependency(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            String classifier,
            String extension) {
        return new StubDependency(groupId, artifactId, version, scope, type, extension, classifier, false);
    }

    public static DependencyCoordinates coordinates(String groupId, String artifactId) {
        return new StubCoordinates(groupId, artifactId, "jar", "", null, List.of());
    }

    public static DependencyCoordinates coordinates(
            String groupId,
            String artifactId,
            String type,
            String classifier,
            String version,
            Collection<Exclusion> exclusions) {
        return new StubCoordinates(groupId, artifactId, type, classifier, version, exclusions);
    }

    public static Exclusion exclusion(String groupId, String artifactId) {
        return new Exclusion() {
            @Override
            public String getGroupId() {
                return groupId;
            }

            @Override
            public String getArtifactId() {
                return artifactId;
            }
        };
    }

    public static Type type(String id) {
        return new StubType(id);
    }

    public static Type type(String id, String extension) {
        return new StubType(id, extension);
    }

    public static Version version(String version) {
        return new StubVersion(version);
    }

    static final class StubCoordinates implements DependencyCoordinates {
        private final String groupId;
        private final String artifactId;
        private final String type;
        private final String classifier;
        private final String version;
        private final Collection<Exclusion> exclusions;

        StubCoordinates(
                String groupId,
                String artifactId,
                String type,
                String classifier,
                String version,
                Collection<Exclusion> exclusions) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.type = type;
            this.classifier = classifier;
            this.version = version;
            this.exclusions = exclusions;
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
        public String getClassifier() {
            return classifier;
        }

        @Override
        public VersionConstraint getVersionConstraint() {
            return version == null ? null : new StubVersionConstraint(version);
        }

        @Override
        public String getExtension() {
            return type;
        }

        @Override
        public Type getType() {
            return new StubType(type);
        }

        @Override
        public DependencyScope getScope() {
            return DependencyScope.COMPILE;
        }

        @Override
        public Boolean getOptional() {
            return Boolean.FALSE;
        }

        @Override
        public Collection<Exclusion> getExclusions() {
            return exclusions;
        }
    }

    static final class StubVersionConstraint implements VersionConstraint {
        private final String version;

        StubVersionConstraint(String version) {
            this.version = version;
        }

        @Override
        public VersionRange getVersionRange() {
            return null;
        }

        @Override
        public Version getRecommendedVersion() {
            return new StubVersion(version);
        }

        @Override
        public boolean contains(Version version) {
            return this.version.equals(version.toString());
        }

        @Override
        public String toString() {
            return version;
        }
    }

    static final class StubType implements Type {
        private final String id;

        private final String extension;

        StubType(String id) {
            this(id, id);
        }

        StubType(String id, String extension) {
            this.id = id;
            this.extension = extension;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Language getLanguage() {
            return null;
        }

        @Override
        public String getExtension() {
            return extension;
        }

        @Override
        public String getClassifier() {
            return null;
        }

        @Override
        public boolean isIncludesDependencies() {
            return false;
        }

        @Override
        public Set<PathType> getPathTypes() {
            return Set.of();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    static final class StubVersion implements Version {
        private final String version;

        StubVersion(String version) {
            this.version = version;
        }

        @Override
        public int compareTo(Version other) {
            return version.compareTo(other.toString());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StubVersion && version.equals(((StubVersion) o).version);
        }

        @Override
        public int hashCode() {
            return version.hashCode();
        }

        @Override
        public String toString() {
            return version;
        }
    }

    static final class StubDependency implements Dependency {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String scope;
        private final String type;
        private final String extension;
        private final String classifier;
        private final boolean optional;

        StubDependency(
                String groupId,
                String artifactId,
                String version,
                String scope,
                String type,
                String classifier,
                boolean optional) {
            this(groupId, artifactId, version, scope, type, type, classifier, optional);
        }

        // CHECKSTYLE_OFF: ParameterNumber
        StubDependency(
                String groupId,
                String artifactId,
                String version,
                String scope,
                String type,
                String extension,
                String classifier,
                boolean optional) {
            // CHECKSTYLE_ON: ParameterNumber
            this.extension = extension;
            this.optional = optional;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.type = type;
            this.classifier = classifier;
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
            return new StubVersion(version);
        }

        @Override
        public Version getBaseVersion() {
            return new StubVersion(version.replaceFirst("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT"));
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
        public boolean isSnapshot() {
            return version.endsWith("-SNAPSHOT");
        }

        @Override
        public Type getType() {
            return new StubType(type, extension);
        }

        @Override
        public DependencyScope getScope() {
            return scope == null ? null : DependencyScope.forId(scope);
        }

        @Override
        public boolean isOptional() {
            return optional;
        }

        @Override
        public DependencyCoordinates toCoordinates() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String key() {
            return groupId + ":" + artifactId + ":" + extension + ":" + classifier + ":" + version;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StubDependency && key().equals(((StubDependency) o).key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }

        @Override
        public String toString() {
            return key() + ":" + scope;
        }
    }
}
