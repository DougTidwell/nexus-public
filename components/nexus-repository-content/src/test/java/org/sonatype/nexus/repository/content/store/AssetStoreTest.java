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
import java.util.Collection;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentFacetFinder;
import org.sonatype.nexus.repository.content.store.example.TestAssetDAO;
import org.sonatype.nexus.repository.content.store.example.TestContentRepositoryDAO;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.core.Is.is;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

public class AssetStoreTest
    extends ExampleContentTestSupport
{
  @Mock
  private ContentFacetFinder contentFacetFinder;

  @Mock
  private EventManager eventManager;

  private AssetStore<TestAssetDAO> underTest = null;

  private int repositoryId;

  @Before
  public void setup() {
    ContentRepositoryData contentRepository = randomContentRepository();
    createContentRepository(contentRepository);
    repositoryId = contentRepository.repositoryId;

    generateRandomNamespaces(4);
    generateRandomNames(4);
    generateRandomVersions(4);

    underTest = new AssetStore<>(sessionRule, "test", TestAssetDAO.class);
    underTest.setDependencies(contentFacetFinder, eventManager);
  }

  private void createContentRepository(final ContentRepositoryData contentRepository) {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      ContentRepositoryDAO dao = session.access(TestContentRepositoryDAO.class);
      dao.createContentRepository(contentRepository);
      session.getTransaction().commit();
    }
  }

  @Test
  public void testBrowseUpdatedAssetsDifferentDates() {
    OffsetDateTime time = OffsetDateTime.now();
    AssetData asset1 = generateAsset(repositoryId, "/asset1/asset1.jar");
    asset1.setLastUpdated(time);
    AssetData asset2 = generateAsset(repositoryId, "/asset2/asset2.jar");
    asset2.setLastUpdated(time.plusSeconds(1));
    AssetData asset3 = generateAsset(repositoryId, "/asset3/asset3.jar");
    asset3.setLastUpdated(time.plusSeconds(2));
    AssetData asset4 = generateAsset(repositoryId, "/asset4/asset4.jar");
    asset4.setLastUpdated(time.plusSeconds(3));
    AssetData asset5 = generateAsset(repositoryId, "/asset5/asset5.jar");
    asset5.setLastUpdated(time.plusSeconds(4));
    AssetData asset6 = generateAsset(repositoryId, "/asset6/asset6.jar");
    asset6.setLastUpdated(time.plusSeconds(5));
    AssetData asset7 = generateAsset(repositoryId, "/asset7_asset7%jar");
    asset7.setLastUpdated(time.plusSeconds(6));

    inTx(() -> {
      Collection<Asset> assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(), 2);
      assertThat(assets, is(empty()));

      underTest.createAsset(asset1);
      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(), 2);
      assertThat(assets.size(), is(1));

      underTest.createAsset(asset2);
      underTest.createAsset(asset3);

      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(),2);
      assertThat(assets.size(), is(2));

      underTest.createAsset(asset4);
      underTest.createAsset(asset5);
      underTest.createAsset(asset6);

      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of("asset5"),100);
      assertThat(assets.size(), is(1));

      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of("asset4", "asset5"),100);
      assertThat(assets.size(), is(2));

      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of("/asset?/a*.jar"),100);
      assertThat(assets.size(), is(6));

      underTest.createAsset(asset7);
      // _ and % should be interpreted literally here so only asset7 will match
      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of("/asset?_a*%jar"),100);
      assertThat(assets.size(), is(1));
    });
  }

  @Test
  public void testBrowseUpdatedAssetsIdenticalDates() {
    OffsetDateTime time = OffsetDateTime.now();
    AssetData asset1 = generateAsset(repositoryId, "/asset1/asset1.jar");
    asset1.setLastUpdated(time);
    AssetData asset2 = generateAsset(repositoryId, "/asset2/asset2.jar");
    asset2.setLastUpdated(time);
    AssetData asset3 = generateAsset(repositoryId, "/asset3/asset3.jar");
    asset3.setLastUpdated(time);
    AssetData asset4 = generateAsset(repositoryId, "/asset4/asset4.jar");
    asset4.setLastUpdated(time);

    inTx(() -> {
      underTest.createAsset(asset1);
      underTest.createAsset(asset2);
      Collection<Asset> assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(),2);
      assertThat(assets.size(), is(2));

      underTest.createAsset(asset3);
      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(),2);
      assertThat(assets.size(), is(3));

      underTest.createAsset(asset4);
      assets = underTest.findUpdatedAssets(repositoryId, null, ImmutableList.of(),2);
      assertThat(assets.size(), is(4));
    });
  }

  private void inTx(final Runnable action) {
    UnitOfWork.begin(() -> sessionRule.openSession(DEFAULT_DATASTORE_NAME));
    try {
      Transactional.operation.run(action::run);
    }
    finally {
      UnitOfWork.end();
    }
  }
}
