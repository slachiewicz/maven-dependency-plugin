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
package org.apache.maven.plugins.dependency.resolvers;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcludeReactorProjectsDependencyFilterTest {

    @Mock
    private Project project;

    @Test
    void testReject() {
        when(project.getGroupId()).thenReturn("org.apache.maven.plugins");
        when(project.getArtifactId()).thenReturn("maven-dependency-plugin-dummy");
        when(project.getVersion()).thenReturn("1.0");

        ExcludeReactorProjectsDependencyFilter filter =
                new ExcludeReactorProjectsDependencyFilter(singletonList(project));

        Dependency dependency = Dependency.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-dependency-plugin-dummy")
                .version("1.0")
                .build();

        assertFalse(filter.test(dependency));
    }

    @Test
    void testAccept() {
        when(project.getGroupId()).thenReturn("org.apache.maven.plugins");
        when(project.getArtifactId()).thenReturn("maven-dependency-plugin-dummy");
        when(project.getVersion()).thenReturn("1.0");

        ExcludeReactorProjectsDependencyFilter filter =
                new ExcludeReactorProjectsDependencyFilter(singletonList(project));

        Dependency dependency = Dependency.newBuilder()
                .groupId("something-else")
                .artifactId("maven-dependency-plugin-dummy")
                .version("1.0")
                .build();

        assertTrue(filter.test(dependency));
    }
}
