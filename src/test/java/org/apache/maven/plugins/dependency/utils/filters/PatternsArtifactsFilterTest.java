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
package org.apache.maven.plugins.dependency.utils.filters;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternsArtifactsFilterTest {

    private final Dependency spring = artifact("org.springframework", "spring-core", "6.1.0");

    private final Dependency snapshot = artifact("org.acme", "lib", "1.0-SNAPSHOT");

    private final Dependency guava = artifact("com.google.guava", "guava", "33.0.0");

    private Set<Dependency> all() {
        return new LinkedHashSet<>(Arrays.asList(spring, snapshot, guava));
    }

    private static Dependency artifact(String groupId, String artifactId, String version) {
        return DependencyStubs.dependency(groupId, artifactId, version, "compile", "jar", null);
    }

    @Test
    void noPatternsKeepEverything() throws Exception {
        assertEquals(all(), new PatternsArtifactsFilter(null, Collections.emptyList()).filter(all()));
    }

    @Test
    void includesKeepMatchingGroupId() throws Exception {
        Set<Dependency> result = new PatternsArtifactsFilter(Arrays.asList("org.springframework"), null).filter(all());
        assertEquals(Collections.singleton(spring), result);
    }

    @Test
    void includesMatchSnapshotVersions() throws Exception {
        Set<Dependency> result = new PatternsArtifactsFilter(Arrays.asList(":::*-SNAPSHOT"), null).filter(all());
        assertEquals(Collections.singleton(snapshot), result);
    }

    @Test
    void excludesRemoveMatching() throws Exception {
        Set<Dependency> result = new PatternsArtifactsFilter(null, Arrays.asList("org.*")).filter(all());
        assertEquals(Collections.singleton(guava), result);
    }

    @Test
    void excludeTakesPrecedenceOverInclude() throws Exception {
        Set<Dependency> result =
                new PatternsArtifactsFilter(Arrays.asList("org.*"), Arrays.asList("org.acme")).filter(all());
        assertEquals(Collections.singleton(spring), result);
    }
}
