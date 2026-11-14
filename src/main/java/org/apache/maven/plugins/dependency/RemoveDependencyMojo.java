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
package org.apache.maven.plugins.dependency;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import org.apache.maven.api.Project;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.pom.DependencyEntry;

/**
 * Removes a dependency from the project's {@code pom.xml}.
 * Supports removing from {@code <dependencies>} or {@code <dependencyManagement>}.
 *
 * <p>Matching uses groupId, artifactId, type, and classifier for precision.
 * If the dependency exists in Maven's resolved model but uses property references
 * in the raw POM, a clear error directs the user to edit manually.</p>
 * When removing a managed dependency from a parent POM, warns if child modules
 * reference it without an explicit version.
 *
 * @since 3.11.0
 */
@Mojo(name = "remove")
public class RemoveDependencyMojo extends AbstractDependencyMojo {

    /**
     * Dependency coordinates: {@code groupId:artifactId[:version]}
     * or {@code groupId:artifactId[:extension[:classifier]]:version}.
     * Only groupId and artifactId are required. Type and classifier, if provided,
     * are used for precise matching when multiple dependency variants exist
     * (e.g., jar vs test-jar).
     *
     * @since 3.11.0
     */
    @Parameter(property = "gav")
    private String gav;

    /**
     * When {@code true}, remove from {@code <dependencyManagement>} instead of {@code <dependencies>}.
     *
     * @since 3.11.0
     */
    @Parameter(property = "managed", defaultValue = "false")
    private boolean managed;

    /**
     * Dependency type for precise matching (e.g., {@code pom}, {@code war}, {@code test-jar}).
     * When not specified, defaults to {@code "jar"}.
     *
     * @since 3.11.0
     */
    @Parameter(property = "type")
    private String type;

    /**
     * Dependency classifier for precise matching (e.g., {@code sources}, {@code javadoc}, {@code tests}).
     *
     * @since 3.11.0
     */
    @Parameter(property = "classifier")
    private String classifier;

    /**
     * Target a specific Maven profile by its {@code <id>}. When set, the dependency is removed
     * from the profile's {@code <dependencies>} or {@code <dependencyManagement>} section.
     * The profile must already exist in the POM.
     *
     * @since 3.11.0
     */
    @Parameter(property = "profile")
    private String profile;

    @Override
    protected void doExecute() throws MojoException {
        DependencyEntry coords = resolveCoordinates();

        boolean targetManaged = managed;
        Project targetProject = getProject();
        Path pomFile = targetProject.getPomPath();
        if (pomFile == null) {
            throw new MojoException("Cannot remove dependency: project has no POM file to modify.");
        }

        // Safety check for managed dependency removal in parent POM
        if (targetManaged && !targetProject.getModel().getModules().isEmpty()) {
            checkChildModuleDependencies(targetProject, coords.getGroupId(), coords.getArtifactId());
        }

        try {
            PomEditor editor = loadPomEditor(pomFile);
            PomEditor.Dependencies dependencies = dependenciesFor(editor, pomFile);
            Coordinates coordinates = Coordinates.of(
                    coords.getGroupId(), coords.getArtifactId(), null, coords.getClassifier(), coords.getType());
            boolean removed = targetManaged
                    ? dependencies.deleteManagedDependency(coordinates)
                    : dependencies.deleteDependency(coordinates);

            if (!removed) {
                // Cross-reference with resolved model to detect property-interpolated coords
                if (existsInResolvedModel(targetProject, coords, targetManaged)) {
                    String section = targetManaged ? "<dependencyManagement>" : "<dependencies>";
                    throw new MojoException("Dependency " + coords.getGroupId() + ":"
                            + coords.getArtifactId()
                            + " exists in " + section + " but uses property references in the POM. "
                            + "Please remove it manually.");
                }
                String section = targetManaged ? "<dependencyManagement>" : "<dependencies>";
                throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                        + " not found in " + section + ".");
            }

            savePomEditor(editor, pomFile);

            getLog().info("Removed dependency " + coords.getGroupId() + ":" + coords.getArtifactId() + " from "
                    + pomFile.getFileName());
        } catch (IOException e) {
            throw new MojoException("Failed to modify POM file: " + pomFile, e);
        }
    }

    private PomEditor.Dependencies dependenciesFor(PomEditor editor, Path pomFile) throws MojoException {
        PomEditor.Dependencies dependencies = editor.dependencies();
        if (profile == null || profile.isEmpty()) {
            return dependencies;
        }
        Element profileElement = editor.profiles().findProfile(profile);
        if (profileElement == null) {
            throw new MojoException("Profile '" + profile + "' not found in " + pomFile.getFileName() + ".");
        }
        return dependencies.forProfile(profileElement);
    }

    private static PomEditor loadPomEditor(Path pomFile) throws IOException {
        try {
            String content = new String(Files.readAllBytes(pomFile), StandardCharsets.UTF_8);
            String upper = content.toUpperCase(Locale.ROOT);
            if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
                throw new IOException("DOCTYPE/ENTITY declarations are not allowed in POM files (security risk)");
            }

            PomEditor editor = new PomEditor(Document.of(pomFile));
            String rootName = editor.root().name();
            if (!"project".equals(rootName)) {
                throw new IOException(
                        "Not a valid POM file: expected <project> root element but found <" + rootName + ">");
            }
            return editor;
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse POM file: " + pomFile, e);
        }
    }

    private static void savePomEditor(PomEditor editor, Path pomFile) throws IOException {
        Path tempFile = Files.createTempFile(pomFile.toAbsolutePath().getParent(), "pom", ".xml.tmp");
        boolean success = false;
        try {
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                editor.document().toXml(os);
            }
            Files.move(tempFile, pomFile, StandardCopyOption.REPLACE_EXISTING);
            success = true;
        } finally {
            if (!success) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private DependencyEntry resolveCoordinates() throws MojoException {
        if (gav == null || gav.isEmpty()) {
            throw new MojoException("You must specify -Dgav=groupId:artifactId");
        }

        DependencyEntry coords;
        try {
            coords = DependencyEntry.parse(gav);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        // Explicit parameters override GAV shorthand values
        if (type != null) {
            coords.setType(type);
        }
        if (classifier != null) {
            coords.setClassifier(classifier);
        }

        try {
            coords.validate();
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        return coords;
    }

    private void checkChildModuleDependencies(Project parentProject, String depGroupId, String depArtifactId) {
        if (parentProject.getBasedir() == null) {
            getLog().debug("Parent project basedir is null, skipping child module dependency check");
            return;
        }
        StringBuilder affected = new StringBuilder();

        for (String moduleName : parentProject.getModel().getModules()) {
            Path modulePom = resolveModulePom(parentProject.getBasedir(), moduleName);
            if (!Files.exists(modulePom)) {
                continue;
            }
            try {
                PomEditor editor = loadPomEditor(modulePom);
                Element dependencies = editor.findChildElement(editor.root(), "dependencies");
                if (dependencies != null
                        && dependencies
                                .childElements("dependency")
                                .anyMatch(dep -> depGroupId.equals(dep.childTextOr("groupId", null))
                                        && depArtifactId.equals(dep.childTextOr("artifactId", null))
                                        && dep.childTextOr("version", "").isEmpty())) {
                    if (affected.length() > 0) {
                        affected.append(", ");
                    }
                    affected.append(moduleName);
                }
            } catch (IOException e) {
                getLog().debug("Could not read module POM: " + modulePom + " - " + e.getMessage());
            }
        }

        if (affected.length() > 0) {
            getLog().warn("The following child modules depend on " + depGroupId + ":" + depArtifactId
                    + " without an explicit version and will break: [" + affected + "]. Proceeding with removal.");
        }
    }

    private static Path resolveModulePom(Path parentBasedir, String moduleName) {
        Path modulePath = parentBasedir.resolve(moduleName);
        if (Files.isRegularFile(modulePath) || moduleName.endsWith(".xml")) {
            return modulePath;
        }
        return modulePath.resolve("pom.xml");
    }
}
