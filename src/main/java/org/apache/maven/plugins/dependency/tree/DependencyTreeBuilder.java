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
package org.apache.maven.plugins.dependency.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.plugins.dependency.tree.DependencyNode.VerboseInfo;

/**
 * Builds the {@link DependencyNode} tree of a project from the dependency graph of Maven Resolver, as returned by the
 * Maven 4 {@link DependencyResolver}.
 * <p>
 * The plain tree is what Maven resolves for the project, so it is consistent with the Maven version running. The
 * verbose tree is collected with the resolver keeping the omitted nodes, labelled by the resolver.
 */
class DependencyTreeBuilder {

    /**
     * Builds the tree of the project.
     *
     * @param session the session
     * @param project the project
     * @param filter the dependencies to keep together with their descendants, <code>null</code> to keep all
     * @param verbose whether to include the nodes omitted for duplicates and conflicts, and the managed versions and
     *            scopes
     * @return the root node, which is the project
     * @throws DependencyResolverException if the dependencies cannot be collected
     */
    DependencyNode build(Session session, Project project, Predicate<Dependency> filter, boolean verbose)
            throws DependencyResolverException {
        // only collect the graph, do not download the artifacts
        DependencyResolverResult result = session.getService(DependencyResolver.class)
                .collect(DependencyResolverRequest.builder()
                        .session(session)
                        .requestType(DependencyResolverRequest.RequestType.COLLECT)
                        .project(project)
                        .pathScope(PathScope.TEST_RUNTIME)
                        .verbose(verbose)
                        .build());

        Artifact rootArtifact = project.getMainArtifact()
                .map(Artifact.class::cast)
                .orElseGet(() -> result.getRoot().getArtifact() != null
                        ? result.getRoot().getArtifact()
                        : project.getPomArtifact());
        DependencyNode root = new DependencyNode(null, rootArtifact, null, nodeString(rootArtifact, null));
        root.setChildren(buildChildren(root, result.getRoot(), filter, verbose));
        return root;
    }

    private DependencyNode buildNode(DependencyNode parent, Node node, Predicate<Dependency> filter, boolean verbose) {
        Dependency dependency = node.getDependency();
        Artifact artifact = dependency != null ? dependency : node.getArtifact();
        DependencyNode current = new DependencyNode(
                parent, artifact, dependency, verbose ? node.asString() : nodeString(artifact, dependency));
        if (verbose) {
            current.setVerboseInfo(verboseInfo(node.asString()));
        }
        current.setRepositories(node.getRemoteRepositories());
        current.setChildren(buildChildren(current, node, filter, verbose));
        return current;
    }

    private List<DependencyNode> buildChildren(
            DependencyNode parent, Node node, Predicate<Dependency> filter, boolean verbose) {
        List<DependencyNode> children = new ArrayList<>(node.getChildren().size());
        for (Node child : node.getChildren()) {
            Dependency dependency = child.getDependency();
            if (filter == null || dependency == null || filter.test(dependency)) {
                children.add(buildNode(parent, child, filter, verbose));
            }
        }
        return Collections.unmodifiableList(children);
    }

    private static String nodeString(Artifact artifact, Dependency dependency) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(artifact.getGroupId())
                .append(':')
                .append(artifact.getArtifactId())
                .append(':')
                .append(dependency != null ? dependency.getType().id() : artifact.getExtension());
        if (!artifact.getClassifier().isEmpty()) {
            buffer.append(':').append(artifact.getClassifier());
        }
        buffer.append(':').append(artifact.getVersion());
        if (dependency != null) {
            buffer.append(':').append(dependency.getScope().id());
            if (dependency.isOptional()) {
                buffer.append(" (optional)");
            }
        }
        return buffer.toString();
    }

    private static final Pattern PREMANAGED_VERSION = Pattern.compile("version managed from ([^;)]+)");

    private static final Pattern PREMANAGED_SCOPE = Pattern.compile("scope managed from ([^;)]+)");

    private static final Pattern CONFLICT_WINNER = Pattern.compile("omitted for conflict with ([^;)]+)");

    /**
     * Reads what the resolver wrote into the label of a verbose node. The Maven 4 API offers no structured access to
     * the omission and management data, only {@link Node#asString()}, which writes an omitted node in parentheses and
     * lists the details separated by semicolons.
     */
    static VerboseInfo verboseInfo(String label) {
        String omittedReason = null;
        String winnerVersion = null;
        Matcher conflict = CONFLICT_WINNER.matcher(label);
        if (conflict.find()) {
            omittedReason = VerboseInfo.OMITTED_FOR_CONFLICT;
            winnerVersion = conflict.group(1).trim();
        } else if (label.contains("omitted for duplicate")) {
            omittedReason = VerboseInfo.OMITTED_FOR_DUPLICATE;
        }
        // the scope selection that did not apply is only known to the Maven 3 resolver labels
        return new VerboseInfo(
                omittedReason, winnerVersion, find(PREMANAGED_VERSION, label), find(PREMANAGED_SCOPE, label), null);
    }

    private static String find(Pattern pattern, String label) {
        Matcher matcher = pattern.matcher(label);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
