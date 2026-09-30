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
package org.apache.maven.plugins.dependency.testUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.plugins.dependency.fromConfiguration.ArtifactItem;
import org.apache.maven.plugins.dependency.utils.ResolvedDependency;

/**
 * Creates dependencies, and optionally the files they resolve to. Replaces the {@code ArtifactStubFactory} of the
 * Maven 3 plugin testing harness, whose artifacts have no equivalent in the Maven 4 API.
 */
public class DependencyArtifactStubFactory {
    private File workingDir;

    private boolean createFiles;

    private boolean flattenedPath = true;

    private File srcFile;

    public DependencyArtifactStubFactory(File theWorkingDir, boolean theCreateFiles, boolean flattenedPath) {
        this(theWorkingDir, theCreateFiles);
        this.flattenedPath = flattenedPath;
    }

    /**
     * @param theWorkingDir {@link File}
     * @param theCreateFiles true/false.
     */
    public DependencyArtifactStubFactory(File theWorkingDir, boolean theCreateFiles) {
        this.workingDir = theWorkingDir;
        this.createFiles = theCreateFiles;
    }

    public File getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    public void setCreateFiles(boolean createFiles) {
        this.createFiles = createFiles;
    }

    /**
     * @param srcFile the file the content of created files is copied from
     */
    public void setSrcFile(File srcFile) {
        this.srcFile = srcFile;
    }

    public File getSrcFile() {
        return srcFile;
    }

    public List<ArtifactItem> getArtifactItems(Collection<Dependency> artifacts) {
        List<ArtifactItem> list = new ArrayList<>();
        for (Dependency artifact : artifacts) {
            list.add(new ArtifactItem((ResolvedDependency) artifact));
        }
        return list;
    }

    public ResolvedDependency createArtifact(String groupId, String artifactId, String version) throws IOException {
        return createArtifact(groupId, artifactId, version, "compile");
    }

    public ResolvedDependency createArtifact(String groupId, String artifactId, String version, String scope)
            throws IOException {
        return createArtifact(groupId, artifactId, version, scope, "jar", "");
    }

    public ResolvedDependency createArtifact(
            String groupId, String artifactId, String version, String scope, String type, String classifier)
            throws IOException {
        return createArtifact(groupId, artifactId, version, scope, type, classifier, false);
    }

    public ResolvedDependency createArtifact(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            String classifier,
            boolean optional)
            throws IOException {
        File dir = workingDir;
        if (!flattenedPath) {
            String base = version.replaceFirst("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT");
            dir = new File(workingDir, groupId.replace('.', '/') + '/' + artifactId + '/' + base);
        }
        StringBuilder name = new StringBuilder(artifactId).append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) {
            name.append('-').append(classifier);
        }
        name.append('.').append(type);
        File file = new File(dir, name.toString());
        if (createFiles) {
            Files.createDirectories(dir.toPath());
            if (srcFile != null) {
                Files.copy(srcFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (!file.exists()) {
                Files.write(file.toPath(), "test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return ResolvedDependency.of(
                null,
                DependencyStubs.dependency(groupId, artifactId, version, scope, type, classifier, optional),
                file.toPath());
    }

    public ResolvedDependency getReleaseArtifact() throws IOException {
        return createArtifact("testGroupId", "release", "1.0");
    }

    public ResolvedDependency getSnapshotArtifact() throws IOException {
        return createArtifact("testGroupId", "snapshot", "2.0-SNAPSHOT");
    }

    public Set<Dependency> getReleaseAndSnapshotArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        set.add(getReleaseArtifact());
        set.add(getSnapshotArtifact());
        return set;
    }

    public Set<Dependency> getScopedArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        for (String scope : new String[] {"compile", "provided", "test", "runtime", "system"}) {
            set.add(createArtifact("g", scope, "1.0", scope));
        }
        return set;
    }

    public Set<Dependency> getTypedArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        set.add(createArtifact("g", "a", "1.0", "compile", "war", ""));
        set.add(createArtifact("g", "b", "1.0", "compile", "jar", ""));
        set.add(createArtifact("g", "c", "1.0", "compile", "sources", ""));
        set.add(createArtifact("g", "d", "1.0", "compile", "zip", ""));
        set.add(createArtifact("g", "e", "1.0", "compile", "rar", ""));
        return set;
    }

    public Set<Dependency> getTypedArchiveArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        set.add(createArtifact("g", "a", "1.0", "compile", "war", ""));
        set.add(createArtifact("g", "b", "1.0", "compile", "jar", ""));
        set.add(createArtifact("g", "d", "1.0", "compile", "zip", ""));
        set.add(createArtifact("g", "e", "1.0", "compile", "rar", ""));
        return set;
    }

    public Set<Dependency> getClassifiedArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        set.add(createArtifact("g", "a", "1.0", "compile", "jar", "one"));
        set.add(createArtifact("g", "b", "1.0", "compile", "jar", "two"));
        set.add(createArtifact("g", "c", "1.0", "compile", "jar", "three"));
        set.add(createArtifact("g", "d", "1.0", "compile", "jar", "four"));
        return set;
    }

    public Set<Dependency> getGroupIdArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        for (String id : new String[] {"one", "two", "three", "four"}) {
            set.add(createArtifact(id, "group-" + id, "1.0", "compile", "jar", "a"));
        }
        return set;
    }

    public Set<Dependency> getArtifactArtifacts() throws IOException {
        Set<Dependency> set = new HashSet<>();
        for (String id : new String[] {"one", "two", "three", "four"}) {
            set.add(createArtifact("g", id, "1.0", "compile", "jar", "a"));
        }
        return set;
    }

    public Set<Dependency> getMixedArtifacts() throws IOException {
        Set<Dependency> set = new LinkedHashSet<>(getScopedArtifacts());
        set.addAll(getTypedArtifacts());
        set.addAll(getClassifiedArtifacts());
        return set;
    }
}
