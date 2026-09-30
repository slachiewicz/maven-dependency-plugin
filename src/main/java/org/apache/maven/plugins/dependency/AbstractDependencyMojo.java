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

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugins.dependency.utils.DependencySilentLog;
import org.apache.maven.plugins.dependency.utils.ResolverUtil;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public abstract class AbstractDependencyMojo implements Mojo {

    /**
     * The Maven session.
     */
    @Inject
    protected Session session;

    @Inject
    private Log log;

    /**
     * POM, {@code null} if the goal is run outside of a project.
     */
    @Inject
    @Nullable
    private Project project;

    /**
     * If the plugin should be silent.
     *
     * @since 2.0
     * @deprecated to be removed in 3.13.0. Since 3.8.1 the copy and unpack goals log each artifact at debug
     *             level, so remove this parameter from the configuration
     */
    @Deprecated
    @Parameter(property = "silent", defaultValue = "false")
    private boolean silent;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Skip plugin execution only during incremental builds (e.g. triggered from M2E). The Maven 4 API has no
     * {@code BuildContext}, so this flag is accepted for compatibility but has no effect.
     *
     * @see #skip
     * @since 3.4.0
     */
    @Parameter(defaultValue = "false")
    private boolean skipDuringIncrementalBuild;

    // Mojo methods -----------------------------------------------------------

    @Override
    public final void execute() throws MojoException {
        if (isSkip()) {
            getLog().info("Skipping plugin execution");
            return;
        }

        doExecute();
    }

    /**
     * @throws MojoException in case of an error
     */
    protected abstract void doExecute() throws MojoException;

    /**
     * @return returns the project, or {@code null} if the goal is run outside of a project
     */
    public Project getProject() {
        return this.project;
    }

    /**
     * @return the session
     */
    public Session getSession() {
        return session;
    }

    /**
     * @return the helper used to resolve artifacts and dependencies
     */
    protected ResolverUtil getResolverUtil() {
        if (resolverUtil == null) {
            resolverUtil = new ResolverUtil(session, project);
        }
        return resolverUtil;
    }

    /**
     * @return the log, which discards everything if {@link #silent} is set
     */
    public Log getLog() {
        return silent ? SILENT : log;
    }

    private ResolverUtil resolverUtil;

    private static final Log SILENT = new DependencySilentLog();

    /**
     * @return {@link #skip}
     */
    public boolean isSkip() {
        return skip;
    }

    /**
     * @param skip {@link #skip}
     */
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    /**
     * @return {@link #silent}
     * @deprecated to be removed in 3.13.0
     */
    @Deprecated
    protected final boolean isSilent() {
        return silent;
    }

    /**
     * @param silent {@link #silent}
     * @deprecated to be removed in 3.13.0; no API replacement
     */
    @Deprecated
    public void setSilent(boolean silent) {
        this.silent = silent;
    }
}
