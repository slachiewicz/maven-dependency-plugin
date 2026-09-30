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

import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.plugin.Log;

/**
 * Translates the artifacts to another set of artifacts.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public interface ArtifactTranslator {
    /**
     * Translates the artifacts to another set of artifacts.
     *
     * @param artifacts the artifacts to translate
     * @param log a logger
     * @return the translated artifacts
     */
    Set<Artifact> translate(Set<Dependency> artifacts, Log log);
}
