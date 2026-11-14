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

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.maven.PomEditor;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.pom.DependencyEntry;
import org.apache.maven.plugins.dependency.utils.DependencySilentLog;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public abstract class AbstractDependencyMojo implements Mojo {

    /**
     * The Maven session.
     */
    @Inject
    protected Session session;

    @Inject
    private Log log;

    /**
     * POM, {@code null} if the goal is run outside of a project.
     */
    @Inject
    @Nullable
    private Project project;

    /**
     * If the plugin should be silent.
     *
     * @since 2.0
     * @deprecated to be removed in 3.13.0. Since 3.8.1 the copy and unpack goals log each artifact at debug
     *             level, so remove this parameter from the configuration
     */
    @Deprecated
    @Parameter(property = "silent", defaultValue = "false")
    private boolean silent;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Skip plugin execution only during incremental builds (e.g. triggered from M2E). The Maven 4 API has no
     * {@code BuildContext}, so this flag is accepted for compatibility but has no effect.
     *
     * @see #skip
     * @since 3.4.0
     */
    @Parameter(defaultValue = "false")
    private boolean skipDuringIncrementalBuild;

    // Mojo methods -----------------------------------------------------------

    @Override
    public final void execute() throws MojoException {
        if (isSkip()) {
            getLog().info("Skipping plugin execution");
            return;
        }

        doExecute();
    }

    /**
     * @throws MojoException in case of an error
     */
    protected abstract void doExecute() throws MojoException;

    /**
     * @return returns the project, or {@code null} if the goal is run outside of a project
     */
    public Project getProject() {
        return this.project;
    }

    /**
     * @return the session
     */
    public Session getSession() {
        return session;
    }

    /**
     * @return the helper used to resolve artifacts and dependencies
     */
    protected ResolverUtil getResolverUtil() {
        if (resolverUtil == null) {
            resolverUtil = new ResolverUtil(session, project);
        }
        return resolverUtil;
    }

    /**
     * @return the log, which discards everything if {@link #silent} is set
     */
    public Log getLog() {
        return silent ? SILENT : log;
    }

    private ResolverUtil resolverUtil;

    private static final Log SILENT = new DependencySilentLog();

    /**
     * @return {@link #skip}
     */
    public boolean isSkip() {
        return skip;
    }

    /**
     * @param skip {@link #skip}
     */
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    /**
     * @return {@link #silent}
     * @deprecated to be removed in 3.13.0
     */
    @Deprecated
    protected final boolean isSilent() {
        return silent;
    }

    /**
     * @param silent {@link #silent}
     * @deprecated to be removed in 3.13.0; no API replacement
     */
    @Deprecated
    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Checks whether the POM declares at least one managed dependency of its own. The raw file is read because the
     * model of a {@link Project} is the effective model, which also holds what the POM inherits.
     *
     * @param pom the POM file
     * @return {@code true} if {@code dependencyManagement} of the POM itself has dependencies
     */
    protected static boolean declaresManagedDependencies(Path pom) {
        try {
            PomEditor editor = new PomEditor(Document.of(pom));
            Element depMgmt = editor.findChildElement(editor.root(), "dependencyManagement");
            Element deps = depMgmt != null ? editor.findChildElement(depMgmt, "dependencies") : null;
            return deps != null && deps.childElements("dependency").findAny().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks whether the POM of the project itself declares the dependency, resolving property references such as
     * {@code ${project.groupId}} that a literal comparison of the file misses. Inherited dependencies do not count,
     * so a dependency of the parent does not block adding it to the child.
     *
     * @param project the project
     * @param coords the dependency to look for
     * @param managed whether to look in {@code dependencyManagement}
     * @return {@code true} if the dependency is declared in the POM of the project
     */
    protected static boolean existsInResolvedModel(Project project, DependencyEntry coords, boolean managed) {
        Path pom = project.getPomPath();
        if (pom == null) {
            return false;
        }
        Element deps;
        try {
            PomEditor editor = new PomEditor(Document.of(pom));
            Element context = editor.root();
            if (managed) {
                Element depMgmt = editor.findChildElement(context, "dependencyManagement");
                context = depMgmt;
            }
            deps = context != null ? editor.findChildElement(context, "dependencies") : null;
            if (deps == null) {
                return false;
            }
            String searchType = isEmpty(coords.getType()) ? "jar" : coords.getType();
            String searchClassifier = isEmpty(coords.getClassifier()) ? "" : coords.getClassifier();
            return deps.childElements("dependency").anyMatch(dep -> {
                String type = interpolate(project, dep.childTextOr("type", "jar"));
                String classifier = interpolate(project, dep.childTextOr("classifier", ""));
                return coords.getGroupId().equals(interpolate(project, dep.childTextOr("groupId", null)))
                        && coords.getArtifactId().equals(interpolate(project, dep.childTextOr("artifactId", null)))
                        && searchType.equals(type)
                        && searchClassifier.equals(classifier);
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String interpolate(Project project, String value) {
        String result = value;
        for (int i = 0; i < 10 && result != null && result.contains("${"); i++) {
            Matcher matcher = PROPERTY_REFERENCE.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String replacement = lookup(project, matcher.group(1));
                matcher.appendReplacement(
                        sb, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }
            matcher.appendTail(sb);
            if (sb.toString().equals(result)) {
                break;
            }
            result = sb.toString();
        }
        return result;
    }

    private static String lookup(Project project, String key) {
        switch (key) {
            case "project.groupId":
            case "pom.groupId":
                return project.getGroupId();
            case "project.artifactId":
            case "pom.artifactId":
                return project.getArtifactId();
            case "project.version":
            case "pom.version":
                return project.getVersion();
            default:
                return project.getModel().getProperties().get(key);
        }
    }
}
