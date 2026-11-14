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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import org.apache.maven.api.Project;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.pom.DependencyEntry;

/**
 * Adds a dependency to the project's {@code pom.xml}.
 * Supports adding to {@code <dependencies>} or {@code <dependencyManagement>},
 * with version inference from managed dependencies.
 *
 * <p>If the dependency already exists, the goal fails with a descriptive error
 * directing the user to remove it first.</p>
 *
 * <p>The goal uses formatting-preserving DOM manipulation to maintain the POM's
 * existing structure (comments, indentation, encoding). Duplicate detection uses
 * type and classifier-aware matching, and cross-references Maven's resolved model
 * to catch dependencies declared via property references.</p>
 *
 * <p>Scope values are validated against Maven's known scopes:
 * {@code compile}, {@code provided}, {@code runtime}, {@code test}, {@code system}, {@code import}.</p>
 *
 * @since 3.11.0
 */
@Mojo(name = "add")
public class AddDependencyMojo extends AbstractDependencyMojo {

    /**
     * Dependency coordinates: {@code groupId:artifactId[:version]}
     * or {@code groupId:artifactId[:extension[:classifier]]:version}.
     * Scope, type, classifier, and optional can be overridden via separate parameters.
     *
     * @since 3.11.0
     */
    @Parameter(property = "gav")
    private String gav;

    /**
     * Dependency scope. Validated against Maven's known scope values:
     * {@code compile}, {@code provided}, {@code runtime}, {@code test}, {@code system}, {@code import}.
     * Invalid values are rejected with a {@code MojoException}.
     *
     * @since 3.11.0
     */
    @Parameter(property = "scope")
    private String scope;

    /**
     * Dependency type/packaging (e.g., {@code jar}, {@code pom}, {@code war}).
     *
     * @since 3.11.0
     */
    @Parameter(property = "type")
    private String type;

    /**
     * Dependency classifier (e.g., {@code sources}, {@code javadoc}, {@code tests}).
     *
     * @since 3.11.0
     */
    @Parameter(property = "classifier")
    private String classifier;

    /**
     * Whether the dependency is optional.
     *
     * @since 3.11.0
     */
    @Parameter(property = "optional")
    private Boolean optional;

    /**
     * When {@code true}, insert into {@code <dependencyManagement>} instead of {@code <dependencies>}.
     *
     * @since 3.11.0
     */
    @Parameter(property = "managed", defaultValue = "false")
    private boolean managed;

    /**
     * When {@code true} (the default), automatically detect and follow the project's existing
     * dependency management conventions:
     * <ul>
     *   <li>If most existing dependencies are version-less, add managed dependency to parent POM</li>
     *   <li>If versions use {@code ${...}} property references, create a version property</li>
     *   <li>Property naming follows the detected pattern (e.g., {@code .version}, {@code -version})</li>
     * </ul>
     * Explicit parameters ({@code managed}, {@code useProperty}, {@code propertyName}) override
     * detected conventions.
     *
     * @since 3.11.0
     */
    @Parameter(property = "align", defaultValue = "true")
    private boolean align;

    /**
     * When set, controls whether a version property is created in {@code <properties>}.
     * When {@code null} (the default), the behavior is auto-detected from existing conventions
     * if {@code align=true}.
     *
     * @since 3.11.0
     */
    @Parameter(property = "useProperty")
    private Boolean useProperty;

    /**
     * Explicit property name for the version (e.g., {@code guava.version}).
     * When not set, the name is derived from the detected naming convention.
     *
     * @since 3.11.0
     */
    @Parameter(property = "propertyName")
    private String propertyName;

    /**
     * Target a specific Maven profile by its {@code <id>}. When set, the dependency is added
     * to the profile's {@code <dependencies>} or {@code <dependencyManagement>} section.
     * The profile must already exist in the POM.
     *
     * @since 3.11.0
     */
    @Parameter(property = "profile")
    private String profile;

    @Override
    protected void doExecute() throws MojoException {
        DependencyEntry coords = resolveCoordinates();

        Project targetProject = getProject();
        boolean effectiveManaged = managed;

        // Convention auto-detection when align=true
        Conventions conventions = null;
        if (align && coords.getVersion() != null) {
            conventions = detectConventions(targetProject);
            if (!managed) {
                effectiveManaged = conventions.useManaged;
            }
        }

        // Validate version requirements
        if (effectiveManaged && coords.getVersion() == null) {
            throw new MojoException("Version is required when adding to <dependencyManagement>."
                    + " Include version in -Dgav (e.g. groupId:artifactId:version)");
        }

        // Version inference for non-managed dependencies
        if (!effectiveManaged && coords.getVersion() == null) {
            String managedVersion = findManagedVersion(targetProject, coords.getGroupId(), coords.getArtifactId());
            if (managedVersion != null) {
                getLog().info("Version managed by parent: " + coords.getGroupId() + ":" + coords.getArtifactId() + ":"
                        + managedVersion);
            } else {
                throw new MojoException("No version specified and no managed version found for "
                        + coords.getGroupId() + ":" + coords.getArtifactId()
                        + ". Include version in -Dgav (e.g. groupId:artifactId:version)");
            }
        }

        // Determine effective property usage
        boolean effectiveUseProperty = false;
        String effectivePropertyName = null;
        if (conventions != null && coords.getVersion() != null) {
            effectiveUseProperty = useProperty != null ? useProperty : conventions.useProperty;
            if (effectiveUseProperty) {
                effectivePropertyName = propertyName != null ? propertyName : conventions.derivePropertyName(coords);
            }
        }

        // Determine the target POM for managed deps
        Path managedPomFile = null;
        if (effectiveManaged && conventions != null && conventions.managedPomFile != null) {
            managedPomFile = conventions.managedPomFile;
        }

        // Cross-POM mode: add managed dep + property to parent, version-less dep to child
        Path pomFile = targetProject.getPomPath();
        if (pomFile == null) {
            throw new MojoException("Cannot add dependency: project has no POM file to modify.");
        }

        boolean crossPom = effectiveManaged
                && managedPomFile != null
                && !managedPomFile.toAbsolutePath().equals(pomFile.toAbsolutePath());

        try {
            if (crossPom) {
                addCrossPom(coords, pomFile, managedPomFile, effectiveUseProperty, effectivePropertyName);
            } else {
                addSinglePom(
                        coords, targetProject, pomFile, effectiveManaged, effectiveUseProperty, effectivePropertyName);
            }
        } catch (IOException e) {
            throw new MojoException("Failed to modify POM file: " + pomFile, e);
        }
    }

    /**
     * Cross-POM mode: adds a managed dependency (with optional property) to the parent POM,
     * and a version-less dependency reference to the child POM.
     */
    private void addCrossPom(
            DependencyEntry coords,
            Path childPomFile,
            Path parentPomFile,
            boolean effectiveUseProperty,
            String effectivePropertyName)
            throws IOException, MojoException {
        // 1. Modify parent POM: add property + managed dependency
        PomEditor parentEditor = loadPomEditor(parentPomFile);
        Element existingManaged = findDependency(
                parentEditor,
                null,
                coords.getGroupId(),
                coords.getArtifactId(),
                coords.getType(),
                coords.getClassifier(),
                true);
        if (existingManaged != null) {
            throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                    + " already exists in " + parentPomFile.getFileName() + " <dependencyManagement>."
                    + " Remove it first with dependency:remove, then re-add.");
        }

        String versionRef = coords.getVersion();
        if (effectiveUseProperty && effectivePropertyName != null) {
            parentEditor.properties().updateProperty(true, effectivePropertyName, coords.getVersion());
            versionRef = "${" + effectivePropertyName + "}";
            getLog().info("Added property " + effectivePropertyName + "=" + coords.getVersion() + " to "
                    + parentPomFile.getFileName());
        }

        DependencyEntry managedCoords = new DependencyEntry(coords.getGroupId(), coords.getArtifactId());
        managedCoords.setVersion(versionRef);
        if (coords.getType() != null && !coords.getType().isEmpty()) {
            managedCoords.setType(coords.getType());
        }
        if (coords.getClassifier() != null && !coords.getClassifier().isEmpty()) {
            managedCoords.setClassifier(coords.getClassifier());
        }
        addDependency(parentEditor, parentPomFile, null, managedCoords, true);
        savePomEditor(parentEditor, parentPomFile);
        getLog().info("Added managed dependency " + coords.getGroupId() + ":" + coords.getArtifactId() + ":"
                + versionRef + " to " + parentPomFile.getFileName());

        // 2. Modify child POM: add version-less dependency
        PomEditor childEditor = loadPomEditor(childPomFile);
        Element existingChild = findDependency(
                childEditor,
                profile,
                coords.getGroupId(),
                coords.getArtifactId(),
                coords.getType(),
                coords.getClassifier(),
                false);
        if (existingChild != null) {
            throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                    + " already exists in " + childPomFile.getFileName()
                    + ". Remove it first with dependency:remove, then re-add.");
        } else if (existsInResolvedModel(getProject(), coords, false)) {
            throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                    + " already exists in the POM (using property references). "
                    + "Cannot safely add or update automatically. Please edit the POM manually.");
        }

        DependencyEntry childCoords = new DependencyEntry(coords.getGroupId(), coords.getArtifactId());
        if (coords.getScope() != null && !coords.getScope().isEmpty()) {
            childCoords.setScope(coords.getScope());
        }
        if (coords.getType() != null && !coords.getType().isEmpty()) {
            childCoords.setType(coords.getType());
        }
        if (coords.getClassifier() != null && !coords.getClassifier().isEmpty()) {
            childCoords.setClassifier(coords.getClassifier());
        }
        if (coords.getOptional() != null) {
            childCoords.setOptional(coords.getOptional());
        }
        addDependency(childEditor, childPomFile, profile, childCoords, false);
        savePomEditor(childEditor, childPomFile);
        getLog().info("Added version-less dependency " + coords.getGroupId() + ":" + coords.getArtifactId() + " to "
                + childPomFile.getFileName());
    }

    /**
     * Single-POM mode: adds the dependency (optionally with a version property) to the current POM.
     */
    private void addSinglePom(
            DependencyEntry coords,
            Project targetProject,
            Path pomFile,
            boolean targetManaged,
            boolean effectiveUseProperty,
            String effectivePropertyName)
            throws IOException, MojoException {

        // Warn when adding to parent <dependencies> (not managed) in a multi-module project
        if (!targetManaged && !targetProject.getModel().getModules().isEmpty()) {
            getLog().warn("Adding dependency to parent POM — this will be inherited by all child modules. "
                    + "Use -Dmanaged to add to <dependencyManagement> instead.");
        }

        PomEditor editor = loadPomEditor(pomFile);
        Element existing = findDependency(
                editor,
                profile,
                coords.getGroupId(),
                coords.getArtifactId(),
                coords.getType(),
                coords.getClassifier(),
                targetManaged);

        if (existing != null) {
            throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                    + " already exists in " + pomFile.getFileName()
                    + ". Remove it first with dependency:remove, then re-add.");
        } else if (existsInResolvedModel(targetProject, coords, targetManaged)) {
            throw new MojoException("Dependency " + coords.getGroupId() + ":" + coords.getArtifactId()
                    + " already exists in the POM (using property references). "
                    + "Cannot safely add or update automatically. Please edit the POM manually.");
        } else {
            // Handle version property
            if (effectiveUseProperty && effectivePropertyName != null && coords.getVersion() != null) {
                editor.properties().updateProperty(true, effectivePropertyName, coords.getVersion());
                getLog().info("Added property " + effectivePropertyName + "=" + coords.getVersion());
                coords.setVersion("${" + effectivePropertyName + "}");
            }
            addDependency(editor, pomFile, profile, coords, targetManaged);
            getLog().info("Added dependency " + coords + " to " + pomFile.getFileName());
        }

        savePomEditor(editor, pomFile);
    }

    private DependencyEntry resolveCoordinates() throws MojoException {
        if (gav == null || gav.isEmpty()) {
            throw new MojoException("You must specify -Dgav=groupId:artifactId[:version]");
        }

        DependencyEntry coords;
        try {
            coords = DependencyEntry.parse(gav);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        // Explicit parameters override GAV shorthand values
        if (scope != null) {
            coords.setScope(scope);
        }
        if (type != null) {
            coords.setType(type);
        }
        if (classifier != null) {
            coords.setClassifier(classifier);
        }

        if (optional != null) {
            coords.setOptional(optional);
        }

        try {
            coords.validate();
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        return coords;
    }

    private String findManagedVersion(Project project, String groupId, String artifactId) {
        Optional<Project> current = Optional.of(project);
        while (current.isPresent()) {
            DependencyManagement depMgmt = current.get().getModel().getDependencyManagement();
            if (depMgmt != null) {
                for (Dependency dep : depMgmt.getDependencies()) {
                    if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                        return dep.getVersion();
                    }
                }
            }
            current = current.get().getParent();
        }
        return null;
    }

    private void addDependency(
            PomEditor editor, Path pomFile, String profileId, DependencyEntry coords, boolean managed)
            throws MojoException {
        PomEditor.Dependencies dependencies = dependenciesFor(editor, pomFile, profileId);
        Coordinates coordinates = Coordinates.of(
                coords.getGroupId(),
                coords.getArtifactId(),
                coords.getVersion(),
                coords.getClassifier(),
                coords.getType());

        if (managed) {
            dependencies.updateManagedDependency(true, coordinates);
        } else {
            dependencies.updateDependency(true, coordinates);
        }

        Element dependency = findDependency(
                editor,
                profileId,
                coords.getGroupId(),
                coords.getArtifactId(),
                coords.getType(),
                coords.getClassifier(),
                managed);
        if (dependency != null) {
            if (coords.getScope() != null && !coords.getScope().isEmpty()) {
                editor.updateOrCreateChildElement(dependency, "scope", coords.getScope());
            }
            if (coords.getOptional() != null && coords.getOptional()) {
                editor.updateOrCreateChildElement(dependency, "optional", "true");
            }
        }
    }

    private PomEditor.Dependencies dependenciesFor(PomEditor editor, Path pomFile, String profileId)
            throws MojoException {
        PomEditor.Dependencies dependencies = editor.dependencies();
        if (profileId == null || profileId.isEmpty()) {
            return dependencies;
        }
        Element profileElement = editor.profiles().findProfile(profileId);
        if (profileElement == null) {
            throw new MojoException("Profile '" + profileId + "' not found in " + pomFile.getFileName() + ".");
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

    private static Element findDependency(
            PomEditor editor,
            String profileId,
            String groupId,
            String artifactId,
            String type,
            String classifier,
            boolean managed) {
        if (groupId == null || artifactId == null) {
            throw new IllegalArgumentException("groupId and artifactId must not be null");
        }
        Element depsElement = getDependenciesElement(editor, profileId, managed);
        if (depsElement == null) {
            return null;
        }

        Coordinates coordinates = Coordinates.of(groupId, artifactId, null, classifier, type);
        return depsElement
                .childElements("dependency")
                .filter(coordinates.predicateGATC())
                .findFirst()
                .orElse(null);
    }

    private static Element getDependenciesElement(PomEditor editor, String profileId, boolean managed) {
        Element context = editor.root();
        if (profileId != null && !profileId.isEmpty()) {
            context = editor.profiles().findProfile(profileId);
            if (context == null) {
                return null;
            }
        }

        if (managed) {
            Element depMgmt = editor.findChildElement(context, "dependencyManagement");
            return depMgmt != null ? editor.findChildElement(depMgmt, "dependencies") : null;
        }
        return editor.findChildElement(context, "dependencies");
    }

    private static List<String> getDependencyVersions(PomEditor editor, boolean managed) {
        List<String> versions = new ArrayList<>();
        Element depsElement = getDependenciesElement(editor, null, managed);
        if (depsElement == null) {
            return versions;
        }
        depsElement.childElements("dependency").forEach(dep -> {
            String version = dep.childTextOr("version", null);
            if (version != null && !version.isEmpty()) {
                versions.add(version);
            }
        });
        return versions;
    }

    private static long getDependencyCount(PomEditor editor, boolean managed) {
        Element depsElement = getDependenciesElement(editor, null, managed);
        return depsElement != null ? depsElement.childElements("dependency").count() : 0;
    }

    // --- Convention detection ---

    /**
     * Detects the project's dependency management conventions by analyzing existing dependencies.
     */
    private Conventions detectConventions(Project project) {
        Conventions conv = new Conventions();

        Path pomFile = project.getPomPath();
        if (pomFile == null) {
            return conv;
        }

        try {
            PomEditor editor = loadPomEditor(pomFile);

            // Find the parent POM that declares <dependencyManagement>, if any.
            conv.managedPomFile = findManagedDepsPom(project);

            // Analyze managed dependency usage: count deps with/without version
            long totalDeps = getDependencyCount(editor, false);
            List<String> depVersions = getDependencyVersions(editor, false);
            long depsWithVersion = depVersions.size();

            // Only treat version-less dependencies as a "managed" convention when there is a
            // separate parent POM to host the managed dependency. Otherwise (e.g. a single,
            // leaf POM whose dependency versions come from a local BOM import), a versioned add
            // belongs in <dependencies>, not in this POM's <dependencyManagement>.
            if (conv.managedPomFile != null && totalDeps > 0 && depsWithVersion < totalDeps / 2.0) {
                // Majority of deps are version-less and a parent manages versions → use managed deps
                conv.useManaged = true;
                getLog().debug("Convention detected: majority of dependencies are version-less"
                        + " and a parent POM manages versions (useManaged=true)");
            }

            // Analyze property patterns from child POM versions
            List<String> allVersions = new java.util.ArrayList<>(depVersions);
            allVersions.addAll(getDependencyVersions(editor, true));

            // If child has mostly version-less deps, also scan the parent POM for property patterns
            if (conv.managedPomFile != null
                    && !conv.managedPomFile.toAbsolutePath().equals(pomFile.toAbsolutePath())) {
                PomEditor parentEditor = loadPomEditor(conv.managedPomFile);
                allVersions.addAll(getDependencyVersions(parentEditor, true));
                allVersions.addAll(getDependencyVersions(parentEditor, false));
            }

            // Count property references vs literal versions
            long propertyRefs = allVersions.stream()
                    .filter(v -> v.startsWith("${") && v.endsWith("}"))
                    .count();
            if (!allVersions.isEmpty() && propertyRefs > allVersions.size() / 2.0) {
                conv.useProperty = true;
                getLog().debug("Convention detected: majority of versions use property references (useProperty=true)");

                // Detect property naming pattern
                conv.pattern = detectPropertyPattern(allVersions);
                if (conv.pattern != null) {
                    getLog().debug("Convention detected: property naming pattern=" + conv.pattern);
                }
            }
        } catch (IOException e) {
            getLog().debug("Could not analyze POM conventions: " + e.getMessage());
        }

        return conv;
    }

    /**
     * Walks the parent chain to find the nearest POM that declares {@code <dependencyManagement>}.
     */
    private Path findManagedDepsPom(Project project) {
        Optional<Project> current = project.getParent();
        while (current.isPresent()) {
            Path pf = current.get().getPomPath();
            if (pf != null && declaresManagedDependencies(pf)) {
                return pf;
            }
            current = current.get().getParent();
        }
        return null;
    }

    private static final Pattern PROPERTY_REF = Pattern.compile("^\\$\\{(.+)}$");

    /**
     * Property naming conventions detected in existing POM files.
     */
    enum PropertyPattern {
        /** {@code artifactId.version} (e.g., {@code guava.version}) */
        DOT_VERSION {
            @Override
            String toPropertyName(String artifactId) {
                return artifactId + ".version";
            }

            @Override
            boolean matches(String propName) {
                return propName.endsWith(".version");
            }
        },
        /** {@code artifactId-version} (e.g., {@code guava-version}) */
        DASH_VERSION {
            @Override
            String toPropertyName(String artifactId) {
                return artifactId + "-version";
            }

            @Override
            boolean matches(String propName) {
                return propName.endsWith("-version");
            }
        },
        /** {@code artifactIdVersion} (camelCase, e.g., {@code guavaVersion}) */
        CAMEL_VERSION {
            @Override
            String toPropertyName(String artifactId) {
                return artifactId + "Version";
            }

            @Override
            boolean matches(String propName) {
                return propName.endsWith("Version");
            }
        },
        /** {@code version.artifactId} (prefix, e.g., {@code version.guava}) */
        VERSION_PREFIX {
            @Override
            String toPropertyName(String artifactId) {
                return "version." + artifactId;
            }

            @Override
            boolean matches(String propName) {
                return propName.startsWith("version.");
            }
        };

        abstract String toPropertyName(String artifactId);

        abstract boolean matches(String propName);
    }

    /**
     * Analyzes property reference names to detect the naming convention.
     *
     * @return the most common {@link PropertyPattern}, or {@code null} if no clear pattern is found
     */
    static PropertyPattern detectPropertyPattern(List<String> versions) {
        Map<PropertyPattern, Integer> patternCounts = new HashMap<>();
        for (String version : versions) {
            Matcher m = PROPERTY_REF.matcher(version);
            if (m.matches()) {
                String propName = m.group(1);
                for (PropertyPattern pp : PropertyPattern.values()) {
                    if (pp.matches(propName)) {
                        patternCounts.merge(pp, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        if (patternCounts.isEmpty()) {
            return null;
        }
        return patternCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Holds the detected conventions for the project.
     */
    static class Conventions {
        boolean useManaged;
        boolean useProperty;
        PropertyPattern pattern;
        Path managedPomFile;

        /**
         * Derives a property name for the given dependency based on the detected pattern.
         */
        String derivePropertyName(DependencyEntry coords) {
            PropertyPattern effective = pattern != null ? pattern : PropertyPattern.DOT_VERSION;
            return effective.toPropertyName(coords.getArtifactId());
        }
    }
}
