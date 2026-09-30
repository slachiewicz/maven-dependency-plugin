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
package org.apache.maven.plugins.dependency.utils.markers;

import java.io.File;
import java.io.IOException;

import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class DefaultFileMarkerHandler implements MarkerHandler {
    /**
     * The artifact.
     */
    protected DownloadedDependency artifact;

    /**
     * The marker directory.
     */
    protected File markerFilesDirectory;

    /**
     * @param theMarkerFilesDirectory the marker directory
     */
    public DefaultFileMarkerHandler(File theMarkerFilesDirectory) {
        this.markerFilesDirectory = theMarkerFilesDirectory;
    }

    /**
     * @param theArtifact {@link DownloadedDependency}
     * @param theMarkerFilesDirectory the marker directory
     */
    public DefaultFileMarkerHandler(DownloadedDependency theArtifact, File theMarkerFilesDirectory) {
        this.artifact = theArtifact;
        this.markerFilesDirectory = theMarkerFilesDirectory;
    }

    /**
     * Returns properly formatted File.
     *
     * @return file object for marker. The file is not guaranteed to exist.
     */
    protected File getMarkerFile() {
        return new File(
                this.markerFilesDirectory, DependencyUtil.getId(this.artifact).replace(':', '-') + ".marker");
    }

    /**
     * Tests whether the file or directory denoted by this abstract pathname exists.
     *
     * @return <code>true</code> if and only if the file or directory denoted by this abstract pathname exists;
     *         <code>false</code> otherwise
     * @throws SecurityException if a security manager exists and its <code>{@link
     *          java.lang.SecurityManager#checkRead(java.lang.String)}</code> method denies read access to the file or
     *             directory
     */
    @Override
    public boolean isMarkerSet() throws MojoException {
        File marker = getMarkerFile();
        return marker.exists();
    }

    @Override
    public boolean isMarkerOlder(DownloadedDependency artifact1) throws MojoException {
        File marker = getMarkerFile();
        if (marker.exists()) {
            return artifact1.getPath().toFile().lastModified() > marker.lastModified();
        } else {
            // if the marker doesn't exist, we want to copy so assume it is
            // infinitely older
            return true;
        }
    }

    @Override
    public void setMarker() throws MojoException {
        File marker = getMarkerFile();
        // create marker file
        try {
            marker.getParentFile().mkdirs();
        } catch (NullPointerException e) {
            // parent is null, ignore it.
        }
        try {
            marker.createNewFile();
        } catch (IOException e) {
            throw new MojoException("Unable to create Marker: " + marker.getAbsolutePath(), e);
        }

        // update marker file timestamp
        try {
            long ts;
            if (this.artifact != null && this.artifact.getPath() != null) {
                ts = this.artifact.getPath().toFile().lastModified();
            } else {
                ts = System.currentTimeMillis();
            }
            if (!marker.setLastModified(ts)) {
                throw new MojoException(
                        "Unable to update last modified timestamp on marker file " + marker.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new MojoException("Unable to update Marker timestamp: " + marker.getAbsolutePath(), e);
        }
    }

    /**
     * Deletes the file or directory denoted by this abstract pathname. If this pathname denotes a directory, then the
     * directory must be empty in order to be deleted.
     *
     * @return <code>true</code> if and only if the file or directory is successfully deleted; <code>false</code>
     *         otherwise
     * @throws SecurityException if a security manager exists and its <code>{@link
     *          java.lang.SecurityManager#checkDelete}</code> method denies delete access to the file
     */
    @Override
    public boolean clearMarker() throws MojoException {
        File marker = getMarkerFile();
        return marker.delete();
    }

    /**
     * @return returns the artifact
     */
    public DownloadedDependency getArtifact() {
        return this.artifact;
    }

    /**
     * @param artifact the artifact to set
     */
    @Override
    public void setArtifact(DownloadedDependency artifact) {
        this.artifact = artifact;
    }

    /**
     * @return returns the markerFilesDirectory
     */
    public File getMarkerFilesDirectory() {
        return this.markerFilesDirectory;
    }

    /**
     * @param markerFilesDirectory the markerFilesDirectory to set
     */
    public void setMarkerFilesDirectory(File markerFilesDirectory) {
        this.markerFilesDirectory = markerFilesDirectory;
    }
}
