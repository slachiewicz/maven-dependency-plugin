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

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Goal that resolves the project source dependencies from the repository.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @since 2.0-alpha2/3.7.0
 */
@Mojo(name = "resolve-sources", defaultPhase = "generate-sources")
public class ResolveDependencySourcesMojo extends ResolveDependenciesMojo {

    private static final String SOURCES_CLASSIFIER = "sources";

    // the Maven 3 version fixed the classifier with a read-only parameter on a setter, which the Maven 4
    // descriptor generator does not support: fix it when running
    @Override
    protected void doExecute() throws MojoException {
        this.classifier = SOURCES_CLASSIFIER;
        super.doExecute();
    }
}
