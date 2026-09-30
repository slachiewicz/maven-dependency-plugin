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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.plugins.dependency.utils.translators.ArtifactTranslator;
import org.apache.maven.plugins.dependency.utils.translators.ClassifierTypeTranslator;

/**
 * Goal that resolves the project source dependencies from the repository.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @since 2.0-alpha2/3.7.0
 */
@Mojo(name = "resolve-sources", defaultPhase = "generate-sources")
public class ResolveDependencySourcesMojo extends ResolveDependenciesMojo {

    private static final String SOURCES_CLASSIFIER = "sources";

    private static final String TEST_SOURCES_CLASSIFIER = "test-sources";

    private static final String TESTS_CLASSIFIER = "tests";

    @Override
    protected boolean isResolvingAfterFiltering() {
        return true;
    }

    // the Maven 3 version fixed the classifier with a read-only parameter on a setter, which the Maven 4
    // descriptor generator does not support: fix it when running
    @Override
    protected void doExecute() throws MojoException {
        this.classifier = SOURCES_CLASSIFIER;
        super.doExecute();
    }

    /**
     * Maps the {@code tests} artifacts (type {@code test-jar}) to their {@code test-sources}, all others to
     * {@code sources}.
     */
    @Override
    protected ArtifactTranslator createArtifactTranslator() {
        ArtifactTranslator sources = new ClassifierTypeTranslator(session, classifier, type);
        ArtifactTranslator testSources = new ClassifierTypeTranslator(session, TEST_SOURCES_CLASSIFIER, type);
        return (artifacts, log) -> {
            Set<Artifact> results = new LinkedHashSet<>();
            for (Dependency artifact : artifacts) {
                ArtifactTranslator translator =
                        TESTS_CLASSIFIER.equals(artifact.getClassifier()) ? testSources : sources;
                results.addAll(translator.translate(Collections.singleton(artifact), log));
            }
            return results;
        };
    }
}
