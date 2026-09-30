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
package org.apache.maven.plugins.dependency.analyze;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Exclusion;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;

/**
 * This mojo looks at the dependencies after final resolution and looks for mismatches in your dependencyManagement
 * section. This mojo is also useful for detecting projects that override the dependencyManagement directly.
 * Set ignoreDirect to false to detect these otherwise normal conditions.
 *
 * @author <a href="mailto:brianefox@gmail.com">Brian Fox</a>
 * @since 2.0-alpha-3
 */
@Mojo(name = "analyze-dep-mgt")
public class AnalyzeDepMgt implements org.apache.maven.api.plugin.Mojo {
    // fields -----------------------------------------------------------------

    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * Fail the build if a problem is detected.
     */
    @Parameter(property = "mdep.analyze.failBuild", defaultValue = "false")
    private boolean failBuild = false;

    /**
     * Ignore Direct Dependency Overrides of dependencyManagement section.
     */
    @Parameter(property = "mdep.analyze.ignore.direct", defaultValue = "true")
    private boolean ignoreDirect = true;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.analyze.skip", defaultValue = "false")
    private boolean skip;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoException {
        if (skip) {
            log.info("Skipping plugin execution");
            return;
        }

        boolean result = checkDependencyManagement();
        if (result) {
            if (this.failBuild) {

                throw new MojoException("Found Dependency errors.");
            } else {
                log.warn("Potential problems found in Dependency Management ");
            }
        }
    }

    /**
     * This method resolves all the dependencies for the project and then checks them against the DependencyManagement
     * section.
     *
     * @return true if errors are found.
     * @throws MojoException in case of an error
     */
    private boolean checkDependencyManagement() throws MojoException {
        boolean foundError = false;

        log.info("Found Resolved Dependency/DependencyManagement mismatches:");

        List<DependencyCoordinates> depMgtDependencies = project.getManagedDependencies();

        if (depMgtDependencies != null && !depMgtDependencies.isEmpty()) {
            // put all the dependencies from depMgt into a map for quick lookup
            Map<String, DependencyCoordinates> depMgtMap = new HashMap<>();
            Map<String, Exclusion> exclusions = new HashMap<>();
            for (DependencyCoordinates depMgtDependency : depMgtDependencies) {
                depMgtMap.put(getManagementKey(depMgtDependency), depMgtDependency);

                // now put all the exclusions into a map for quick lookup
                exclusions.putAll(addExclusions(depMgtDependency.getExclusions()));
            }

            // get dependencies for the project (including transitive)
            DependencyResolverResult resolved = dependencyResolver.resolve(session, project, PathScope.TEST_RUNTIME);
            Set<Dependency> allDependencyArtifacts =
                    new LinkedHashSet<>(resolved.getDependencies().keySet());

            // don't warn if a dependency that is directly listed overrides
            // depMgt. That's ok.
            if (this.ignoreDirect) {
                log.info("\tIgnoring Direct Dependencies.");
                Set<String> directDependencies = new LinkedHashSet<>();
                for (Node child : resolved.getRoot().getChildren()) {
                    if (child.getDependency() != null) {
                        directDependencies.add(child.getDependency().key());
                    }
                }
                allDependencyArtifacts.removeIf(dependency -> directDependencies.contains(dependency.key()));
            }

            // log exclusion errors
            List<Dependency> exclusionErrors = getExclusionErrors(exclusions, allDependencyArtifacts);
            for (Dependency exclusion : exclusionErrors) {
                log.info(getArtifactManagementKey(exclusion)
                        + " was excluded in DepMgt, but version " + exclusion.getVersion()
                        + " has been found in the dependency tree.");
                foundError = true;
            }

            // find and log version mismatches
            Map<Dependency, DependencyCoordinates> mismatch = getMismatch(depMgtMap, allDependencyArtifacts);
            for (Map.Entry<Dependency, DependencyCoordinates> entry : mismatch.entrySet()) {
                logMismatch(entry.getKey(), entry.getValue());
                foundError = true;
            }
            if (!foundError) {
                log.info("\tNone");
            }
        } else {
            log.info("\tNothing in DepMgt.");
        }

        return foundError;
    }

    /**
     * This function returns a string comprised of the management key: groupId:artifactId:type[:classifier].
     *
     * @param dependency the managed dependency
     * @return the management key
     */
    private String getManagementKey(DependencyCoordinates dependency) {
        String classifier = dependency.getClassifier();
        return dependency.getGroupId() + ":" + dependency.getArtifactId() + ":"
                + dependency.getType().id() + ((classifier != null && !classifier.isEmpty()) ? ":" + classifier : "");
    }

    /**
     * Returns a map of the exclusions using the Dependency ManagementKey as the keyset.
     *
     * @param exclusionList list of exclusions
     * @return a map of the exclusions using the Dependency ManagementKey as the keyset.
     */
    public Map<String, Exclusion> addExclusions(java.util.Collection<Exclusion> exclusionList) {
        if (exclusionList != null) {
            return exclusionList.stream().collect(Collectors.toMap(this::getExclusionKey, exclusion -> exclusion));
        }
        return Collections.emptyMap();
    }

    /**
     * Returns a List of the artifacts that should have been excluded, but were found in the dependency tree.
     *
     * @param exclusions a map of the DependencyManagement exclusions, keyed by exclusion key.
     * @param allDependencyArtifacts resolved artifacts to be compared.
     * @return list of artifacts that should have been excluded.
     */
    public List<Dependency> getExclusionErrors(
            Map<String, Exclusion> exclusions, Set<Dependency> allDependencyArtifacts) {
        return allDependencyArtifacts.stream()
                .filter(artifact -> exclusions.containsKey(getExclusionKey(artifact)))
                .collect(Collectors.toList());
    }

    /**
     * @param artifact the artifact
     * @return the key used to match exclusions
     */
    public String getExclusionKey(Dependency artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    /**
     * @param ex the exclusion
     * @return the key used to match exclusions
     */
    public String getExclusionKey(Exclusion ex) {
        return ex.getGroupId() + ":" + ex.getArtifactId();
    }

    /**
     * Calculate the mismatches between the dependencyManagement and resolved artifacts.
     *
     * @param depMgtMap the managed dependencies, keyed by management key
     * @param allDependencyArtifacts resolved artifacts
     * @return a map containing the resolved artifact as the key and the listed dependency as the value
     */
    public Map<Dependency, DependencyCoordinates> getMismatch(
            Map<String, DependencyCoordinates> depMgtMap, Set<Dependency> allDependencyArtifacts) {
        Map<Dependency, DependencyCoordinates> mismatchMap = new HashMap<>();

        for (Dependency dependencyArtifact : allDependencyArtifacts) {
            DependencyCoordinates depFromDepMgt = depMgtMap.get(getArtifactManagementKey(dependencyArtifact));
            if (depFromDepMgt != null) {
                String managedVersion = depFromDepMgt.getVersionConstraint().toString();
                if (managedVersion != null
                        && !managedVersion.equals(
                                dependencyArtifact.getBaseVersion().toString())) {
                    mismatchMap.put(dependencyArtifact, depFromDepMgt);
                }
            }
        }
        return mismatchMap;
    }

    /**
     * This function displays the log to the screen showing the versions and information about the artifacts that
     * don't match.
     *
     * @param dependencyArtifact the artifact that was resolved
     * @param dependencyFromDepMgt the managed dependency
     * @throws MojoException in case of invalid parameters
     */
    public void logMismatch(Dependency dependencyArtifact, DependencyCoordinates dependencyFromDepMgt)
            throws MojoException {
        if (dependencyArtifact == null || dependencyFromDepMgt == null) {
            throw new MojoException(
                    "Invalid params: Artifact: " + dependencyArtifact + " Dependency: " + dependencyFromDepMgt);
        }

        log.info("\tDependency: " + getManagementKey(dependencyFromDepMgt));
        log.info("\t\tDepMgt  : " + dependencyFromDepMgt.getVersionConstraint());
        log.info("\t\tResolved: " + dependencyArtifact.getBaseVersion());
    }

    /**
     * This function returns a string comprised of the management key: groupId:artifactId:type[:classifier].
     *
     * @param artifact to gen the key for
     * @return a string containing the management key
     */
    public String getArtifactManagementKey(Dependency artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                + artifact.getType().id()
                + ((artifact.getClassifier() != null
                                && !artifact.getClassifier().isEmpty())
                        ? ":" + artifact.getClassifier()
                        : "");
    }

    /**
     * @return the failBuild
     */
    protected final boolean isFailBuild() {
        return this.failBuild;
    }

    /**
     * @param theFailBuild the failBuild to set
     */
    public void setFailBuild(boolean theFailBuild) {
        this.failBuild = theFailBuild;
    }

    /**
     * @return the project
     */
    protected final Project getProject() {
        return this.project;
    }

    /**
     * @return the ignoreDirect
     */
    protected final boolean isIgnoreDirect() {
        return this.ignoreDirect;
    }

    /**
     * @param theIgnoreDirect the ignoreDirect to set
     */
    public void setIgnoreDirect(boolean theIgnoreDirect) {
        this.ignoreDirect = theIgnoreDirect;
    }
}
