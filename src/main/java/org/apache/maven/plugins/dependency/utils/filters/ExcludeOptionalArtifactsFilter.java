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
package org.apache.maven.plugins.dependency.utils.filters;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.shared.artifact.filter.collection.AbstractArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;

/**
 * Filter that removes optional artifacts, that is artifacts for which {@link Dependency#isOptional()} is {@code true}.
 *
 * @since 3.12.0
 */
public class ExcludeOptionalArtifactsFilter extends AbstractArtifactsFilter {

    @Override
    public Set<Dependency> filter(Set<Dependency> artifacts) throws ArtifactFilterException {
        Set<Dependency> result = new LinkedHashSet<>();
        for (Dependency artifact : artifacts) {
            if (!artifact.isOptional()) {
                result.add(artifact);
            }
        }
        return result;
    }
}
