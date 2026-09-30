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

import org.apache.maven.api.Dependency;
import org.apache.maven.plugins.dependency.testUtils.DependencyStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author brianf
 */
class TestDependencyUtil {

    private static final String TEST_CONTENT =
            "Test line 1" + System.lineSeparator() + "Test line 2" + System.lineSeparator();

    @TempDir
    File temDir;

    Dependency snap;

    Dependency snapResolvedVersion;

    Dependency release;

    Dependency sources;

    @BeforeEach
    void setUp() {
        release = DependencyStubs.dependency("test", "one", "1.1", "compile", "jar", "sources", "jar");
        snap = DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "war", null, "war");
        snapResolvedVersion =
                DependencyStubs.dependency("test", "three", "1.1-20121003.035531-117", "provided", "war", null, "war");
        sources = DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "sources", "sources", "war");
    }

    @Test
    void directoryName() {
        File folder = new File("target/a");
        final Dependency artifact = release;
        File name =
                DependencyUtil.getFormattedOutputDirectory(false, false, false, false, false, false, folder, artifact);
        // object is the same.
        assertEquals(folder, name);

        name = DependencyUtil.getFormattedOutputDirectory(false, false, false, true, false, false, folder, artifact);
        String expectedResult = folder.getAbsolutePath() + File.separatorChar + "test" + File.separatorChar + "one"
                + File.separatorChar + "1.1";
        assertTrue(expectedResult.equalsIgnoreCase(name.getAbsolutePath()));

        name = DependencyUtil.getFormattedOutputDirectory(false, true, false, false, false, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "jars";
        assertTrue(expectedResult.equalsIgnoreCase(name.getAbsolutePath()));

        name = DependencyUtil.getFormattedOutputDirectory(true, false, false, false, false, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "compile";
        assertEquals(expectedResult, name.getAbsolutePath());
        assertTrue(expectedResult.equalsIgnoreCase(name.getAbsolutePath()));

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "one-1.1-sources-jar";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "one-sources-jar";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "one-1.1-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "one-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, false, false, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "jars" + File.separatorChar + "one-1.1-sources-jar";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, true, false, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "jars" + File.separatorChar + "one-sources-jar";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, false, true, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "jars" + File.separatorChar + "one-1.1-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, true, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "jars" + File.separatorChar + "one-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(true, false, true, false, true, false, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "compile" + File.separatorChar + "one-sources-jar";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(true, false, true, false, false, true, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "compile" + File.separatorChar + "one-1.1-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(true, false, true, false, true, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "compile" + File.separatorChar + "one-sources";
        assertEquals(expectedResult, name.getAbsolutePath());
    }

    @Test
    void directoryName2() {
        File folder = new File("target/a");
        final Dependency artifact = snap;
        File name =
                DependencyUtil.getFormattedOutputDirectory(false, false, false, false, false, false, folder, artifact);
        // object is the same.
        assertEquals(folder, name);

        name = DependencyUtil.getFormattedOutputDirectory(false, true, false, false, false, false, folder, artifact);
        String expectedResult = folder.getAbsolutePath() + File.separatorChar + "wars";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, false, true, false, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "test" + File.separatorChar + "two"
                + File.separatorChar + "1.1-SNAPSHOT";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-1.1-SNAPSHOT-war";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, false, false, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "wars" + File.separatorChar + "two-1.1-SNAPSHOT-war";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-war";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-1.1-SNAPSHOT";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, true, false, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "wars" + File.separatorChar + "two-war";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, false, true, folder, artifact);
        expectedResult =
                folder.getAbsolutePath() + File.separatorChar + "wars" + File.separatorChar + "two-1.1-SNAPSHOT";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, true, true, false, true, true, folder, artifact);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "wars" + File.separatorChar + "two";
        assertEquals(expectedResult, name.getAbsolutePath());
    }

    @Test
    void directoryNameSources() {
        File folder = new File("target/a");
        File name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, false, folder, sources);
        String expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, true, true, folder, sources);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, false, folder, sources);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-1.1-SNAPSHOT-sources";
        assertEquals(expectedResult, name.getAbsolutePath());

        name = DependencyUtil.getFormattedOutputDirectory(false, false, true, false, false, true, folder, sources);
        expectedResult = folder.getAbsolutePath() + File.separatorChar + "two-1.1-SNAPSHOT-sources";
        assertEquals(expectedResult, name.getAbsolutePath());
    }

    @Test
    void fileName() {
        Dependency artifact = release;

        String name = DependencyUtil.getFormattedFileName(artifact, false);
        String expectedResult = "one-1.1-sources.jar";
        assertEquals(expectedResult, name);
        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "one-sources.jar";
        assertEquals(expectedResult, name);
    }

    @Test
    void fileNameUseBaseVersion() {
        Dependency artifact = snapResolvedVersion;

        String name = DependencyUtil.getFormattedFileName(artifact, false, false, true);
        String expectedResult = "three-1.1-SNAPSHOT.war";
        assertEquals(expectedResult, name);
        name = DependencyUtil.getFormattedFileName(artifact, false, false, false);
        expectedResult = "three-1.1-20121003.035531-117.war";
        assertEquals(expectedResult, name);
    }

    @Test
    void testJar() {
        Dependency artifact =
                DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "test-jar", null, "jar");

        String name = DependencyUtil.getFormattedFileName(artifact, false);
        String expectedResult = "two-1.1-SNAPSHOT.jar";
        assertEquals(expectedResult, name);
    }

    @Test
    void fileNameClassifier() {
        Dependency artifact =
                DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "jar", "sources", "jar");

        String name = DependencyUtil.getFormattedFileName(artifact, false);
        String expectedResult = "two-1.1-SNAPSHOT-sources.jar";
        assertEquals(expectedResult, name);

        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "two-sources.jar";
        assertEquals(expectedResult, name);

        name = DependencyUtil.getFormattedFileName(artifact, false, false, false, true);
        expectedResult = "two-1.1-SNAPSHOT.jar";
        assertEquals(expectedResult, name);

        artifact = DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "war", "", "war");
        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "two.war";
        assertEquals(expectedResult, name);
    }

    @Test
    void fileNameClassifierWithExtension() {
        // the extension of the artifact is what ends up in the file name
        Dependency artifact =
                DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "war", "sources", "war");

        String name = DependencyUtil.getFormattedFileName(artifact, false);
        String expectedResult = "two-1.1-SNAPSHOT-sources.war";
        assertEquals(expectedResult, name);

        name = DependencyUtil.getFormattedFileName(artifact, false, false, false, true);
        expectedResult = "two-1.1-SNAPSHOT.war";
        assertEquals(expectedResult, name);

        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "two-sources.war";
        assertEquals(expectedResult, name);

        artifact = DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "war", "", "war");
        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "two.war";
        assertEquals(expectedResult, name);

        artifact = DependencyStubs.dependency("test", "two", "1.1-SNAPSHOT", "provided", "war", "", "jar");
        name = DependencyUtil.getFormattedFileName(artifact, true);
        expectedResult = "two.jar";
        assertEquals(expectedResult, name);
    }

    @Test
    void outputFileShouldBeOverridden() throws Exception {
        File file = new File(temDir, "file1.out");
        assertThat(file).doesNotExist();

        DependencyUtil.write(TEST_CONTENT, file, false, "UTF-8");
        assertThat(file).hasContent(TEST_CONTENT);

        DependencyUtil.write(TEST_CONTENT, file, false, "UTF-8");
        assertThat(file).hasContent(TEST_CONTENT);
    }

    @Test
    void outputFileShouldBeAppended() throws Exception {
        File file = new File(temDir, "file2.out");
        assertThat(file).doesNotExist();

        DependencyUtil.write(TEST_CONTENT, file, true, "UTF-8");
        assertThat(file).hasContent(TEST_CONTENT);

        DependencyUtil.write(TEST_CONTENT, file, true, "UTF-8");
        assertThat(file).hasContent(TEST_CONTENT + TEST_CONTENT);
    }
}
