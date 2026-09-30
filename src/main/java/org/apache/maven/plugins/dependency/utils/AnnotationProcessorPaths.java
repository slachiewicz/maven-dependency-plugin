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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Exclusion;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.xml.XmlNode;

/**
 * Reads the {@code annotationProcessorPaths} of the {@code maven-compiler-plugin} from the configuration of build
 * plugins. Maven does not resolve these artifacts as plugin dependencies, so they have to be found in the
 * configuration.
 *
 * <p>Each configuration (the plugin level one and one per execution) yields its own {@link Group}, because the
 * compiler plugin resolves the paths of one configuration together. Exclusions are read as {@code groupId} and
 * {@code artifactId} only; a {@code classifier} or {@code extension} in an exclusion is ignored, so the exclusion
 * matches every classifier and extension.
 */
public final class AnnotationProcessorPaths {

    static final String COMPILER_PLUGIN_GROUP_ID = "org.apache.maven.plugins";

    static final String COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

    private static final String PARAMETER = "annotationProcessorPaths";

    private static final String USE_DEP_MGMT_PARAMETER = "annotationProcessorPathsUseDepMgmt";

    private AnnotationProcessorPaths() {}

    /**
     * The processor paths of one configuration of the compiler plugin.
     */
    public static final class Group {

        private final List<Dependency> paths;

        private final boolean useDependencyManagement;

        private final String key;

        Group(List<Dependency> paths, boolean useDependencyManagement) {
            this.paths = Collections.unmodifiableList(paths);
            this.useDependencyManagement = useDependencyManagement;
            StringBuilder sb = new StringBuilder().append(useDependencyManagement);
            for (Dependency d : paths) {
                sb.append('|').append(key(d));
            }
            this.key = sb.toString();
        }

        /**
         * @return the paths as compile scope dependencies, without duplicates, in configuration order
         */
        public List<Dependency> getPaths() {
            return paths;
        }

        /**
         * @return the value of {@code annotationProcessorPathsUseDepMgmt}: whether the dependency management of the
         *         project applies to the transitive dependencies of the paths
         */
        public boolean isUseDependencyManagement() {
            return useDependencyManagement;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Group && ((Group) o).key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /**
     * Collects the annotation processor paths configured for the {@code maven-compiler-plugin}, one group for the
     * plugin configuration and one for each of its executions that configures paths. Identical groups are returned
     * once.
     *
     * @param plugins the build plugins of the project, from the effective model
     * @param dependencyManagement the dependency management of the project, used for paths without a version; may be
     *                             {@code null}
     * @param warn receives a message for each path that is skipped
     * @return the groups, in configuration order
     */
    public static List<Group> collect(
            Collection<Plugin> plugins, DependencyManagement dependencyManagement, Consumer<String> warn) {
        Set<Group> result = new LinkedHashSet<>();
        for (Plugin plugin : plugins) {
            if (!COMPILER_PLUGIN_GROUP_ID.equals(plugin.getGroupId())
                    || !COMPILER_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
                continue;
            }
            XmlNode pluginConfiguration = plugin.getConfiguration();
            addGroup(result, pluginConfiguration, pluginConfiguration, dependencyManagement, warn);
            for (PluginExecution execution : plugin.getExecutions()) {
                addGroup(result, execution.getConfiguration(), pluginConfiguration, dependencyManagement, warn);
            }
        }
        return new ArrayList<>(result);
    }

    private static void addGroup(
            Set<Group> result,
            XmlNode configuration,
            XmlNode pluginConfiguration,
            DependencyManagement dependencyManagement,
            Consumer<String> warn) {
        if (configuration == null) {
            return;
        }
        XmlNode paths = configuration.getChild(PARAMETER);
        if (paths == null) {
            return;
        }
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        for (XmlNode path : paths.children()) {
            Dependency dependency = parse(path, dependencyManagement, warn);
            if (dependency != null) {
                dependencies.putIfAbsent(key(dependency), dependency);
            }
        }
        if (dependencies.isEmpty()) {
            return;
        }
        // an execution that does not set the flag inherits the one of the plugin configuration
        String flag = value(configuration, USE_DEP_MGMT_PARAMETER);
        if (flag == null && pluginConfiguration != null) {
            flag = value(pluginConfiguration, USE_DEP_MGMT_PARAMETER);
        }
        result.add(new Group(new ArrayList<>(dependencies.values()), Boolean.parseBoolean(flag)));
    }

    private static Dependency parse(XmlNode path, DependencyManagement dependencyManagement, Consumer<String> warn) {
        String groupId = value(path, "groupId");
        String artifactId = value(path, "artifactId");
        String type = value(path, "type");
        Dependency.Builder builder = Dependency.newBuilder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(value(path, "version"))
                .type(type == null ? "jar" : type)
                .classifier(value(path, "classifier"))
                .scope("compile");
        Dependency dependency = builder.build();

        if (groupId == null || artifactId == null) {
            warn.accept("Skipping annotation processor path without groupId or artifactId: " + path);
            return null;
        }
        if (dependency.getVersion() == null) {
            String managedVersion = managedVersion(dependency, dependencyManagement);
            if (managedVersion == null) {
                warn.accept("Skipping annotation processor path " + groupId + ":" + artifactId
                        + ": it has no version and none is managed in dependencyManagement; compilation will fail");
                return null;
            }
            builder.version(managedVersion);
        }
        XmlNode exclusions = path.getChild("exclusions");
        if (exclusions != null) {
            List<Exclusion> parsed = new ArrayList<>();
            for (XmlNode exclusionNode : exclusions.children()) {
                if (!"exclusion".equals(exclusionNode.name())) {
                    continue;
                }
                String exclusionGroupId = value(exclusionNode, "groupId");
                String exclusionArtifactId = value(exclusionNode, "artifactId");
                if (exclusionGroupId != null && exclusionArtifactId != null) {
                    parsed.add(Exclusion.newBuilder()
                            .groupId(exclusionGroupId)
                            .artifactId(exclusionArtifactId)
                            .build());
                }
            }
            builder.exclusions(parsed);
        }
        return builder.build();
    }

    private static String managedVersion(Dependency dependency, DependencyManagement dependencyManagement) {
        if (dependencyManagement == null) {
            return null;
        }
        for (Dependency managed : dependencyManagement.getDependencies()) {
            if (Objects.equals(managed.getGroupId(), dependency.getGroupId())
                    && Objects.equals(managed.getArtifactId(), dependency.getArtifactId())
                    && Objects.equals(managed.getType(), dependency.getType())
                    && Objects.equals(emptyToNull(managed.getClassifier()), emptyToNull(dependency.getClassifier()))) {
                return managed.getVersion();
            }
        }
        return null;
    }

    private static String value(XmlNode node, String name) {
        XmlNode child = node.getChild(name);
        return child == null
                ? null
                : emptyToNull(child.value() == null ? null : child.value().trim());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Identity of a path, including its exclusions. */
    private static String key(Dependency dependency) {
        Set<String> exclusions = new TreeSet<>();
        for (Exclusion e : dependency.getExclusions()) {
            exclusions.add(e.getGroupId() + ":" + e.getArtifactId());
        }
        return dependency.getManagementKey() + ":" + dependency.getVersion() + exclusions;
    }
}
