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
package org.sonatype.nexus.repository.search.sql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.search.query.SearchFilter;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

public class SqlSearchUtilsTest
    extends TestSupport
{
  private static final String RAW_FORMAT = "raw";

  @Mock
  private SqlSearchQueryContribution defaultSearchContribution;

  private SqlSearchUtils underTest;

  @Before
  public void setup() {
    Map<String, SqlSearchQueryContribution> searchContributions = new HashMap<>();
    searchContributions.put(DefaultSqlSearchQueryContribution.NAME, defaultSearchContribution);
    underTest = new SqlSearchUtils(searchContributions);
  }

  @Test
  public void shouldBuildQueryConditionsForSearchFilters() {

    List<SearchFilter> searchFilters = asList(new SearchFilter("group.raw", "junit org.mockito"),
        new SearchFilter("version", "4.13 3.2.0"));

    final SqlSearchQueryBuilder queryBuilder = underTest.buildQuery(searchFilters);

    assertThat(queryBuilder, is(notNullValue()));
    verify(defaultSearchContribution).contribute(any(SqlSearchQueryBuilder.class), eq(searchFilters.get(0)));
    verify(defaultSearchContribution).contribute(any(SqlSearchQueryBuilder.class), eq(searchFilters.get(1)));
  }

  @Test
  public void shouldFindFormatFilter() {
    List<SearchFilter> searchFilters = asList(new SearchFilter("group.raw", "junit org.mockito"),
        new SearchFilter("format", RAW_FORMAT));

    Optional<String> format = underTest.getFormat(searchFilters);

    assertThat(format.isPresent(), is(true));
    assertThat(format.get(), is(RAW_FORMAT));
  }

  @Test
  public void shouldFindRepositoryNameFilter() {
    SearchFilter repositoryNameFilter = new SearchFilter("repository_name", "raw-hosted raw-proxy");
    List<SearchFilter> searchFilters = asList(new SearchFilter("group.raw", "junit org.mockito"),
        repositoryNameFilter);

    Optional<SearchFilter> repositoryFilter = underTest.getRepositoryFilter(searchFilters);

    assertThat(repositoryFilter.isPresent(), is(true));
    assertThat(repositoryFilter.get(), is(repositoryNameFilter));
  }
}
