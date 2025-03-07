/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.maven.internal.content;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.MultipleFailures;
import org.sonatype.nexus.common.entity.Continuations;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.internal.Attributes;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.AbstractMetadataRebuilder;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.AbstractMetadataUpdater;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.Maven2Metadata;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.transaction.Transactional;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet.METADATA_FORCE_REBUILD;
import static org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet.METADATA_REBUILD;
import static org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataUtils.metadataPath;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

/**
 * @since 3.26
 */
@Singleton
@Named
public class DatastoreMetadataRebuilder
    extends AbstractMetadataRebuilder
{
  private static final String PATH_PREFIX = "/";

  @Inject
  public DatastoreMetadataRebuilder(
      @Named("${nexus.maven.metadata.rebuild.bufferSize:-1000}") final int bufferSize,
      @Named("${nexus.maven.metadata.rebuild.timeoutSeconds:-60}") final int timeoutSeconds)
  {
    super(bufferSize, timeoutSeconds);
  }

  @Transactional
  @Override
  public boolean rebuild(
      final Repository repository,
      final boolean update,
      final boolean rebuildChecksums,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    checkNotNull(repository);
    return rebuildInTransaction(repository, update, rebuildChecksums, groupId, artifactId, baseVersion);
  }

  @Override
  public boolean rebuildInTransaction(
      final Repository repository,
      final boolean update,
      final boolean rebuildChecksums,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    checkNotNull(repository);
    return new DatastoreWorker(repository, update, rebuildChecksums, groupId, artifactId, baseVersion, bufferSize,
        timeoutSeconds, new DatastoreMetadataUpdater(update, repository)).rebuildMetadata();
  }

  @Override
  public boolean refreshInTransaction(
      final Repository repository,
      final boolean update,
      final boolean rebuildChecksums,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    checkNotNull(repository);
    return new DatastoreWorker(repository, update, rebuildChecksums, groupId, artifactId, baseVersion, bufferSize,
        timeoutSeconds, new DatastoreMetadataUpdater(update, repository)).refreshMetadata();
  }

  @Transactional
  @Override
  protected Set<String> deleteAllMetadataFiles(
      final Repository repository,
      final String groupId,
      final String artifactId,
      final String baseVersion)
  {
    return super.deleteAllMetadataFiles(repository, groupId, artifactId, baseVersion);
  }

  @Override
  protected Set<String> deleteGavMetadata(final Repository repository, final String groupId, final String artifactId, final String baseVersion)
  {
    MavenPath gavMetadataPath = metadataPath(groupId, artifactId, baseVersion);
    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
    try {
        return mavenContentFacet.deleteWithHashes(gavMetadataPath);
    }
    catch (IOException e) {
      log.warn("Error encountered when deleting metadata: repository={}", repository);
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean exists(final Repository repository, final MavenPath mavenPath) {
    return repository.facet(ContentFacet.class).assets().path(mavenPath.getPath()).find().isPresent();
  }

  protected static class DatastoreWorker
      extends Worker
  {
    public DatastoreWorker(
        final Repository repository,
        final boolean update,
        final boolean rebuildChecksums,
        @Nullable final String groupId,
        @Nullable final String artifactId,
        @Nullable final String baseVersion,
        final int bufferSize,
        final int timeoutSeconds,
        final AbstractMetadataUpdater metadataUpdater)
    {
      super(repository, update, rebuildChecksums, groupId, artifactId, baseVersion, bufferSize, timeoutSeconds,
          metadataUpdater, repository.facet(MavenContentFacet.class).getMavenPathParser());
    }

    @Override
    protected List<Map<String, Object>> browseGAVs() {
      Collection<String> namespaces = Optional.ofNullable(groupId)
          .map(Collections::singleton)
          .map(set -> (Collection<String>) set)
          .orElseGet(() -> content().components().namespaces());
      log.debug("Searching GAVs on {} namespaces", namespaces.size());

      return namespaces.stream()
          .map(namespace -> namesInNamespace(namespace)
              .map(name -> findBaseVersions(namespace, name))
              .collect(Collectors.toList()))
          .flatMap(Collection::stream)
          .collect(Collectors.toList());
    }

    private Stream<String> namesInNamespace(final String namespace) {
      if (groupId != null && artifactId != null) {
        return Collections.singleton(artifactId).stream();
      }
      else {
        return content().components()
            .names(namespace)
            .stream();
      }
    }

    private MavenContentFacet content() {
      return repository.facet(MavenContentFacet.class);
    }

    private Map<String, Object> findBaseVersions(final String namespace, final String name) {
      FluentQuery<FluentComponent> query = content().components()
          .byFilter("namespace = #{filterParams.namespace} AND name = #{filterParams.name}",
              ImmutableMap.of("namespace", namespace, "name", name));

      Set<String> baseVersions = Continuations.streamOf(query::browse)
          .map(component -> component.attributes("maven2").get("baseVersion", String.class))
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());

      return ImmutableMap.<String, Object>of(
          "groupId", namespace,
          "artifactId", name,
          "baseVersions", baseVersions);
    }

    @Override
    protected Content get(final MavenPath mavenPath) throws IOException {
      return content().get(mavenPath).orElse(null);
    }

    @Override
    protected void put(final MavenPath mavenPath, final Payload payload) throws IOException {
      content().put(mavenPath, payload);
    }

    @Override
    protected void rebuildMetadataInner(
        final String groupId,
        final String artifactId,
        final Set<String> baseVersions,
        final MultipleFailures failures)
    {
      metadataBuilder.onEnterArtifactId(artifactId);

      FluentComponents components = content().components();

      for (final String baseVersion : baseVersions) {
        checkCancellation();
        metadataBuilder.onEnterBaseVersion(baseVersion);

        processAssets(components, groupId, artifactId, baseVersion, this::processAsset);

        processMetadata(metadataPath(groupId, artifactId, baseVersion), metadataBuilder.onExitBaseVersion(), failures);
      }

      processMetadata(metadataPath(groupId, artifactId, null), metadataBuilder.onExitArtifactId(), failures);
    }

    private void processAssets(
        final FluentComponents components,
        final String groupId,
        final String artifactId,
        final String baseVersion,
        final Consumer<Pair<FluentComponent, FluentAsset>> processor)
    {
      if (StringUtils.isBlank(groupId) || StringUtils.isBlank(artifactId) || StringUtils.isBlank(baseVersion)) {
        log.debug("Skipping assets for component with groupId={}, artifactId={}, and baseVersion={}",
            groupId, artifactId, baseVersion);
        return;
      }

      FluentQuery<FluentComponent> componentsByBaseVersion = components.byFilter(
          "namespace = #{filterParams.groupId} AND " +
              "name = #{filterParams.artifactId} AND " +
              "(base_version = #{filterParams.baseVersion} OR version = #{filterParams.baseVersion})",
          ImmutableMap.of(
              "groupId", groupId,
              "artifactId", artifactId,
              "baseVersion", baseVersion));

      Continuations.streamOf(componentsByBaseVersion::browse, bufferSize)
          .forEach(component -> component.assets().stream()
                .filter(asset -> !mavenPathParser.parsePath(asset.path()).isSubordinate())
                .forEach(asset -> processor.accept(Pair.of(component, asset)))
          );
    }

    @Override
    protected boolean refreshArtifact(
        final String groupId,
        final String artifactId,
        final Set<String> baseVersions,
        final MultipleFailures failures)
    {
      MavenPath metadataPath = metadataPath(groupId, artifactId, null);

      FluentComponents components = content().components();

      metadataBuilder.onEnterArtifactId(artifactId);
      boolean rebuiltAtLeastOneVersion = baseVersions.stream()
          .map(v -> {
            checkCancellation();
            return refreshVersion(components, groupId, artifactId, v, failures);
          })
          .reduce(Boolean::logicalOr)
          .orElse(false);
      Maven2Metadata newMetadata = metadataBuilder.onExitArtifactId();

      boolean isRequestedVersion = StringUtils.equals(this.groupId, groupId) &&
          StringUtils.equals(this.artifactId, artifactId) &&
          StringUtils.equals(baseVersion, null);

      if (isRequestedVersion || rebuiltAtLeastOneVersion || requiresRebuild(metadataPath)) {
        processMetadata(metadataPath, newMetadata, failures);
        return true;
      }
      else {
        log.debug("Skipping {}:{} for rebuild", groupId, artifactId);
        return false;
      }
    }

    private boolean refreshVersion(
        final FluentComponents components,
        final String groupId,
        final String artifactId,
        final String version,
        final MultipleFailures failures)
    {
      MavenPath metadataPath = metadataPath(groupId, artifactId, version);

      metadataBuilder.onEnterBaseVersion(baseVersion);
      processAssets(components, groupId, artifactId, version, this::processAsset);
      Maven2Metadata newMetadata = metadataBuilder.onExitBaseVersion();

      /**
       * The rebuild flag on the requested asset may have been cleared before we were invoked.
       * So we check a special case to always rebuild the metadata for the g:a:v that we were initialized with
       */
      boolean isRequestedVersion = StringUtils.equals(this.groupId, groupId) &&
          StringUtils.equals(this.artifactId, artifactId) &&
          StringUtils.equals(baseVersion, version);

      if (isRequestedVersion || requiresRebuild(metadataPath)) {
        processMetadata(metadataPath, newMetadata, failures);
        return true;
      }
      else {
        log.debug("Skipping {}:{}:{} for rebuild", groupId, artifactId, version);
        return false;
      }
    }

    private boolean requiresRebuild(final MavenPath metadataPath) {
      FluentAssets assets = content().assets();
      Optional<FluentAsset> existingMetadata = assets.path(metadataPath.getPath()).find();

      return existingMetadata.map(this::getMetadataRebuildFlag).orElse(false);
    }

    private Boolean getMetadataRebuildFlag(final FluentAsset asset) {
      return asset.attributes(METADATA_REBUILD).get(METADATA_FORCE_REBUILD, Boolean.class, false);
    }

    private void processAsset(final Pair<FluentComponent, FluentAsset> componentAssetPair) {
      checkCancellation();
      FluentComponent component = componentAssetPair.getLeft();
      FluentAsset asset = componentAssetPair.getRight();
      MavenPath mavenPath = mavenPathParser.parsePath(asset.path());
      metadataBuilder.addArtifactVersion(mavenPath);
      if (rebuildChecksums) {
        boolean sha1ChecksumWasRebuilt = mayUpdateChecksum(mavenPath, HashType.SHA1);
        if (sha1ChecksumWasRebuilt) {
          // Rebuilding checksums is expensive so only rebuild the others if the first one was rebuilt
          mayUpdateChecksum(mavenPath, HashType.SHA256);
          mayUpdateChecksum(mavenPath, HashType.SHA512);
          mayUpdateChecksum(mavenPath, HashType.MD5);
        }
      }
      final String packaging =
          component.attributes(repository.getFormat().getValue()).get(Attributes.P_PACKAGING, String.class);
      log.debug("POM packaging: {}", packaging);
      if ("maven-plugin".equals(packaging)) {
        metadataBuilder.addPlugin(getPluginPrefix(mavenPath.locateMainArtifact("jar")), component.name(),
            component.attributes(repository.getFormat().getValue()).get(Attributes.P_POM_NAME, String.class));
      }
    }

    @Override
    protected Optional<HashCode> getChecksum(final MavenPath mavenPath, final HashType hashType)
    {
      return content()
          .assets()
          .path(PATH_PREFIX + mavenPath.getPath())
          .find()
          .flatMap(Asset::blob)
          .map(AssetBlob::checksums)
          .map(checksums -> checksums.get(hashType.name().toLowerCase()))
          .map(HashCode::fromString);
    }
  }

  @Override
  public Set<String> deleteMetadata(final Repository repository, final List<String[]> gavs) {
    checkNotNull(repository);
    checkNotNull(gavs);

    List<String> paths = Lists.newArrayList();
    for (String[] gav : gavs) {
      MavenPath mavenPath = metadataPath(gav[0], gav[1], gav[2]);
      paths.add(prependIfMissing(mavenPath.main().getPath(), PATH_PREFIX));
      for (HashType hashType : HashType.values()) {
        paths.add(prependIfMissing(mavenPath.main().hash(hashType).getPath(), PATH_PREFIX));
      }
    }

    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
    Set<String> deletedPaths = Sets.newHashSet();
    if (mavenContentFacet.delete(paths)) {
      deletedPaths.addAll(paths);
    }
    return deletedPaths;
  }
}
