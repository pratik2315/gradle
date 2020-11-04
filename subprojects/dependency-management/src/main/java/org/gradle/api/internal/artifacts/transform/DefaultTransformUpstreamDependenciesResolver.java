/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.artifacts.ResolverResults;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.internal.Describables;
import org.gradle.internal.Factory;
import org.gradle.internal.Try;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.model.CalculatedValueContainer;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.model.ValueCalculator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DefaultTransformUpstreamDependenciesResolver implements TransformUpstreamDependenciesResolver {
    public static final ArtifactTransformDependencies NO_RESULT = new ArtifactTransformDependencies() {
        @Override
        public FileCollection getFiles() {
            throw failure();
        }

        @Override
        public CurrentFileCollectionFingerprint fingerprint(FileCollectionFingerprinter fingerprinter) {
            return fingerprinter.empty();
        }
    };
    public static final TransformUpstreamDependencies NO_DEPENDENCIES = new TransformUpstreamDependencies() {
        @Override
        public FileCollection selectedArtifacts() {
            throw failure();
        }

        @Override
        public void finalizeIfNotAlready() {
        }

        @Override
        public Try<ArtifactTransformDependencies> computeArtifacts() {
            return Try.successful(NO_RESULT);
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }
    };

    private final ComponentIdentifier componentIdentifier;
    private final Factory<ResolverResults> graphResults;
    private final Factory<ResolverResults> artifactResults;
    private final DomainObjectContext owner;
    private final FilteredResultFactory filteredResultFactory;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private Set<ComponentIdentifier> buildDependencies;
    private Set<ComponentIdentifier> dependencies;

    public DefaultTransformUpstreamDependenciesResolver(ComponentIdentifier componentIdentifier,
                                                        Factory<ResolverResults> graphResults,
                                                        Factory<ResolverResults> artifactResults,
                                                        DomainObjectContext owner,
                                                        FilteredResultFactory filteredResultFactory,
                                                        CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.componentIdentifier = componentIdentifier;
        this.graphResults = graphResults;
        this.artifactResults = artifactResults;
        this.owner = owner;
        this.filteredResultFactory = filteredResultFactory;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("Transform does not use artifact dependencies.");
    }

    @Override
    public TransformUpstreamDependencies dependenciesFor(TransformationStep transformationStep) {
        if (!transformationStep.requiresDependencies()) {
            return NO_DEPENDENCIES;
        }
        return new TransformUpstreamDependenciesImpl(transformationStep, calculatedValueContainerFactory);
    }

    private FileCollectionInternal selectedArtifactsFor(ImmutableAttributes fromAttributes) {
        if (dependencies == null) {
            ResolverResults results = artifactResults.create();
            dependencies = computeDependencies(componentIdentifier, ComponentIdentifier.class, results.getResolutionResult().getAllComponents(), false);
        }
        return filteredResultFactory.resultsMatching(fromAttributes, element -> dependencies.contains(element));
    }

    private void computeDependenciesFor(ImmutableAttributes fromAttributes, TaskDependencyResolveContext context) {
        if (buildDependencies == null) {
            ResolverResults results = graphResults.create();
            buildDependencies = computeDependencies(componentIdentifier, ComponentIdentifier.class, results.getResolutionResult().getAllComponents(), true);
        }
        FileCollectionInternal files = filteredResultFactory.resultsMatching(fromAttributes, element -> buildDependencies.contains(element));
        context.add(files);
    }

    private static Set<ComponentIdentifier> computeDependencies(ComponentIdentifier componentIdentifier, Class<? extends ComponentIdentifier> type, Set<ResolvedComponentResult> componentResults, boolean strict) {
        ResolvedComponentResult targetComponent = null;
        for (ResolvedComponentResult component : componentResults) {
            if (component.getId().equals(componentIdentifier)) {
                targetComponent = component;
                break;
            }
        }
        if (targetComponent == null) {
            if (strict) {
                throw new AssertionError("Could not find component " + componentIdentifier + " in provided results.");
            } else {
                return Collections.emptySet();
            }
        }
        Set<ComponentIdentifier> buildDependencies = new HashSet<>();
        collectDependenciesIdentifiers(buildDependencies, type, new HashSet<>(), targetComponent.getDependencies());
        return buildDependencies;
    }

    private static void collectDependenciesIdentifiers(Set<ComponentIdentifier> dependenciesIdentifiers, Class<? extends ComponentIdentifier> type, Set<ComponentIdentifier> visited, Set<? extends DependencyResult> dependencies) {
        for (DependencyResult dependency : dependencies) {
            if (dependency instanceof ResolvedDependencyResult) {
                ResolvedDependencyResult resolvedDependency = (ResolvedDependencyResult) dependency;
                ResolvedComponentResult selected = resolvedDependency.getSelected();
                if (type.isInstance(selected.getId())) {
                    dependenciesIdentifiers.add(selected.getId());
                }
                if (visited.add(selected.getId())) {
                    // Do not traverse if seen already
                    collectDependenciesIdentifiers(dependenciesIdentifiers, type, visited, selected.getDependencies());
                }
            }
        }
    }

    public static abstract class FinalizeTransformDependencies implements ValueCalculator<ArtifactTransformDependencies> {
        public abstract FileCollection selectedArtifacts();

        @Override
        public ArtifactTransformDependencies calculateValue(NodeExecutionContext context) {
            FileCollection files = selectedArtifacts();
            // Trigger resolution, including any failures
            files.getFiles();
            return new DefaultArtifactTransformDependencies(files);
        }
    }

    public class FinalizeTransformDependenciesFromSelectedArtifacts extends FinalizeTransformDependencies {
        private final ImmutableAttributes fromAttributes;

        public FinalizeTransformDependenciesFromSelectedArtifacts(ImmutableAttributes fromAttributes) {
            this.fromAttributes = fromAttributes;
        }

        @Override
        public FileCollection selectedArtifacts() {
            return selectedArtifactsFor(fromAttributes);
        }

        @Override
        public boolean usesMutableProjectState() {
            return owner.getProject() != null;
        }

        @Override
        public Project getOwningProject() {
            return owner.getProject();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            computeDependenciesFor(fromAttributes, context);
        }
    }

    private class TransformUpstreamDependenciesImpl implements TransformUpstreamDependencies {
        private final CalculatedValueContainer<ArtifactTransformDependencies, FinalizeTransformDependencies> transformDependencies;
        private final TransformationStep transformationStep;

        public TransformUpstreamDependenciesImpl(TransformationStep transformationStep, CalculatedValueContainerFactory calculatedValueContainerFactory) {
            this.transformationStep = transformationStep;
            transformDependencies = calculatedValueContainerFactory.create(Describables.of("dependencies for", transformationStep), new FinalizeTransformDependenciesFromSelectedArtifacts(transformationStep.getFromAttributes()));
        }

        @Override
        public FileCollection selectedArtifacts() {
            return selectedArtifactsFor(transformationStep.getFromAttributes());
        }

        @Override
        public Try<ArtifactTransformDependencies> computeArtifacts() {
            return transformDependencies.getValue();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(transformDependencies);
        }

        @Override
        public void finalizeIfNotAlready() {
            transformDependencies.finalizeIfNotAlready();
        }
    }
}
