// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewers;

import static java.util.stream.Collectors.toSet;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.RevisionEvent;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ReviewerSuggestion;
import com.google.gerrit.server.change.SuggestedReviewer;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class Reviewers implements RevisionCreatedListener, ReviewerSuggestion {
  private static final Logger log = LoggerFactory.getLogger(Reviewers.class);

  private final ReviewersResolver resolver;
  private final AddReviewersByConfiguration.Factory byConfigFactory;
  private final WorkQueue workQueue;
  private final ReviewersConfig config;
  private final Provider<CurrentUser> user;
  private final ChangeQueryBuilder queryBuilder;
  private final Provider<InternalChangeQuery> queryProvider;

  @Inject
  Reviewers(
      ReviewersResolver resolver,
      AddReviewersByConfiguration.Factory byConfigFactory,
      WorkQueue workQueue,
      ReviewersConfig config,
      Provider<CurrentUser> user,
      ChangeQueryBuilder queryBuilder,
      Provider<InternalChangeQuery> queryProvider) {
    this.resolver = resolver;
    this.byConfigFactory = byConfigFactory;
    this.workQueue = workQueue;
    this.config = config;
    this.user = user;
    this.queryBuilder = queryBuilder;
    this.queryProvider = queryProvider;
  }

  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    onEvent(event);
  }

  @Override
  public Set<SuggestedReviewer> suggestReviewers(
      Project.NameKey projectName,
      @Nullable Change.Id changeId,
      @Nullable String query,
      Set<Account.Id> candidates) {
    List<ReviewerFilterSection> sections = getSections(projectName);

    if (sections.isEmpty() || changeId == null) {
      return ImmutableSet.of();
    }

    try {
      Set<String> reviewers = findReviewers(changeId.get(), sections);
      if (!reviewers.isEmpty()) {
        return resolver
            .resolve(reviewers, projectName, changeId.get(), null)
            .stream()
            .map(a -> suggestedReviewer(a))
            .collect(toSet());
      }
    } catch (OrmException | QueryParseException x) {
      log.error(x.getMessage(), x);
    }
    return ImmutableSet.of();
  }

  private SuggestedReviewer suggestedReviewer(Account.Id account) {
    SuggestedReviewer reviewer = new SuggestedReviewer();
    reviewer.account = account;
    reviewer.score = 1;
    return reviewer;
  }

  private List<ReviewerFilterSection> getSections(Project.NameKey projectName) {
    // TODO(davido): we have to cache per project configuration
    return config.forProject(projectName).getReviewerFilterSections();
  }

  private void onEvent(RevisionEvent event) {
    ChangeInfo c = event.getChange();
    Project.NameKey projectName = new Project.NameKey(c.project);

    List<ReviewerFilterSection> sections = getSections(projectName);

    if (sections.isEmpty()) {
      return;
    }

    AccountInfo uploader = event.getWho();
    int changeNumber = c._number;
    try {
      Set<String> reviewers = findReviewers(changeNumber, sections);
      if (reviewers.isEmpty()) {
        return;
      }
      final Runnable task =
          byConfigFactory.create(
              c, resolver.resolve(reviewers, projectName, changeNumber, uploader));

      workQueue.getDefaultQueue().submit(task);
    } catch (QueryParseException e) {
      log.warn(
          "Could not add default reviewers for change {} of project {}, filter is invalid: {}",
          changeNumber,
          projectName.get(),
          e.getMessage());
    } catch (OrmException x) {
      log.error(x.getMessage(), x);
    }
  }

  private Set<String> findReviewers(int change, List<ReviewerFilterSection> sections)
      throws OrmException, QueryParseException {
    ImmutableSet.Builder<String> reviewers = ImmutableSet.builder();
    List<ReviewerFilterSection> found = findReviewerSections(change, sections);
    for (ReviewerFilterSection s : found) {
      reviewers.addAll(s.getReviewers());
    }
    return reviewers.build();
  }

  private List<ReviewerFilterSection> findReviewerSections(
      int change, List<ReviewerFilterSection> sections) throws OrmException, QueryParseException {
    ImmutableList.Builder<ReviewerFilterSection> found = ImmutableList.builder();
    for (ReviewerFilterSection s : sections) {
      if (Strings.isNullOrEmpty(s.getFilter()) || s.getFilter().equals("*")) {
        found.add(s);
      } else if (filterMatch(change, s.getFilter())) {
        found.add(s);
      }
    }
    return found.build();
  }

  boolean filterMatch(int change, String filter) throws OrmException, QueryParseException {
    Preconditions.checkNotNull(filter);
    ChangeQueryBuilder qb = queryBuilder.asUser(user.get());
    return !queryProvider
        .get()
        .noFields()
        .query(qb.parse(String.format("change:%s %s", change, filter)))
        .isEmpty();
  }
}
