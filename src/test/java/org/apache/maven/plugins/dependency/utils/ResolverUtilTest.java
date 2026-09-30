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

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.LocalRepository;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Repository;
import org.apache.maven.api.services.TypeRegistry;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.of;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolverUtilTest {

    @Mock
    private Session session;

    @Mock
    private TypeRegistry typeRegistry;

    @Mock
    private RemoteRepository remoteRepository;

    @TempDir
    private File tempDir;

    private ResolverUtil resolverUtil;

    @BeforeEach
    void setUp() {
        resolverUtil = new ResolverUtil(session, null);
        when(session.getService(TypeRegistry.class)).thenReturn(typeRegistry);
        when(session.createRemoteRepository(any(Repository.class))).thenReturn(remoteRepository);
    }

    public static Stream<Arguments> prepareRepositoryTest() {

        return Stream.of(
                of("", "temp", "default", ""),
                of("https://repo.maven.apache.org", "temp", "default", "https://repo.maven.apache.org"),
                of("central::https://repo.maven.apache.org", "central", "default", "https://repo.maven.apache.org"),
                of("central::::https://repo.maven.apache.org", "central", "default", "https://repo.maven.apache.org"),
                of(
                        "central::layout2::https://repo.maven.apache.org",
                        "central",
                        "layout2",
                        "https://repo.maven.apache.org"),
                // surrounding whitespace is not part of the id, layout or url: a comma-separated
                // list written with spaces after the commas must behave like one written without
                of(
                        " central :: default :: https://repo.maven.apache.org ",
                        "central",
                        "default",
                        "https://repo.maven.apache.org"),
                of(" central :: https://repo.maven.apache.org ", "central", "default", "https://repo.maven.apache.org"),
                of(" https://repo.maven.apache.org ", "temp", "default", "https://repo.maven.apache.org"));
    }

    @ParameterizedTest
    @MethodSource
    void prepareRepositoryTest(String repository, String id, String type, String url) {
        RemoteRepository result = resolverUtil.prepareRemoteRepository(repository, "always");

        assertThat(result).isSameAs(remoteRepository);
        ArgumentCaptor<Repository> captor = ArgumentCaptor.forClass(Repository.class);
        verify(session).createRemoteRepository(captor.capture());
        Repository prepared = captor.getValue();
        assertThat(prepared.getId()).isEqualTo(id);
        assertThat(prepared.getLayout()).isEqualTo(type);
        assertThat(prepared.getUrl()).isEqualTo(url);
        assertThat(prepared.getSnapshots().getUpdatePolicy()).isEqualTo("always");
        assertThat(prepared.getReleases().getUpdatePolicy()).isEqualTo("always");
    }

    @Test
    void prepareRepositoryWithNull() {
        assertThatCode(() -> resolverUtil.prepareRemoteRepository(null, null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("repository must be not null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"groupId:artifactId", "groupId:artifactId:version:packaging:classifier:extra"})
    void createArtifactFromInvalidString(String artifact) {
        ParamArtifact paramArtifact = new ParamArtifact();
        paramArtifact.setArtifact(artifact);

        assertThatCode(() -> resolverUtil.createArtifactFromParams(paramArtifact))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid artifact format: " + artifact);
    }

    @Test
    void createArtifactFromShortCoordinatesUsesSeparatePackagingAndClassifier() {
        prepareTypes();
        ParamArtifact paramArtifact = new ParamArtifact();
        paramArtifact.setArtifact("groupId:artifactId:1.0");
        paramArtifact.setPackaging("test-jar");
        paramArtifact.setClassifier("tests");

        resolverUtil.createArtifactFromParams(paramArtifact);

        verify(typeRegistry).require("test-jar");
        verify(session).createArtifact("groupId", "artifactId", "1.0", "tests", "jar", "test-jar");
    }

    @Test
    void createArtifactFromExplicitCoordinatesOverridesSeparatePackagingAndClassifier() {
        prepareTypes();
        ParamArtifact paramArtifact = new ParamArtifact();
        paramArtifact.setArtifact("groupId:artifactId:1.0:jar:sources");
        paramArtifact.setPackaging("test-jar");
        paramArtifact.setClassifier("tests");

        resolverUtil.createArtifactFromParams(paramArtifact);

        verify(typeRegistry).require("jar");
        verify(session).createArtifact("groupId", "artifactId", "1.0", "sources", "jar", "jar");
    }

    @Test
    void createArtifactFromGavDefaultsToJar() {
        prepareTypes();
        ParamArtifact paramArtifact = new ParamArtifact();
        paramArtifact.setGroupId("groupId");
        paramArtifact.setArtifactId("artifactId");
        paramArtifact.setVersion("1.0");

        resolverUtil.createArtifactFromParams(paramArtifact);

        verify(session).createArtifact("groupId", "artifactId", "1.0", null, "jar", "jar");
    }

    @Test
    void installArtifactKeepsTheTimestampedVersion() {
        Session target = mock(Session.class);
        ProducedArtifact produced = mock(ProducedArtifact.class);
        when(target.createProducedArtifact(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(produced);
        DownloadedArtifact artifact = mock(DownloadedArtifact.class);
        Path path = tempDir.toPath().resolve("artifact-1.0-20121003.035531-117.jar");
        when(artifact.getGroupId()).thenReturn("org.apache.maven.plugins");
        when(artifact.getArtifactId()).thenReturn("artifact");
        when(artifact.getVersion()).thenReturn(DependencyStubs.version("1.0-20121003.035531-117"));
        when(artifact.getClassifier()).thenReturn("");
        when(artifact.getExtension()).thenReturn("jar");
        when(artifact.getPath()).thenReturn(path);

        resolverUtil.installArtifact(artifact, target);

        verify(target)
                .createProducedArtifact(
                        "org.apache.maven.plugins", "artifact", "1.0-20121003.035531-117", "", "jar", null);
        verify(target).setArtifactPath(produced, path);
        verify(target).installArtifacts(produced);
    }

    @Test
    void installArtifactWithAnotherVersion() {
        Session target = mock(Session.class);
        ProducedArtifact produced = mock(ProducedArtifact.class);
        when(target.createProducedArtifact(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(produced);
        DownloadedArtifact artifact = mock(DownloadedArtifact.class);
        Path path = tempDir.toPath().resolve("artifact-1.0-20121003.035531-117.jar");
        when(artifact.getGroupId()).thenReturn("org.apache.maven.plugins");
        when(artifact.getArtifactId()).thenReturn("artifact");
        when(artifact.getClassifier()).thenReturn("");
        when(artifact.getExtension()).thenReturn("jar");
        when(artifact.getPath()).thenReturn(path);

        resolverUtil.installArtifact(artifact, "1.0-SNAPSHOT", target);

        verify(target).createProducedArtifact("org.apache.maven.plugins", "artifact", "1.0-SNAPSHOT", "", "jar", null);
        verify(target).installArtifacts(produced);
    }

    @Test
    void localRepositorySessionUsesTheDirectory() {
        LocalRepository localRepository = mock(LocalRepository.class);
        Session other = mock(Session.class);
        Path alternate = new File(tempDir, "alternate").toPath();
        when(session.createLocalRepository(alternate)).thenReturn(localRepository);
        when(session.withLocalRepository(localRepository)).thenReturn(other);

        assertThat(resolverUtil.localRepositorySession(alternate.toFile())).isSameAs(other);
    }

    @Test
    void localRepositorySessionRequiresDirectory() {
        assertThatCode(() -> resolverUtil.localRepositorySession(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("localRepositoryDirectory");
    }

    private void prepareTypes() {
        when(typeRegistry.require(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            // test-jar is a jar with a classifier, like in the default type registry
            return DependencyStubs.type(id, id.equals("test-jar") ? "jar" : id);
        });
        when(typeRegistry.lookup(anyString())).thenReturn(Optional.<Type>empty());
        when(session.createArtifact(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(mock(Artifact.class));
    }
}
