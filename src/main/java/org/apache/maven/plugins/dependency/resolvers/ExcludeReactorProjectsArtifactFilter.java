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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.Project;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.shared.artifact.filter.collection.AbstractArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;

/**
 * {@link ArtifactFilter} implementation that excludes artifacts found in the Reactor.
 *
 * @author Maarten Mulders
 */
public class ExcludeReactorProjectsArtifactFilter extends AbstractArtifactsFilter {
    private final Log log;
    private final Set<String> reactorArtifactKeys;

    public ExcludeReactorProjectsArtifactFilter(final List<Project> reactorProjects, final Log log) {
        this.log = log;
        this.reactorArtifactKeys = new HashSet<>(reactorProjects.size());
        for (final Project project : reactorProjects) {
            this.reactorArtifactKeys.add(
                    project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        }
    }

    @Override
    public Set<Dependency> filter(final Set<Dependency> artifacts) throws ArtifactFilterException {
        final Set<Dependency> results = new LinkedHashSet<>(artifacts.size());

        for (final Dependency artifact : artifacts) {
            if (!isArtifactInReactor(artifact)) {
                results.add(artifact);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Skipped artifact " + key(artifact) + " because it is present in the reactor");
                }
            }
        }

        return results;
    }

    private boolean isArtifactInReactor(final Dependency artifact) {
        for (final String reactorArtifactKey : this.reactorArtifactKeys) {
            final String artifactKey = key(artifact);

            // This check only includes GAV. Should we take a look at the types, too?
            if (reactorArtifactKey.equals(artifactKey)) {
                return true;
            }
        }
        return false;
    }

    private static String key(Dependency artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }
}
