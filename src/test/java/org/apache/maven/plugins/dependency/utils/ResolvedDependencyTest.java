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
import java.nio.file.Paths;

import org.apache.maven.api.Dependency;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the replacement of the Maven 3 artifact: identity, the path it was resolved to and the Maven 3 string forms.
 */
class ResolvedDependencyTest {

    private final Dependency dependency =
            DependencyStubs.dependency("org.example", "lib", "1.0", "runtime", "test-jar", "tests", "jar");

    @Test
    void keepsThePathAndTheCoordinatesOfTheDependency() {
        Path path = Paths.get("lib-1.0-tests.jar");

        ResolvedDependency resolved = ResolvedDependency.of(null, dependency, path);

        assertEquals(path, resolved.getPath());
        assertEquals("org.example", resolved.getGroupId());
        assertEquals("lib", resolved.getArtifactId());
        assertEquals("1.0", resolved.getVersion().toString());
        assertEquals("tests", resolved.getClassifier());
        assertEquals("jar", resolved.getExtension());
        assertEquals("test-jar", resolved.getType().id());
        assertEquals(dependency.getScope(), resolved.getScope());
    }

    @Test
    void isIdentifiedByItsCoordinatesNotByItsPath() {
        ResolvedDependency a = ResolvedDependency.of(null, dependency, Paths.get("a.jar"));
        ResolvedDependency b = ResolvedDependency.of(null, dependency, Paths.get("b.jar"));
        ResolvedDependency other = ResolvedDependency.of(
                null,
                DependencyStubs.dependency("org.example", "lib", "2.0", "runtime", "test-jar", "tests", "jar"),
                null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, other);
    }

    @Test
    void wrapsOnlyWhenThePathChanges() {
        Path path = Paths.get("lib.jar");
        ResolvedDependency resolved = ResolvedDependency.of(null, dependency, path);

        assertSame(resolved, ResolvedDependency.of(null, resolved, path));
    }

    @Test
    void formatsLikeMaven3() {
        assertEquals("org.example:lib:test-jar:tests:1.0", DependencyUtil.getId(dependency));
        assertEquals("org.example:lib:test-jar:tests:1.0:runtime", DependencyUtil.toString(dependency));
        assertEquals("org.example:lib:test-jar:tests", DependencyUtil.getDependencyConflictId(dependency));

        Dependency plain = DependencyStubs.dependency("org.example", "lib", "1.0", "compile", "jar", "");
        assertEquals("org.example:lib:jar:1.0", DependencyUtil.getId(plain));
        assertEquals("org.example:lib:jar", DependencyUtil.getDependencyConflictId(plain));
    }
}
