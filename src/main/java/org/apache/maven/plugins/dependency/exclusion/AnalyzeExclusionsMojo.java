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
package org.apache.maven.plugins.dependency.exclusion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Exclusion;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.plugins.dependency.exclusion.Coordinates.coordinates;

/**
 * Analyzes the exclusions defined on dependencies in this project and reports if any of them are unneeded.
 * <p>
 * Relevant use case is when an artifact in a later version has removed usage of a dependency, making the exclusion no
 * longer necessary.
 * </p>
 *
 * @since 3.7.0
 */
@Mojo(name = "analyze-exclusions")
public class AnalyzeExclusionsMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * Whether to fail the build if invalid exclusions is found.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.exclusion.fail", defaultValue = "false")
    private boolean exclusionFail;

    /**
     * Skip plugin execution completely.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Current project modelId.
     */
    private String projectModelId;

    @Override
    public void execute() throws MojoException {
        if (skip) {
            log.debug("Skipping execution");
            return;
        }

        projectModelId = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

        Map<Coordinates, Collection<Exclusion>> dependenciesWithExclusions = new HashMap<>();

        DependencyManagement depMgt = project.getModel().getDependencyManagement();
        if (depMgt != null) {
            List<Dependency> depMgtDependencies = depMgt.getDependencies();

            if (depMgtDependencies != null) {
                depMgtDependencies.forEach(dependency -> {
                    Collection<Exclusion> exclusions = getExclusionsForDependency(dependency);
                    if (!exclusions.isEmpty()) {
                        dependenciesWithExclusions
                                .computeIfAbsent(coordinates(dependency), d -> new ArrayList<>())
                                .addAll(exclusions);
                    }
                });
            }
        }

        project.getModel().getDependencies().forEach(dependency -> {
            Collection<Exclusion> exclusions = getExclusionsForDependency(dependency);
            if (!exclusions.isEmpty()) {
                dependenciesWithExclusions
                        .computeIfAbsent(coordinates(dependency), d -> new ArrayList<>())
                        .addAll(exclusions);
            }
        });

        if (dependenciesWithExclusions.isEmpty()) {
            log.debug("No dependencies defined with exclusions - exiting");
            return;
        }

        ExclusionChecker checker = new ExclusionChecker();

        for (Map.Entry<Coordinates, Collection<Exclusion>> entry : dependenciesWithExclusions.entrySet()) {

            Coordinates currentCoordinates = entry.getKey();

            Set<Coordinates> actualCoordinates = collectDependencies(currentCoordinates.getDependency());

            Set<Coordinates> exclusions =
                    entry.getValue().stream().map(Coordinates::coordinates).collect(toSet());

            checker.check(currentCoordinates, exclusions, actualCoordinates);
        }

        if (!checker.getViolations().isEmpty()) {
            if (exclusionFail) {
                logViolations(projectName(), checker.getViolations(), value -> log.error(value));
                throw new MojoException("Invalid exclusions found");
            } else {
                logViolations(projectName(), checker.getViolations(), value -> log.warn(value));
            }
        } else {
            log.info("No problems with dependencies exclusions");
        }
    }

    /**
     * Collects the dependency and its transitive dependencies, ignoring the exclusions of the dependency.
     */
    private Set<Coordinates> collectDependencies(Dependency dependency) throws MojoException {
        try {
            DependencyCoordinates root = session.createDependencyCoordinates(session.createArtifactCoordinates(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion(),
                    dependency.getClassifier(),
                    null,
                    dependency.getType()));
            Node rootNode = dependencyResolver
                    .collect(DependencyResolverRequest.builder()
                            .session(session)
                            .requestType(DependencyResolverRequest.RequestType.COLLECT)
                            .dependency(root)
                            .pathScope(PathScope.TEST_RUNTIME)
                            .build())
                    .getRoot();
            Set<Coordinates> coordinates = new HashSet<>();
            rootNode.accept(new org.apache.maven.api.NodeVisitor() {
                @Override
                public boolean enter(Node node) {
                    if (node.getArtifact() != null) {
                        coordinates.add(coordinates(
                                node.getArtifact().getGroupId(),
                                node.getArtifact().getArtifactId()));
                    }
                    return true;
                }

                @Override
                public boolean leave(Node node) {
                    return true;
                }
            });
            return coordinates;
        } catch (RuntimeException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private String projectName() {
        // like MavenProject#getName(): the artifactId when the project has no name
        String name = project.getModel().getName();
        return name != null ? name : project.getArtifactId();
    }

    private Collection<Exclusion> getExclusionsForDependency(Dependency dependency) {
        return dependency.getExclusions().stream()
                .filter(this::isExclusionInProject)
                .collect(toList());
    }

    private boolean isExclusionInProject(Exclusion exclusion) {
        String modelId = exclusion.getLocation("").getSource().getModelId();
        return projectModelId.equals(modelId);
    }

    private void logViolations(String name, Map<Coordinates, List<Coordinates>> violations, Consumer<String> logger) {
        logger.accept(name + " defines following unnecessary excludes");
        violations.forEach((dependency, invalidExclusions) -> {
            logger.accept("    " + dependency);
            invalidExclusions.forEach(invalidExclusion -> logger.accept("        - " + invalidExclusion));
        });
    }
}
