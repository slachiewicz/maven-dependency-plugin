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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.plugins.dependency.fromConfiguration.DependencyVersionResolver.GroupArtifactKey;
import org.apache.maven.plugins.dependency.fromConfiguration.DependencyVersionResolver.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DependencyVersionResolverTest {
    @Test
    void indexesAllVariantsUnderTheirGroupAndArtifactId() {
        Variant main = new Variant("group", "artifact", "1.0", "jar", null);
        Variant sources = new Variant("group", "artifact", "2.0", "jar", "sources");
        Variant other = new Variant("group", "other", "3.0", "jar", null);

        Map<GroupArtifactKey, List<Variant>> index =
                DependencyVersionResolver.indexVariants(Arrays.asList(main, sources, other));

        assertEquals(Arrays.asList(main, sources), index.get(new GroupArtifactKey("group", "artifact")));
        assertEquals(Arrays.asList(other), index.get(new GroupArtifactKey("group", "other")));
        assertThrows(UnsupportedOperationException.class, () -> index.clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.get(new GroupArtifactKey("group", "artifact")).clear());
    }

    @Test
    void matchesExactVariantBeforeUsingAllGaVariants() {
        List<Variant> variants = Arrays.asList(
                new Variant("group", "artifact", "1.0", "jar", null),
                new Variant("group", "artifact", "2.0", "jar", "sources"));

        ArtifactItem item = new ArtifactItem();
        item.setGroupId("group");
        item.setArtifactId("artifact");
        item.setType("jar");
        item.setClassifier("sources");

        assertIterableEquals(Arrays.asList("2.0"), DependencyVersionResolver.matchingVersions(item, variants, false));
        assertIterableEquals(
                Arrays.asList("1.0", "2.0"), DependencyVersionResolver.matchingVersions(item, variants, true));
    }

    @Test
    void resolvesTransitiveVariantsWithoutIncludingTheProjectAndCachesTheGraph() {
        Session session = mock(Session.class);
        DependencyResolver dependencyResolver = mock(DependencyResolver.class);
        when(session.getService(DependencyResolver.class)).thenReturn(dependencyResolver);
        Project project = mock(Project.class);

        Node root = node("group", "artifact", "9.0", "test-jar", "tests");
        Node direct = node("group", "direct", "1.0", "jar", null);
        List<Node> children = Arrays.asList(
                node("group", "artifact", "1.0", "jar", null), node("group", "artifact", "2.0", "test-jar", "tests"));
        when(direct.getChildren()).thenReturn(children);
        when(root.getChildren()).thenReturn(Collections.singletonList(direct));
        DependencyResolverResult result = mock(DependencyResolverResult.class);
        when(result.getRoot()).thenReturn(root);
        when(dependencyResolver.collect(any(DependencyResolverRequest.class))).thenReturn(result);

        ArtifactItem item = new ArtifactItem();
        item.setGroupId("group");
        item.setArtifactId("artifact");
        item.setType("test-jar");
        item.setClassifier("tests");
        item.setDependencyScope("runtime");
        DependencyVersionResolver resolver = new DependencyVersionResolver(project, () -> session);

        assertEquals("2.0", resolver.resolveVersion(item));
        assertEquals("2.0", resolver.resolveVersion(item));
        verify(dependencyResolver, times(1)).collect(any(DependencyResolverRequest.class));
    }

    private Node node(String groupId, String artifactId, String version, String type, String classifier) {
        Version v = mock(Version.class);
        when(v.toString()).thenReturn(version);
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn(groupId);
        when(artifact.getArtifactId()).thenReturn(artifactId);
        when(artifact.getVersion()).thenReturn(v);
        when(artifact.getClassifier()).thenReturn(classifier == null ? "" : classifier);
        Type t = mock(Type.class);
        when(t.id()).thenReturn(type);
        Dependency dependency = mock(Dependency.class);
        when(dependency.getType()).thenReturn(t);
        Node node = mock(Node.class);
        when(node.getArtifact()).thenReturn(artifact);
        when(node.getDependency()).thenReturn(dependency);
        when(node.getChildren()).thenReturn(Collections.emptyList());
        return node;
    }
}
