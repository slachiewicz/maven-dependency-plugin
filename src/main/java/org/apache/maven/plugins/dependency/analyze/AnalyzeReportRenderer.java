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
package org.apache.maven.plugins.dependency.analyze;

import java.util.Collection;
import java.util.Locale;
import java.util.ResourceBundle;

import org.apache.maven.api.Dependency;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;

/**
 * This is the view part of the analyze-report mojo. It generates the HTML report for the project website. The HTML
 * output is same as the CLI output.
 */
public class AnalyzeReportRenderer extends AbstractMavenReportRenderer {
    private final ResourceBundle bundle;

    private final Locale locale;

    private final ProjectDependencyAnalysis analysis;

    public AnalyzeReportRenderer(Sink sink, Locale locale, ProjectDependencyAnalysis analysis) {
        super(sink);
        this.bundle = ResourceBundle.getBundle("analyze-report", locale);
        this.locale = locale;
        this.analysis = analysis;
    }

    @Override
    public String getTitle() {
        return getI18nString("title");
    }

    /**
     * @param key the key
     * @return the translated string
     */
    private String getI18nString(String key) {
        return bundle.getString("report.analyze." + key);
    }

    protected void renderBody() {
        startSection(getTitle());

        boolean reported = false;

        // Generate Used Declared dependencies:
        if (!analysis.getUsedDeclaredArtifacts().isEmpty()) {
            startSection(getI18nString("UsedDeclaredDependencies"));
            renderDependenciesTable(sink, analysis.getUsedDeclaredArtifacts());
            endSection();
            reported = true;
        }

        // Generate Used Undeclared dependencies:

        if (!analysis.getUsedUndeclaredArtifacts().isEmpty()) {
            startSection(getI18nString("UsedUndeclaredDependencies"));
            renderDependenciesTable(sink, analysis.getUsedUndeclaredArtifacts());
            endSection();
            reported = true;
        }

        // Generate Unused declared dependencies:
        if (!analysis.getUnusedDeclaredArtifacts().isEmpty()) {
            startSection(getI18nString("UnusedDeclaredDependencies"));
            renderDependenciesTable(sink, analysis.getUnusedDeclaredArtifacts());
            endSection();
            reported = true;
        }

        // Generate Non-Test Scoped Test Dependencies:
        if (!analysis.getTestArtifactsWithNonTestScope().isEmpty()) {
            startSection(getI18nString("CompileScopeTestOnlyDependencies"));
            renderDependenciesTable(sink, analysis.getTestArtifactsWithNonTestScope());
            endSection();
            reported = true;
        }

        if (!reported) {
            text(getI18nString("noDependencyProblems"));
        }

        endSection();
    }

    private void renderDependenciesTable(Sink sink, Collection<Dependency> artifacts) {
        startTable();

        tableHeader(new String[] {"GroupId", "ArtifactId", "Version", "Scope", "Classifier", "Type", "Optional"});

        for (Dependency artifact : artifacts) {
            tableRow(new String[] {
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion().toString(),
                artifact.getScope().id(),
                artifact.getClassifier(),
                artifact.getType().id(),
                artifact.isOptional() ? "" : "false"
            });
        }

        endTable();
    }
}
