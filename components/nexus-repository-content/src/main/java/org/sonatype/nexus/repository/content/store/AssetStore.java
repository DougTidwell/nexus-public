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
package org.sonatype.nexus.repository.content.store;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.AssetInfo;
import org.sonatype.nexus.repository.content.AttributeChangeSet;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.event.asset.AssetAttributesEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetCreatedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetDeletedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetDownloadedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetKindEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetPreDeleteEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetPrePurgeEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetPurgedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetUploadedEvent;
import org.sonatype.nexus.repository.content.event.repository.ContentRepositoryDeletedEvent;
import org.sonatype.nexus.repository.content.fluent.internal.FluentAssetImpl;
import org.sonatype.nexus.transaction.Transactional;

import com.google.inject.assistedinject.Assisted;
import org.apache.shiro.util.CollectionUtils;

import static java.util.Arrays.stream;
import static org.sonatype.nexus.repository.content.AttributesHelper.applyAttributeChange;

/**
 * {@link Asset} store.
 *
 * @since 3.21
 */
@Named
public class AssetStore<T extends AssetDAO>
    extends ContentStoreEventSupport<T>
{
  private static final int LAST_UPDATED_LIMIT = 1000;

  @Inject
  public AssetStore(final DataSessionSupplier sessionSupplier,
                    @Assisted final String contentStoreName,
                    @Assisted final Class<T> daoClass)
  {
    super(sessionSupplier, contentStoreName, daoClass);
  }

  /**
   * Count all assets in the given repository.
   *
   * @param repositoryId the repository to count
   * @param kind optional kind of assets to count
   * @param filter optional filter to apply
   * @param filterParams parameter map for the optional filter
   * @return count of assets in the repository
   */
  @Transactional
  public int countAssets(final int repositoryId,
                         @Nullable final String kind,
                         @Nullable final String filter,
                         @Nullable final Map<String, Object> filterParams)
  {
    return dao().countAssets(repositoryId, kind, filter, filterParams);
  }

  /**
   * Browse all assets in the given repository in a paged fashion.
   *
   * @param repositoryId the repository to browse
   * @param continuationToken optional token to continue from a previous request
   * @param kind optional kind of assets to return
   * @param filter optional filter to apply
   * @param filterParams parameter map for the optional filter
   * @param limit maximum number of assets to return
   * @return collection of assets and the next continuation token
   *
   * @see Continuation#nextContinuationToken()
   */
  @Transactional
  public Continuation<Asset> browseAssets(final int repositoryId,
                                          @Nullable final String continuationToken,
                                          @Nullable final String kind,
                                          @Nullable final String filter,
                                          @Nullable final Map<String, Object> filterParams,
                                          final int limit)
  {
    return dao().browseAssets(repositoryId, limit, continuationToken, kind, filter, filterParams);
  }

  /**
   * Browse all assets in the given repositories in a paged fashion. The returned assets will be sorted
   * by asset id in ascending order.
   *
   * @param repositoryIds     the repositories to browse
   * @param continuationToken optional token to continue from a previous request
   * @param kind optional kind of assets to return
   * @param filter optional filter to apply
   * @param filterParams parameter map for the optional filter
   * @param limit             maximum number of assets to return
   * @return collection of assets and the next continuation token
   * @see Continuation#nextContinuationToken()
   *
   * @since 3.27
   */
  @Transactional
  public Continuation<Asset> browseAssets(
      final Set<Integer> repositoryIds,
      @Nullable final String continuationToken,
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams,
      final int limit)
  {
    return dao().browseAssetsInRepositories(repositoryIds, continuationToken, kind, filter, filterParams, limit);
  }

  /**
   * Browse all assets associated with the given logical component.
   *
   * @param component the component to browse
   * @return collection of assets
   */
  @Transactional
  public Collection<Asset> browseComponentAssets(final Component component) {
    return dao().browseComponentAssets(component);
  }

  /**
   * Find updated assets. The paging works differently because results are sorted by lastUpdated instead of id. Page
   * through results by passing the lastUpdated value from the last record in the Collection.
   *
   * @param repositoryId the repository to browse
   * @param lastUpdated lastUpdated from the last record of the previous call.
   * @param wildcardExpressions  list of wildcard expressions to match on path.
   *                             Supported special characters are * and ?
   * @param batchSize how many assets to fetch in each call. May return more assets than this if there are
   *                  multiple assets with the same lastUpdated value as the last record.
   * @return batch of updated assets
   */
  @Transactional
  public Collection<Asset> findUpdatedAssets(
      final int repositoryId,
      @Nullable final OffsetDateTime lastUpdated,
      final List<String> wildcardExpressions,
      final int batchSize)
  {
    List<String> pathExpressions = wildcardExpressions.stream().map(this::convertWildcardToLike).collect(Collectors.toList());

    // We consider dates the same if they are at the same millisecond. Normalization of the date plus using a >= query has
    // the effect of doing a > query as if the data in the database was truncated to the millisecond.
    OffsetDateTime lastUpdatedNormalized = null;
    if (lastUpdated != null) {
      lastUpdatedNormalized = lastUpdated.plus(1, ChronoUnit.MILLIS).truncatedTo(ChronoUnit.MILLIS);
    }

    // Fetch one extra record to check if there are more results with the same lastUpdated value. Most of the time
    // this won't be the case, and we will not need a query to find them all.
    List<Asset> assets = dao().findGreaterThanOrEqualToLastUpdated(repositoryId, lastUpdatedNormalized, pathExpressions, batchSize + 1);

    if (assets.size() == batchSize + 1) {
      if (hasMoreResultsWithSameLastUpdated(assets)) {
        Set<String> knownPaths = assets.stream().map(Asset::path).collect(Collectors.toSet());
        Asset lastAsset = assets.get(assets.size() - 1);

        OffsetDateTime startLastUpdated = lastAsset.lastUpdated().truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime endLastUpdated = startLastUpdated.plus(1, ChronoUnit.MILLIS);

        // Add all records that match the timestamp (truncating to millisecond) of the last record. Then we can continue
        // paging with a greater than query.
        List<Asset> matchLastUpdated =
            dao().findLastUpdatedWithinRange(repositoryId, startLastUpdated, endLastUpdated, pathExpressions, LAST_UPDATED_LIMIT);

        if (matchLastUpdated.size() == LAST_UPDATED_LIMIT) {
          log.error(
              "Found {} assets with identical last_updated value. Replication is skipping over additional assets with last_updated = {}",
              LAST_UPDATED_LIMIT, lastAsset.lastUpdated());
        }

        assets.addAll(
            matchLastUpdated.stream().filter(asset -> !knownPaths.contains(asset.path())).collect(Collectors.toList()));
      }
      else {
        // It's not safe to leave the extra record in. There may be more assets with same lastUpdated value as it.
        assets.remove(assets.size() - 1);
      }
    }

    return assets;
  }

  private boolean hasMoreResultsWithSameLastUpdated(final List<Asset> assets) {
    Asset lastResult = assets.get(assets.size() - 1);
    Asset secondToLastResult = assets.get(assets.size() - 2);
    return lastResult.lastUpdated().equals(secondToLastResult.lastUpdated());
  }

  private String convertWildcardToLike(final String wildcardExpression) {
    // Escape special characters for like statements if they exist in the wildcard expression (\, %, _)
    String escaped = wildcardExpression.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");

    String translated = escaped.replace("*", "%").replace("?", "_");
    return "%" + translated + "%";
  }

  /**
   * Creates the given asset in the content data store.
   *
   * @param asset the asset to create
   */
  @Transactional
  public void createAsset(final AssetData asset) {
    dao().createAsset(asset);

    postCommitEvent(() -> new AssetCreatedEvent(asset));
  }

  /**
   * Retrieves an asset from the content data store.
   *
   * @param assetId the internalId of the asset
   * @return asset if it was found
   */
  @Transactional
  public Optional<Asset> readAsset(final int assetId) {
    return dao().readAsset(assetId);
  }

  /**
   * Retrieves an asset located at the given path in the content data store.
   *
   * @param repositoryId the repository containing the asset
   * @param path the path of the asset
   * @return asset if it was found
   */
  @Transactional
  public Optional<Asset> readPath(final int repositoryId, final String path) {
    return dao().readPath(repositoryId, path);
  }

  /**
   * Retrieves an asset associated with the given blob ref.
   *
   * @param repositoryId the repository containing the asset
   * @param blobRef the blob ref
   * @return asset if it was found
   * @since 3.32
   */
  @Transactional
  public Optional<Asset> findByBlobRef(final int repositoryId, final BlobRef blobRef) {
    return dao().findByBlobRef(repositoryId, blobRef);
  }

  /**
   * Retrieves an assets associated with the given component ids.
   *
   * @param componentIds a set of component ids to search
   * @return collection of {@link AssetInfo}
   */
  @Transactional
  public Collection<AssetInfo> findByComponentIds(final Set<Integer> componentIds) {
    if (CollectionUtils.isEmpty(componentIds)) {
      return Collections.emptyList();
    }

    return dao().findByComponentIds(componentIds);
  }

  /**
   * Updates the kind of the given asset in the content data store.
   *
   * @param asset the asset to update
   *
   * @since 3.25
   */
  @Transactional
  public void updateAssetKind(final Asset asset) {
    dao().updateAssetKind(asset);

    postCommitEvent(() -> new AssetKindEvent(asset));
  }

  /**
   * Updates the attributes of the given asset in the content data store.
   *
   * @param asset the asset to update
   */
  @Transactional
  public void updateAssetAttributes(final Asset asset,
                                    final AttributeChangeSet changeSet)
  {
    // reload latest attributes, apply change, then update database if necessary
    dao().readAssetAttributes(asset).ifPresent(attributes -> {
      ((AssetData) asset).setAttributes(attributes);

      boolean changesApplied = changeSet.getChanges().stream()
          .map(change -> applyAttributeChange(attributes, change))
          .reduce((a, b) -> a || b)
          .orElse(false);
      if (changesApplied) {
        dao().updateAssetAttributes(asset);

        postCommitEvent(() -> new AssetAttributesEvent(asset, changeSet.getChanges()));
      }
    });
  }

  /**
   * Updates the link between the given asset and its {@link AssetBlob} in the content data store.
   *
   * @param asset the asset to update
   */
  @Transactional
  public void updateAssetBlobLink(final Asset asset) {
    dao().updateAssetBlobLink(asset);

    postCommitEvent(() -> new AssetUploadedEvent(asset));
  }

  /**
   * Updates the last downloaded time of the given asset in the content data store.
   *
   * @param asset the asset to update
   */
  @Transactional
  public void markAsDownloaded(final Asset asset) {
    dao().markAsDownloaded(asset);

    postCommitEvent(() -> new AssetDownloadedEvent(asset));
  }

  /**
   * Deletes an asset from the content data store.
   *
   * @param asset the asset to delete
   * @return {@code true} if the asset was deleted
   */
  @Transactional
  public boolean deleteAsset(final Asset asset) {
    preCommitEvent(() -> new AssetPreDeleteEvent(asset));
    postCommitEvent(() -> new AssetDeletedEvent(asset));

    return dao().deleteAsset(asset);
  }

  /**
   * Deletes the asset located at the given path in the content data store.
   *
   * @param repositoryId the repository containing the asset
   * @param path the path of the asset
   * @return {@code true} if the asset was deleted
   */
  @Transactional
  public boolean deletePath(final int repositoryId, final String path) {
    return dao().readPath(repositoryId, path)
        .map(this::deleteAsset)
        .orElse(false);
  }

  /**
   * Deletes the assets located at the given paths in the content data store.
   *
   * @param repositoryId the repository containing the assets
   * @param paths the paths of the assets to delete
   * @return number of assets deleted
   */
  @Transactional
  public int deleteAssetsByPaths(final int repositoryId, final List<String> paths) {
    if (paths.isEmpty()) {
      return 0;
    }

    Collection<Asset> assets = dao().readPathsFromRepository(repositoryId, paths);

    if(assets.isEmpty()){
      return 0;
    }

    int[] assetIds = assets.stream().mapToInt(InternalIds::internalAssetId).toArray();
    preCommitEvent(() -> new AssetPrePurgeEvent(repositoryId, assetIds));
    postCommitEvent(() -> new AssetPurgedEvent(repositoryId, assetIds));

    return purgeAssets(assetIds);
  }

  /**
   * Deletes all assets in the given repository from the content data store.
   *
   * Events will not be sent for these deletes, instead listen for {@link ContentRepositoryDeletedEvent}.
   *
   * @param repositoryId the repository containing the assets
   * @return {@code true} if any assets were deleted
   */
  @Transactional
  public boolean deleteAssets(final int repositoryId) {
    log.debug("Deleting all assets in repository {}", repositoryId);
    boolean deleted = false;
    while (dao().deleteAssets(repositoryId, deleteBatchSize())) {
      commitChangesSoFar();
      deleted = true;
    }
    log.debug("Deleted all assets in repository {}", repositoryId);
    return deleted;
  }

  /**
   * Purge assets without a component in the given repository last downloaded more than given number of days ago
   *
   * @param repositoryId the repository to check
   * @param daysAgo the number of days ago to check
   * @return number of purged assets
   *
   * @since 3.24
   */
  @Transactional
  public int purgeNotRecentlyDownloaded(final int repositoryId, final int daysAgo) {
    int purged = 0;
    while (true) {
      int[] assetIds = dao().selectNotRecentlyDownloaded(repositoryId, daysAgo, deleteBatchSize());
      if (assetIds.length == 0) {
        break; // nothing left to purge
      }
      purged += purgeAssets(assetIds);

      preCommitEvent(() -> new AssetPrePurgeEvent(repositoryId, assetIds));
      postCommitEvent(() -> new AssetPurgedEvent(repositoryId, assetIds));

      commitChangesSoFar();
    }
    return purged;
  }

  /**
   * Generally it is recommended that this method not be called and let stores manage this value automatically.
   *
   * Sets the created time of the asset associated with the ID to the specified time.
   *
   * @since 3.29
   */
  @Transactional
  public void created(final FluentAssetImpl asset, final OffsetDateTime created) {
    dao().created(InternalIds.internalAssetId(asset), created);
  }

  /**
   * Generally it is recommended that this method not be called and let stores manage this value automatically.
   *
   * Sets the last download time of the asset associated with the ID to the specified time.
   *
   * @since 3.29
   */
  @Transactional
  public void lastDownloaded(final Asset asset, final OffsetDateTime lastDownloaded) {
    dao().lastDownloaded(InternalIds.internalAssetId(asset), lastDownloaded);
  }

  /**
   * Generally it is recommended that this method not be called and let stores manage this value automatically.
   *
   * Sets the last updated time of the asset associated with the ID to the specified time.
   *
   * @since 3.29
   */
  @Transactional
  public void lastUpdated(final Asset asset, final OffsetDateTime lastUpdated) {
    dao().lastUpdated(InternalIds.internalAssetId(asset), lastUpdated);
  }

  private int purgeAssets(final int[] assetIds) {
    if ("H2".equals(thisSession().sqlDialect())) {
      // workaround lack of primitive array support in H2 (should be fixed in H2 1.4.201?)
      return dao().purgeSelectedAssets(stream(assetIds).boxed().toArray(Integer[]::new));
    }
    else {
      return dao().purgeSelectedAssets(assetIds);
    }
  }
}
