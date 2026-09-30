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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Node;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;

final class DependencyVersionResolver {
    private final Project project;

    private final Supplier<Session> sessionSupplier;

    private final Map<ClasspathScope, Map<GroupArtifactKey, List<Variant>>> artifactIndexByScope =
            new EnumMap<>(ClasspathScope.class);

    DependencyVersionResolver(Project project, Supplier<Session> sessionSupplier) {
        this.project = project;
        this.sessionSupplier = sessionSupplier;
    }

    /**
     * Finds the version of an artifact item, looking at the direct dependencies, the dependency management and
     * finally the dependency graph of the requested classpath scopes.
     */
    String resolveVersion(ArtifactItem artifactItem) throws MojoException {
        List<DependencyCoordinates> directDependencies =
                project == null ? Collections.emptyList() : project.getDependencies();
        List<DependencyCoordinates> managedDependencies =
                project == null ? Collections.emptyList() : project.getManagedDependencies();

        String modelVersion = findModelVersion(artifactItem, directDependencies, false);
        if (modelVersion == null) {
            modelVersion = findModelVersion(artifactItem, managedDependencies, false);
        }
        if (modelVersion == null) {
            modelVersion = findModelVersion(artifactItem, directDependencies, true);
        }
        if (modelVersion == null) {
            modelVersion = findModelVersion(artifactItem, managedDependencies, true);
        }
        if (modelVersion != null) {
            return modelVersion;
        }

        List<ClasspathScope> requestedScopes = requestedScopes(artifactItem);
        Map<ClasspathScope, Set<String>> versions = findGraphVersions(artifactItem, requestedScopes, false);
        if (versions.isEmpty()) {
            versions = findGraphVersions(artifactItem, requestedScopes, true);
        }
        if (!versions.isEmpty()) {
            return uniqueVersion(artifactItem, versions);
        }

        String scopeDescription = artifactItem.getDependencyScope() == null
                ? "compile, runtime, or test dependency graph"
                : artifactItem.getDependencyScope() + " dependency graph";
        throw new MojoException("Unable to find artifact version of " + artifactItem.getGroupId() + ":"
                + artifactItem.getArtifactId() + " in direct dependencies, dependency management, or the project's "
                + scopeDescription + ".");
    }

    private List<ClasspathScope> requestedScopes(ArtifactItem artifactItem) throws MojoException {
        String dependencyScope = artifactItem.getDependencyScope();
        if (dependencyScope == null || dependencyScope.isEmpty()) {
            List<ClasspathScope> scopes = new ArrayList<>(3);
            Collections.addAll(scopes, ClasspathScope.COMPILE, ClasspathScope.RUNTIME, ClasspathScope.TEST);
            return scopes;
        }
        try {
            return Collections.singletonList(ClasspathScope.fromString(dependencyScope));
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private Map<ClasspathScope, Set<String>> findGraphVersions(
            ArtifactItem artifactItem, List<ClasspathScope> scopes, boolean looseMatch) throws MojoException {
        Map<ClasspathScope, Set<String>> result = new LinkedHashMap<>();
        GroupArtifactKey key = new GroupArtifactKey(artifactItem.getGroupId(), artifactItem.getArtifactId());
        for (ClasspathScope scope : scopes) {
            List<Variant> variants = artifactIndex(scope).get(key);
            Set<String> versions =
                    variants == null ? Collections.emptySet() : matchingVersions(artifactItem, variants, looseMatch);
            if (!versions.isEmpty()) {
                result.put(scope, versions);
            }
        }
        return result;
    }

    private String uniqueVersion(ArtifactItem artifactItem, Map<ClasspathScope, Set<String>> versions)
            throws MojoException {
        Set<String> uniqueVersions = versions.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueVersions.size() == 1) {
            return uniqueVersions.iterator().next();
        }

        String selections = versions.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
                .collect(Collectors.joining(", "));
        throw new MojoException("Dependency graphs select different versions of " + artifactItem.getGroupId() + ":"
                + artifactItem.getArtifactId() + " (" + selections
                + "). Set dependencyScope to compile, runtime, or test, or specify version explicitly.");
    }

    private Map<GroupArtifactKey, List<Variant>> artifactIndex(ClasspathScope scope) throws MojoException {
        Map<GroupArtifactKey, List<Variant>> cached = artifactIndexByScope.get(scope);
        if (cached != null) {
            return cached;
        }

        Session session = sessionSupplier.get();
        try {
            // the path scope restricts the dependency scopes while the graph is collected, so dependencies that are
            // not on that classpath do not take part in the version mediation
            Node root = session.getService(DependencyResolver.class)
                    .collect(DependencyResolverRequest.builder()
                            .session(session)
                            .requestType(DependencyResolverRequest.RequestType.COLLECT)
                            .project(project)
                            .pathScope(scope.pathScope())
                            .build())
                    .getRoot();
            List<Variant> collected = new ArrayList<>();
            collectVariants(root, collected, true);
            Map<GroupArtifactKey, List<Variant>> index = indexVariants(collected);
            artifactIndexByScope.put(scope, index);
            return index;
        } catch (RuntimeException e) {
            throw new MojoException("Unable to collect the project's " + scope + " dependency graph.", e);
        }
    }

    private void collectVariants(Node node, List<Variant> variants, boolean root) {
        if (!root && node.getArtifact() != null) {
            String type = node.getDependency() != null && node.getDependency().getType() != null
                    ? node.getDependency().getType().id()
                    : node.getArtifact().getExtension();
            variants.add(new Variant(
                    node.getArtifact().getGroupId(),
                    node.getArtifact().getArtifactId(),
                    node.getArtifact().getVersion().toString(),
                    type,
                    node.getArtifact().getClassifier()));
        }
        for (Node child : node.getChildren()) {
            collectVariants(child, variants, false);
        }
    }

    static Map<GroupArtifactKey, List<Variant>> indexVariants(Collection<Variant> variants) {
        Map<GroupArtifactKey, List<Variant>> index = new LinkedHashMap<>();
        for (Variant variant : variants) {
            index.computeIfAbsent(new GroupArtifactKey(variant.groupId, variant.artifactId), key -> new ArrayList<>())
                    .add(variant);
        }
        for (Map.Entry<GroupArtifactKey, List<Variant>> entry : index.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(index);
    }

    static Set<String> matchingVersions(ArtifactItem artifactItem, List<Variant> variants, boolean looseMatch) {
        return variants.stream()
                .filter(variant -> looseMatch
                        || (Objects.equals(emptyToNull(variant.classifier), artifactItem.getClassifier())
                                && Objects.equals(variant.type, artifactItem.getType())))
                .map(variant -> variant.version)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private String findModelVersion(
            ArtifactItem artifactItem, List<DependencyCoordinates> dependencies, boolean looseMatch) {
        for (DependencyCoordinates dependency : dependencies) {
            if (Objects.equals(dependency.getArtifactId(), artifactItem.getArtifactId())
                    && Objects.equals(dependency.getGroupId(), artifactItem.getGroupId())
                    && (looseMatch
                            || Objects.equals(emptyToNull(dependency.getClassifier()), artifactItem.getClassifier()))
                    && (looseMatch || Objects.equals(dependency.getType().id(), artifactItem.getType()))) {
                return dependency.getVersionConstraint().toString();
            }
        }
        return null;
    }

    /** A dependency node of a collected graph, reduced to what the version lookup compares. */
    static final class Variant {
        private final String groupId;

        private final String artifactId;

        private final String version;

        private final String type;

        private final String classifier;

        Variant(String groupId, String artifactId, String version, String type, String classifier) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.classifier = classifier;
        }
    }

    static final class GroupArtifactKey {
        private final String groupId;

        private final String artifactId;

        GroupArtifactKey(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof GroupArtifactKey)) {
                return false;
            }
            GroupArtifactKey that = (GroupArtifactKey) object;
            return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, artifactId);
        }
    }
}
