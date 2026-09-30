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
package org.apache.maven.plugins.dependency.fromConfiguration;

import java.util.Locale;

import org.apache.maven.api.PathScope;

enum ClasspathScope {
    COMPILE(PathScope.MAIN_COMPILE),
    RUNTIME(PathScope.MAIN_RUNTIME),
    TEST(PathScope.TEST_RUNTIME);

    private final PathScope pathScope;

    ClasspathScope(PathScope pathScope) {
        this.pathScope = pathScope;
    }

    /**
     * The Maven 4 path scope whose dependency scopes are collected for this classpath scope.
     */
    PathScope pathScope() {
        return pathScope;
    }

    static ClasspathScope fromString(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown dependency scope '" + value + "'. Expected one of: compile, runtime, test.");
        }
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
