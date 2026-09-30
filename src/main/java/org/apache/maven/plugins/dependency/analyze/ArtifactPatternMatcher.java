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

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;

/**
 * Matches dependencies against ignore patterns of the form
 * <code>[groupId]:[artifactId]:[type]:[version]:[scope]</code>.
 * <p>
 * The first four segments are matched by {@link StrictPatternExcludesArtifactFilter}, which silently never matches a
 * pattern with more than four segments. The optional fifth segment is therefore split off and matched here, with the
 * same rules: an empty segment is a wildcard, and <code>*</code> is allowed as a full or partial wildcard.
 */
final class ArtifactPatternMatcher {

    private ArtifactPatternMatcher() {}

    /**
     * Removes dependencies matching any of the given patterns from the source set and returns the removed ones.
     *
     * @param artifacts the source set of dependencies (mutated in place)
     * @param patterns  patterns, may be null or empty
     * @return the dependencies that were removed from the source set
     */
    static Set<Dependency> removeMatching(Set<Dependency> artifacts, String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            return Collections.emptySet();
        }
        Set<Dependency> result = new LinkedHashSet<>();
        for (Iterator<Dependency> it = artifacts.iterator(); it.hasNext(); ) {
            Dependency artifact = it.next();
            if (matchesAny(artifact, patterns)) {
                it.remove();
                result.add(artifact);
            }
        }
        return result;
    }

    /**
     * @param artifact the dependency to test
     * @param patterns patterns, may be null or empty
     * @return true if the dependency matches at least one pattern
     */
    static boolean matchesAny(Dependency artifact, String[] patterns) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(artifact, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs one warning for every pattern with more than five segments, because such a pattern never matches.
     *
     * @param log      the log to warn on
     * @param patterns patterns, may be null
     */
    static void warnAboutUnsupportedPatterns(Log log, String[] patterns) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern.split(":").length > 5) {
                log.warn("Ignoring dependency pattern '" + pattern + "' because it has more than five segments and"
                        + " can never match. The supported form is [groupId]:[artifactId]:[type]:[version]:[scope]");
            }
        }
    }

    private static boolean matches(Dependency artifact, String pattern) {
        String[] segments = pattern.split(":");
        if (segments.length <= 4) {
            return !new StrictPatternExcludesArtifactFilter(Collections.singletonList(pattern)).test(artifact);
        }
        if (segments.length > 5) {
            return false;
        }
        String coordinates = String.join(":", Arrays.copyOf(segments, 4));
        String scope = artifact.getScope() == null ? null : artifact.getScope().id();
        return !new StrictPatternExcludesArtifactFilter(Collections.singletonList(coordinates)).test(artifact)
                && matchesSegment(scope, segments[4]);
    }

    private static boolean matchesSegment(String value, String pattern) {
        if (pattern.isEmpty() || "*".equals(pattern)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        boolean prefix = pattern.endsWith("*");
        boolean suffix = pattern.startsWith("*");
        String literal = pattern.substring(suffix ? 1 : 0, pattern.length() - (prefix ? 1 : 0));
        if (prefix && suffix) {
            return value.contains(literal);
        } else if (prefix) {
            return value.startsWith(literal);
        } else if (suffix) {
            return value.endsWith(literal);
        }
        return value.equals(literal);
    }
}
