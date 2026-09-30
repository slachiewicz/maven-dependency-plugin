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
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.services.TypeRegistry;

/**
 * Checks that artifact items are declared as dependencies of the project, with the same version. The mojo fills in a
 * missing item version before the check.
 */
final class ExplicitDependencyMatcher {
    private final Session session;

    private final List<DependencyCoordinates> declared;

    ExplicitDependencyMatcher(Session session, List<DependencyCoordinates> declared) {
        this.session = session;
        this.declared = declared == null ? new ArrayList<DependencyCoordinates>() : declared;
    }

    /**
     * Returns one message per item that is not declared, or declared with a different version.
     *
     * @param items the items to check
     * @return the problems, empty if every item is declared
     */
    List<String> findViolations(List<ArtifactItem> items) {
        List<String> violations = new ArrayList<>();
        for (ArtifactItem item : items) {
            String violation = check(item);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    private String check(ArtifactItem item) {
        String extension = extension(item.getType());
        String classifier = classifier(item.getType(), item.getClassifier());
        String version = item.getVersion() == null ? "" : item.getVersion().trim();

        List<String> declaredVersions = new ArrayList<>();
        List<String> otherVariants = new ArrayList<>();
        for (DependencyCoordinates dependency : declared) {
            if (!Objects.equals(item.getGroupId(), dependency.getGroupId())
                    || !Objects.equals(item.getArtifactId(), dependency.getArtifactId())) {
                continue;
            }
            String declaredType = dependency.getType().id();
            // The reactor orders on groupId:artifactId:version only, so a pom declaration covers every variant.
            boolean sameVariant = "pom".equals(declaredType)
                    || (extension.equals(extension(declaredType))
                            && Objects.equals(classifier, classifier(declaredType, dependency.getClassifier())));
            if (sameVariant) {
                String declaredVersion = dependency.getVersionConstraint().toString();
                if (version.isEmpty() || versionsMatch(version, declaredVersion)) {
                    return null;
                }
                declaredVersions.add(declaredVersion);
            } else {
                String other = declaredType;
                if (dependency.getClassifier() != null
                        && !dependency.getClassifier().isEmpty()) {
                    other += ":" + dependency.getClassifier();
                }
                otherVariants.add(other);
            }
        }
        if (declaredVersions.isEmpty()) {
            return item + " is not declared as a dependency of the project"
                    + (otherVariants.isEmpty() ? "" : " (declared as " + String.join(", ", otherVariants) + ")");
        }
        return item + " is declared as a dependency with version " + String.join(", ", declaredVersions) + ", not "
                + version;
    }

    /**
     * Returns whether the project declares a dependency with the groupId and artifactId of the item, of any type,
     * classifier and version.
     *
     * @param item the item to look up
     * @return {@code true} if there is such a dependency
     */
    boolean isDeclaredInAnyVariant(ArtifactItem item) {
        for (DependencyCoordinates dependency : declared) {
            if (Objects.equals(item.getGroupId(), dependency.getGroupId())
                    && Objects.equals(item.getArtifactId(), dependency.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A declared version range matches an item version it contains; an item version range is only matched by an
     * identical declaration, because the goal resolves such a range to the highest available version, which may not
     * be the declared one.
     */
    boolean versionsMatch(String itemVersion, String declaredVersion) {
        if (itemVersion.equals(declaredVersion)) {
            return true;
        }
        return declaredVersion != null && contains(declaredVersion, itemVersion);
    }

    private boolean contains(String rangeSpec, String version) {
        if (!rangeSpec.startsWith("[") && !rangeSpec.startsWith("(")) {
            return false;
        }
        try {
            return session.parseVersionRange(rangeSpec).contains(session.parseVersion(version));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Type type(String type) {
        return session.getService(TypeRegistry.class)
                .lookup(type == null || type.isEmpty() ? Type.JAR : type)
                .orElse(null);
    }

    private String extension(String type) {
        Type resolved = type(type);
        String extension = resolved == null ? null : resolved.getExtension();
        return extension != null ? extension : type == null || type.isEmpty() ? "jar" : type;
    }

    private String classifier(String type, String classifier) {
        if (classifier != null && !classifier.isEmpty()) {
            return classifier;
        }
        Type resolved = type(type);
        String fromType = resolved == null ? null : resolved.getClassifier();
        return fromType == null || fromType.isEmpty() ? null : fromType;
    }
}
