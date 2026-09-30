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
package org.apache.maven.plugins.dependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;

/**
 * Displays all ancestor POMs of the project. This may be useful in a continuous integration system where you want to
 * know all parent poms of the project.
 *
 * @author Mirko Friedenhagen
 * @since 2.9
 */
@Mojo(name = "display-ancestors", defaultPhase = "validate")
public class DisplayAncestorsMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    @Override
    public void execute() throws MojoException {
        final List<String> ancestors = collectAncestors();

        if (ancestors.isEmpty()) {
            log.info("No Ancestor POMs!");
        } else {
            log.info("Ancestor POMs: " + String.join(" <- ", ancestors));
        }
    }

    /**
     * Follows the {@code parent} element instead of {@code Project#getParent()}, which is empty for a parent that is
     * not in the reactor: the parents are read from the POMs resolved from the repositories.
     */
    private ArrayList<String> collectAncestors() {
        final ArrayList<String> ancestors = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        ResolverUtil resolverUtil = new ResolverUtil(session, project);
        Parent current = project.getModel().getParent();
        while (current != null) {
            final Parent parent = current;
            String ancestor = parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion();
            if (!seen.add(ancestor)) {
                break;
            }
            ancestors.add(ancestor);

            Path pom = session.getProjects().stream()
                    .filter(p -> ancestor.equals(p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion()))
                    .map(Project::getPomPath)
                    .findFirst()
                    .orElseGet(() -> resolverUtil
                            .resolveArtifact(
                                    session.createArtifact(
                                            parent.getGroupId(),
                                            parent.getArtifactId(),
                                            parent.getVersion(),
                                            "",
                                            "pom",
                                            "pom"),
                                    resolverUtil.getProjectRepositories())
                            .getPath());
            current = session.getService(ModelXmlFactory.class)
                    .read(XmlReaderRequest.builder().path(pom).strict(false).build())
                    .getParent();
        }

        return ancestors;
    }
}
