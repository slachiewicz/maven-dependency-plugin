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
package org.apache.maven.plugins.dependency.utils.translators;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.TypeRegistry;
import org.apache.maven.plugins.dependency.testUtils.DependencyArtifactStubFactory;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.apache.maven.plugins.dependency.utils.DependencySilentLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * @author brianf
 */
class TestClassifierTypeTranslator {

    Set<Dependency> artifacts = new HashSet<>();

    private final Log log = new DependencySilentLog();

    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        DependencyArtifactStubFactory factory = new DependencyArtifactStubFactory(null, false);
        artifacts = factory.getMixedArtifacts();

        session = Mockito.mock(Session.class);
        TypeRegistry typeRegistry = Mockito.mock(TypeRegistry.class);
        when(session.getService(TypeRegistry.class)).thenReturn(typeRegistry);
        when(typeRegistry.lookup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return Optional.<Type>of(DependencyStubs.type(id));
        });
        when(session.createArtifact(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> DependencyStubs.dependency(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        "compile",
                        invocation.getArgument(4),
                        invocation.getArgument(3),
                        invocation.getArgument(4)));
    }

    @Test
    void testNullClassifier() {
        doTestNullEmptyClassifier(null);
    }

    @Test
    void testEmptyClassifier() {
        doTestNullEmptyClassifier("");
    }

    private void doTestNullEmptyClassifier(String classifier) {
        String type = "zip";

        ArtifactTranslator at = new ClassifierTypeTranslator(session, classifier, type);
        Set<Artifact> results = at.translate(artifacts, log);

        for (Dependency artifact : artifacts) {
            // the classifier of the artifact is kept when there is none to translate to
            assertTrue(results.stream()
                    .anyMatch(translated -> artifact.getArtifactId().equals(translated.getArtifactId())
                            && artifact.getGroupId().equals(translated.getGroupId())
                            && artifact.getClassifier().equals(translated.getClassifier())
                            && type.equals(translated.getExtension())));
        }
    }

    @Test
    void testNullType() {
        doTestNullEmptyType(null);
    }

    @Test
    void testEmptyType() {
        doTestNullEmptyType("");
    }

    private void doTestNullEmptyType(String type) {
        String classifier = "jdk5";

        ArtifactTranslator at = new ClassifierTypeTranslator(session, classifier, type);
        Set<Artifact> results = at.translate(artifacts, log);

        for (Dependency artifact : artifacts) {
            // the type of the artifact is kept when there is none to translate to
            assertTrue(results.stream()
                    .anyMatch(translated -> artifact.getArtifactId().equals(translated.getArtifactId())
                            && artifact.getGroupId().equals(translated.getGroupId())
                            && classifier.equals(translated.getClassifier())
                            && artifact.getType().id().equals(translated.getExtension())));
        }
    }

    @Test
    void testClassifierAndType() {
        String classifier = "jdk14";
        String type = "sources";
        ArtifactTranslator at = new ClassifierTypeTranslator(session, classifier, type);
        Set<Artifact> results = at.translate(artifacts, log);

        for (Dependency artifact : artifacts) {
            assertTrue(results.stream()
                    .anyMatch(translated -> artifact.getArtifactId().equals(translated.getArtifactId())
                            && artifact.getGroupId().equals(translated.getGroupId())
                            && classifier.equals(translated.getClassifier())
                            && type.equals(translated.getExtension())));
        }
    }

    @Test
    void testGetterSetter() {
        String classifier = "class";
        String type = "type";
        ClassifierTypeTranslator at = new ClassifierTypeTranslator(session, classifier, type);

        assertEquals(classifier, at.getClassifier());
        assertEquals(type, at.getType());

        at.setClassifier(type);
        at.setType(classifier);

        assertEquals(type, at.getClassifier());
        assertEquals(classifier, at.getType());
    }
}
