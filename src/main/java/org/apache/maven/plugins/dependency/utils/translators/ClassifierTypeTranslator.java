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

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.TypeRegistry;

/**
 * Translates the artifacts to artifacts with another classifier and/or type.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class ClassifierTypeTranslator implements ArtifactTranslator {
    private final Session session;

    private String classifier;

    private String type;

    /**
     * @param session the session, used to look the types up
     * @param theClassifier the classifier to use
     * @param theType the type
     */
    public ClassifierTypeTranslator(Session session, String theClassifier, String theType) {
        this.session = session;
        this.classifier = theClassifier;
        this.type = theType;
    }

    @Override
    public Set<Artifact> translate(Set<Dependency> artifacts, Log log) {
        Set<Artifact> results;

        log.debug("Translating Artifacts using Classifier: " + this.classifier + " and Type: " + this.type);

        results = new LinkedHashSet<>();
        TypeRegistry typeRegistry = session.getService(TypeRegistry.class);
        for (Dependency artifact : artifacts) {
            // this translator must pass both type and classifier here so we
            // will use the
            // base artifact value if null comes in
            final String useType;
            if (this.type != null && !this.type.isEmpty()) {
                useType = this.type;
            } else {
                useType = artifact.getType().id();
            }

            final String extension;
            Optional<Type> resolvedType = typeRegistry.lookup(useType);
            if (resolvedType.isPresent()) {
                extension = resolvedType.get().getExtension();
            } else {
                extension = this.type;
            }

            String useClassifier;
            if (this.classifier != null && !this.classifier.isEmpty()) {
                useClassifier = this.classifier;
            } else {
                useClassifier = artifact.getClassifier();
            }

            results.add(session.createArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion().toString(),
                    useClassifier,
                    extension,
                    null));
        }

        return results;
    }

    /**
     * @return the type
     */
    public String getType() {
        return this.type;
    }

    /**
     * @param theType the type to set
     */
    public void setType(String theType) {
        this.type = theType;
    }

    /**
     * @return the classifier
     */
    public String getClassifier() {
        return this.classifier;
    }

    /**
     * @param theClassifier the classifier to set
     */
    public void setClassifier(String theClassifier) {
        this.classifier = theClassifier;
    }
}
