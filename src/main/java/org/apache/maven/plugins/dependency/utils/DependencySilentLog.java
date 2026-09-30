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

import java.util.function.Supplier;

import org.apache.maven.api.plugin.Log;

/**
 * A {@link Log} that discards everything.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public class DependencySilentLog implements Log {

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(CharSequence content) {
        // nop
    }

    @Override
    public void debug(CharSequence content, Throwable error) {
        // nop
    }

    @Override
    public void debug(Throwable error) {
        // nop
    }

    @Override
    public void debug(Supplier<String> content) {
        // nop
    }

    @Override
    public void debug(Supplier<String> content, Throwable error) {
        // nop
    }

    @Override
    public boolean isInfoEnabled() {
        return false;
    }

    @Override
    public void info(CharSequence content) {
        // nop
    }

    @Override
    public void info(CharSequence content, Throwable error) {
        // nop
    }

    @Override
    public void info(Throwable error) {
        // nop
    }

    @Override
    public void info(Supplier<String> content) {
        // nop
    }

    @Override
    public void info(Supplier<String> content, Throwable error) {
        // nop
    }

    @Override
    public boolean isWarnEnabled() {
        return false;
    }

    @Override
    public void warn(CharSequence content) {
        // nop
    }

    @Override
    public void warn(CharSequence content, Throwable error) {
        // nop
    }

    @Override
    public void warn(Throwable error) {
        // nop
    }

    @Override
    public void warn(Supplier<String> content) {
        // nop
    }

    @Override
    public void warn(Supplier<String> content, Throwable error) {
        // nop
    }

    @Override
    public boolean isErrorEnabled() {
        return false;
    }

    @Override
    public void error(CharSequence content) {
        // nop
    }

    @Override
    public void error(CharSequence content, Throwable error) {
        // nop
    }

    @Override
    public void error(Throwable error) {
        // nop
    }

    @Override
    public void error(Supplier<String> content) {
        // nop
    }

    @Override
    public void error(Supplier<String> content, Throwable error) {
        // nop
    }
}
