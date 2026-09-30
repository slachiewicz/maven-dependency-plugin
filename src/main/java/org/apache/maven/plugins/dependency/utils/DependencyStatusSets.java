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

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.maven.api.Dependency;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class DependencyStatusSets {
    Set<Dependency> resolvedDependencies = null;

    Set<Dependency> unResolvedDependencies = null;

    Set<Dependency> skippedDependencies = null;

    /**
     * Default ctor.
     */
    public DependencyStatusSets() {}

    /**
     * @param resolved set of {@link Dependency}
     * @param unResolved set of {@link Dependency}
     * @param skipped set of {@link Dependency}
     */
    public DependencyStatusSets(Set<Dependency> resolved, Set<Dependency> unResolved, Set<Dependency> skipped) {
        if (resolved != null) {
            this.resolvedDependencies = new LinkedHashSet<>(resolved);
        }
        if (unResolved != null) {
            this.unResolvedDependencies = new LinkedHashSet<>(unResolved);
        }
        if (skipped != null) {
            this.skippedDependencies = new LinkedHashSet<>(skipped);
        }
    }

    /**
     * @return returns the resolvedDependencies
     */
    public Set<Dependency> getResolvedDependencies() {
        return this.resolvedDependencies;
    }

    /**
     * @param resolvedDependencies the resolvedDependencies to set
     */
    public void setResolvedDependencies(Set<Dependency> resolvedDependencies) {
        if (resolvedDependencies != null) {
            this.resolvedDependencies = new LinkedHashSet<>(resolvedDependencies);
        } else {
            this.resolvedDependencies = null;
        }
    }

    /**
     * @return returns the skippedDependencies
     */
    public Set<Dependency> getSkippedDependencies() {
        return this.skippedDependencies;
    }

    /**
     * @param skippedDependencies the skippedDependencies to set
     */
    public void setSkippedDependencies(Set<Dependency> skippedDependencies) {
        if (skippedDependencies != null) {
            this.skippedDependencies = new LinkedHashSet<>(skippedDependencies);
        } else {
            this.skippedDependencies = null;
        }
    }

    /**
     * @return returns the unResolvedDependencies
     */
    public Set<Dependency> getUnResolvedDependencies() {
        return this.unResolvedDependencies;
    }

    /**
     * @param unResolvedDependencies the unResolvedDependencies to set
     */
    public void setUnResolvedDependencies(Set<Dependency> unResolvedDependencies) {
        if (unResolvedDependencies != null) {
            this.unResolvedDependencies = new LinkedHashSet<>(unResolvedDependencies);
        } else {
            this.unResolvedDependencies = null;
        }
    }
}
