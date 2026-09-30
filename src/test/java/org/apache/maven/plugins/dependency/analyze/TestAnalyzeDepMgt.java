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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Exclusion;
import org.apache.maven.plugins.dependency.testUtils.DependencyArtifactStubFactory;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the helper methods of <code>analyze-dep-mgt</code>. The goal itself is covered by the
 * <code>analyze-dep-mgt</code> and <code>mdep-506-dependency-without-version-in-depmgt</code> integration tests.
 */
class TestAnalyzeDepMgt {

    AnalyzeDepMgt mojo;

    DependencyArtifactStubFactory stubFactory;

    DependencyCoordinates exclusion;

    Exclusion ex;

    Set<Dependency> allArtifacts;

    @BeforeEach
    void setUp() throws Exception {
        mojo = new AnalyzeDepMgt();

        stubFactory = new DependencyArtifactStubFactory(new File(""), false);

        allArtifacts = stubFactory.getMixedArtifacts();

        Dependency exclusionArtifact = stubFactory.getReleaseArtifact();
        allArtifacts.add(exclusionArtifact);
        ex = DependencyStubs.exclusion(exclusionArtifact.getGroupId(), exclusionArtifact.getArtifactId());

        List<Exclusion> exclusions = new ArrayList<>();
        exclusions.add(ex);
        exclusion = DependencyStubs.coordinates(
                exclusionArtifact.getGroupId(),
                exclusionArtifact.getArtifactId(),
                exclusionArtifact.getType().id(),
                "",
                "3.0",
                exclusions);
    }

    private static String managementKey(String groupId, String artifactId, String type, String classifier) {
        return groupId + ":" + artifactId + ":" + type
                + ((classifier != null && !classifier.isEmpty()) ? ":" + classifier : "");
    }

    @Test
    void getManagementKey() throws Exception {
        Dependency artifact = stubFactory.createArtifact("group", "artifact", "1.0", "compile", "type", "class");

        // basic case ok
        assertEquals(managementKey("group", "artifact", "type", "class"), mojo.getArtifactManagementKey(artifact));

        // now change each one and make sure it fails
        assertNotEquals(managementKey("group", "artifact", "t", "class"), mojo.getArtifactManagementKey(artifact));
        assertNotEquals(managementKey("group", "a", "type", "class"), mojo.getArtifactManagementKey(artifact));
        assertNotEquals(managementKey("group", "artifact", "type", "c"), mojo.getArtifactManagementKey(artifact));
        assertNotEquals(managementKey("g", "artifact", "type", "class"), mojo.getArtifactManagementKey(artifact));

        artifact = stubFactory.createArtifact("group", "artifact", "1.0", "compile", "type", null);
        assertEquals(managementKey("group", "artifact", "type", null), mojo.getArtifactManagementKey(artifact));

        artifact = stubFactory.createArtifact("group", "artifact", "1.0", "compile", "type", "");
        assertEquals(managementKey("group", "artifact", "type", ""), mojo.getArtifactManagementKey(artifact));
    }

    @Test
    void addExclusions() {

        assertEquals(0, mojo.addExclusions(null).size());

        List<Exclusion> list = new ArrayList<>();
        list.add(ex);
        Map<String, Exclusion> map = mojo.addExclusions(list);

        assertEquals(1, map.size());
        assertTrue(map.containsKey(mojo.getExclusionKey(ex)));
        assertSame(ex, map.get(mojo.getExclusionKey(ex)));
    }

    @Test
    void getExclusionErrors() {
        List<Exclusion> list = new ArrayList<>();
        list.add(ex);

        // already tested this method so I can trust it.
        Map<String, Exclusion> map = mojo.addExclusions(list);

        List<Dependency> l = mojo.getExclusionErrors(map, allArtifacts);

        assertEquals(1, l.size());

        assertEquals(mojo.getExclusionKey(ex), mojo.getExclusionKey(l.get(0)));
    }

    @Test
    void getMismatch() throws Exception {
        Map<String, DependencyCoordinates> depMgtMap = new HashMap<>();

        depMgtMap.put(
                managementKey(
                        exclusion.getGroupId(),
                        exclusion.getArtifactId(),
                        exclusion.getType().id(),
                        ""),
                exclusion);

        Map<Dependency, DependencyCoordinates> results = mojo.getMismatch(depMgtMap, allArtifacts);

        assertEquals(1, results.size());
        // the release artifact is used to create the exclusion
        assertTrue(results.containsKey(stubFactory.getReleaseArtifact()));
        assertSame(exclusion, results.get(stubFactory.getReleaseArtifact()));
    }
}
