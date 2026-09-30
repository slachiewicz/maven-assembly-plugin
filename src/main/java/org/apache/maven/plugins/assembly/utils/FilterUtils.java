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
package org.apache.maven.plugins.assembly.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.artifact.AssemblyArtifact;
import org.apache.maven.plugins.assembly.functions.MavenProjects;
import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StatisticsReportingArtifactFilter;
import org.slf4j.Logger;

/**
 *
 */
public final class FilterUtils {

    private FilterUtils() {}

    public static Set<Project> filterProjects(
            final Session session,
            final Set<Project> projects,
            final List<String> includes,
            final List<String> excludes,
            final boolean actTransitively,
            final Logger logger) {
        final List<StatisticsReportingArtifactFilter> allFilters = new ArrayList<>();

        final List<Predicate<AssemblyArtifact>> filter = new ArrayList<>();

        if (!includes.isEmpty()) {
            final TrailAwareIncludes includeFilter = new TrailAwareIncludes(includes, actTransitively);

            filter.add(includeFilter::accept);
            allFilters.add(includeFilter);
        }
        if (!excludes.isEmpty()) {
            final TrailAwareExcludes excludeFilter = new TrailAwareExcludes(excludes, actTransitively);

            filter.add(excludeFilter::accept);
            allFilters.add(excludeFilter);
        }

        Set<Project> result = new LinkedHashSet<>(projects.size());
        for (Project project : projects) {
            final AssemblyArtifact artifact = MavenProjects.mainArtifact(session, project);

            if (filter.stream().allMatch(f -> f.test(artifact))) {
                result.add(project);
            }
        }

        for (final StatisticsReportingArtifactFilter f : allFilters) {
            f.reportMissedCriteria(logger);
        }
        return result;
    }

    public static void filterArtifacts(
            final Set<AssemblyArtifact> artifacts,
            final List<String> includes,
            final List<String> excludes,
            final boolean strictFiltering,
            final boolean actTransitively,
            final Logger logger,
            @SuppressWarnings("unchecked") final Predicate<AssemblyArtifact>... additionalFilters)
            throws InvalidAssemblerConfigurationException {
        final List<StatisticsReportingArtifactFilter> allFilters = new ArrayList<>();

        final List<Predicate<AssemblyArtifact>> filter = new ArrayList<>();

        if (additionalFilters != null) {
            for (final Predicate<AssemblyArtifact> additionalFilter : additionalFilters) {
                if (additionalFilter != null) {
                    filter.add(additionalFilter);
                }
            }
        }

        if (!includes.isEmpty()) {
            final TrailAwareIncludes includeFilter = new TrailAwareIncludes(includes, actTransitively);

            filter.add(includeFilter::accept);

            allFilters.add(includeFilter);
        }

        if (!excludes.isEmpty()) {
            final TrailAwareExcludes excludeFilter = new TrailAwareExcludes(excludes, actTransitively);

            filter.add(excludeFilter::accept);

            allFilters.add(excludeFilter);
        }

        for (final Iterator<AssemblyArtifact> it = artifacts.iterator(); it.hasNext(); ) {
            final AssemblyArtifact artifact = it.next();

            if (!filter.stream().allMatch(f -> f.test(artifact))) {
                it.remove();

                if (logger.isDebugEnabled()) {
                    logger.debug(artifact.getId() + " was removed by one or more filters.");
                }
            }
        }

        reportFilteringStatistics(allFilters, logger);

        for (final StatisticsReportingArtifactFilter f : allFilters) {
            if (strictFiltering && f.hasMissedCriteria()) {
                throw new InvalidAssemblerConfigurationException(
                        "One or more filters had unmatched criteria. Check debug log for more information.");
            }
        }
    }

    public static void reportFilteringStatistics(
            final Collection<? extends StatisticsReportingArtifactFilter> filters, final Logger logger) {
        for (final StatisticsReportingArtifactFilter sFilter : filters) {
            if (logger.isDebugEnabled()) {
                logger.debug("Statistics for " + sFilter + "\n");
            }

            sFilter.reportMissedCriteria(logger);
            sFilter.reportFilteredArtifacts(logger);
        }
    }

    public static Predicate<AssemblyArtifact> newScopeFilter(final String rootScope) {
        return newScopeFilter(Collections.singleton(rootScope));
    }

    public static Predicate<AssemblyArtifact> newScopeFilter(final Collection<String> rootScopes) {
        final Set<String> scopes = classpathScopes(rootScopes);

        return artifact -> scopes.contains(artifact.getScope());
    }

    /**
     * The dependency scopes that end up on a classpath of the given scopes.
     */
    public static Set<String> classpathScopes(final Collection<String> rootScopes) {
        Set<String> scopes = new HashSet<>();

        for (String rootScope : rootScopes) {
            if (AssemblyArtifact.SCOPE_COMPILE.equals(rootScope)) {
                scopes.addAll(Arrays.asList("compile", "provided", "system"));
            }
            if (AssemblyArtifact.SCOPE_PROVIDED.equals(rootScope)) {
                scopes.add("provided");
            }
            if (AssemblyArtifact.SCOPE_RUNTIME.equals(rootScope)) {
                scopes.addAll(Arrays.asList("compile", "runtime"));
            }
            if (AssemblyArtifact.SCOPE_SYSTEM.equals(rootScope)) {
                scopes.add("system");
            }
            if (AssemblyArtifact.SCOPE_TEST.equals(rootScope)) {
                scopes.addAll(Arrays.asList("compile", "provided", "runtime", "system", "test"));
            }
        }
        return scopes;
    }

    /**
     * The pattern filters of maven-common-artifact-filters 4 no longer know about the dependency trail, which the
     * Maven 3 {@code actTransitively} flag used to consult. This re-creates it on top of
     * {@link PatternIncludesArtifactFilter#patternMatches(Dependency)}: the artifact is tried first, then every
     * element of its trail (the project, the intermediate dependencies, the artifact itself) until a pattern matches.
     * {@code patternMatches} cannot tell "no pattern matched" from "a negated pattern matched", so a second filter
     * with a catch-all pattern appended is asked as well: it says {@code true} only if nothing matched before it.
     */
    private interface TrailAware {
        boolean patternMatches(Dependency dependency);

        boolean catchAllMatches(Dependency dependency);

        boolean actTransitively();

        default Boolean tryMatch(Dependency dependency) {
            if (patternMatches(dependency)) {
                return Boolean.TRUE;
            }
            return catchAllMatches(dependency) ? null : Boolean.FALSE;
        }

        default boolean matches(AssemblyArtifact artifact) {
            Boolean match = tryMatch(artifact.toDependency());
            if (match != null) {
                return match;
            }
            if (actTransitively()) {
                for (Dependency trailItem : artifact.getAncestors()) {
                    match = tryMatch(trailItem);
                    if (match != null) {
                        return match;
                    }
                }
            }
            return false;
        }
    }

    private static List<String> withCatchAll(List<String> patterns) {
        List<String> all = new ArrayList<>(patterns);
        all.add("*");
        return all;
    }

    private static class TrailAwareIncludes extends PatternIncludesArtifactFilter implements TrailAware {
        private final boolean actTransitively;

        private final TrailAwareIncludes catchAll;

        TrailAwareIncludes(List<String> patterns, boolean actTransitively) {
            super(patterns);
            this.actTransitively = actTransitively;
            this.catchAll = new TrailAwareIncludes(withCatchAll(patterns));
        }

        private TrailAwareIncludes(List<String> patterns) {
            super(patterns);
            this.actTransitively = false;
            this.catchAll = null;
        }

        boolean accept(AssemblyArtifact artifact) {
            boolean shouldInclude = matches(artifact);
            if (!shouldInclude) {
                addFilteredArtifact(artifact.toDependency());
            }
            return shouldInclude;
        }

        @Override
        public boolean patternMatches(Dependency dependency) {
            return super.patternMatches(dependency);
        }

        @Override
        public boolean catchAllMatches(Dependency dependency) {
            return catchAll.patternMatches(dependency);
        }

        @Override
        public boolean actTransitively() {
            return actTransitively;
        }
    }

    private static class TrailAwareExcludes extends PatternExcludesArtifactFilter implements TrailAware {
        private final boolean actTransitively;

        private final TrailAwareExcludes catchAll;

        TrailAwareExcludes(List<String> patterns, boolean actTransitively) {
            super(patterns);
            this.actTransitively = actTransitively;
            this.catchAll = new TrailAwareExcludes(withCatchAll(patterns));
        }

        private TrailAwareExcludes(List<String> patterns) {
            super(patterns);
            this.actTransitively = false;
            this.catchAll = null;
        }

        boolean accept(AssemblyArtifact artifact) {
            boolean shouldInclude = !matches(artifact);
            if (!shouldInclude) {
                addFilteredArtifact(artifact.toDependency());
            }
            return shouldInclude;
        }

        @Override
        public boolean patternMatches(Dependency dependency) {
            return super.patternMatches(dependency);
        }

        @Override
        public boolean catchAllMatches(Dependency dependency) {
            return catchAll.patternMatches(dependency);
        }

        @Override
        public boolean actTransitively() {
            return actTransitively;
        }
    }
}
