/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.purge;

import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.lang.math.RandomUtils;
import org.apache.commons.lang.time.DateUtils;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.issue.Issue;
import org.sonar.api.utils.System2;
import org.sonar.core.util.CloseableIterator;
import org.sonar.core.util.UuidFactoryFast;
import org.sonar.core.util.Uuids;
import org.sonar.db.DbInputStream;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.alm.setting.AlmSettingDto;
import org.sonar.db.ce.CeActivityDto;
import org.sonar.db.ce.CeQueueDto;
import org.sonar.db.ce.CeQueueDto.Status;
import org.sonar.db.ce.CeTaskCharacteristicDto;
import org.sonar.db.ce.CeTaskMessageDto;
import org.sonar.db.ce.CeTaskMessageType;
import org.sonar.db.ce.CeTaskTypes;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.event.EventComponentChangeDto;
import org.sonar.db.event.EventDto;
import org.sonar.db.event.EventTesting;
import org.sonar.db.issue.IssueChangeDto;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.measure.LiveMeasureDto;
import org.sonar.db.measure.MeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.newcodeperiod.NewCodePeriodDto;
import org.sonar.db.newcodeperiod.NewCodePeriodType;
import org.sonar.db.portfolio.PortfolioProjectDto;
import org.sonar.db.project.ProjectDto;
import org.sonar.db.property.PropertyDto;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.source.FileSourceDto;
import org.sonar.db.user.UserDismissedMessageDto;
import org.sonar.db.user.UserDto;
import org.sonar.db.webhook.WebhookDeliveryLiteDto;
import org.sonar.db.webhook.WebhookDto;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.time.ZoneOffset.UTC;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sonar.db.ce.CeTaskTypes.REPORT;
import static org.sonar.db.component.ComponentTesting.newBranchDto;
import static org.sonar.db.component.ComponentTesting.newDirectory;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.component.ComponentTesting.newModuleDto;
import static org.sonar.db.component.ComponentTesting.newProjectCopy;
import static org.sonar.db.component.ComponentTesting.newSubPortfolio;
import static org.sonar.db.component.SnapshotDto.STATUS_PROCESSED;
import static org.sonar.db.component.SnapshotDto.STATUS_UNPROCESSED;
import static org.sonar.db.component.SnapshotTesting.newSnapshot;
import static org.sonar.db.event.EventDto.CATEGORY_VERSION;
import static org.sonar.db.issue.IssueTesting.newCodeReferenceIssue;
import static org.sonar.db.webhook.WebhookDeliveryTesting.newDto;
import static org.sonar.db.webhook.WebhookDeliveryTesting.selectAllDeliveryUuids;

public class PurgeDaoTest {

  private static final String PROJECT_UUID = "P1";

  private final System2 system2 = mock(System2.class);

  @Rule
  public DbTester db = DbTester.create(system2);

  private final DbClient dbClient = db.getDbClient();
  private final DbSession dbSession = db.getSession();
  private final PurgeDao underTest = db.getDbClient().purgeDao();

  @Test
  public void purge_failed_ce_tasks() {
    ComponentDto project = db.components().insertPrivateProject();
    SnapshotDto pastAnalysis = db.components().insertSnapshot(project, t -> t.setStatus(STATUS_PROCESSED).setLast(false));
    db.components().insertSnapshot(project, t -> t.setStatus(STATUS_UNPROCESSED).setLast(false));
    SnapshotDto lastAnalysis = db.components().insertSnapshot(project, t -> t.setStatus(STATUS_PROCESSED).setLast(true));

    underTest.purge(dbSession, newConfigurationWith30Days(project.uuid()), PurgeListener.EMPTY, new PurgeProfiler());
    dbSession.commit();

    assertThat(uuidsOfAnalysesOfRoot(project)).containsOnly(pastAnalysis.getUuid(), lastAnalysis.getUuid());
  }

  @Test
  public void purge_inactive_branches() {
    when(system2.now()).thenReturn(new Date().getTime());
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto branch1 = db.components().insertProjectBranch(project);
    ComponentDto branch2 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH));
    ComponentDto pr1 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST));

    db.components().insertSnapshot(branch1);
    db.components().insertSnapshot(branch2);
    db.components().insertSnapshot(pr1);

    // branch with other components and issues, last analysed 31 days ago
    ComponentDto branch3 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH).setExcludeFromPurge(false));
    addComponentsSnapshotsAndIssuesToBranch(branch3, rule, 31);

    // branch with no analysis
    ComponentDto branch4 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH));

    // branch last analysed 31 days ago but protected from purge
    ComponentDto branch5 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH).setExcludeFromPurge(true));
    db.components().insertSnapshot(branch5, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -31).getTime()));

    // pull request last analysed 100 days ago
    ComponentDto pr2 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST).setExcludeFromPurge(false));
    addComponentsSnapshotsAndIssuesToBranch(pr2, rule, 100);

    // pull request last analysed 100 days ago but marked as "excluded from purge" which should not work for pull requests
    ComponentDto pr3 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST).setExcludeFromPurge(true));
    addComponentsSnapshotsAndIssuesToBranch(pr3, rule, 100);

    underTest.purge(dbSession, newConfigurationWith30Days(System2.INSTANCE, project.uuid(), project.uuid()), PurgeListener.EMPTY, new PurgeProfiler());
    dbSession.commit();

    assertThat(uuidsIn("components")).containsOnly(
      project.uuid(), branch1.uuid(), branch2.uuid(), branch5.uuid(), pr1.uuid());
    assertThat(uuidsIn("projects")).containsOnly(project.uuid());
  }

  @Test
  public void purge_inactive_pull_request() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto nonMainBranch = db.components().insertProjectBranch(project);
    db.components().insertSnapshot(nonMainBranch);
    ComponentDto recentPullRequest = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST));
    db.components().insertSnapshot(recentPullRequest);

    // pull request with other components and issues, updated 31 days ago
    ComponentDto pullRequest = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST));
    db.components().insertSnapshot(pullRequest, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -31).getTime()));
    ComponentDto module = db.components().insertComponent(newModuleDto(pullRequest));
    ComponentDto subModule = db.components().insertComponent(newModuleDto(module));
    ComponentDto file = db.components().insertComponent(newFileDto(subModule));
    db.issues().insert(rule, pullRequest, file);
    db.issues().insert(rule, pullRequest, subModule);
    db.issues().insert(rule, pullRequest, module);

    underTest.purge(dbSession, newConfigurationWith30Days(System2.INSTANCE, project.uuid(), project.uuid()), PurgeListener.EMPTY, new PurgeProfiler());
    dbSession.commit();

    assertThat(uuidsIn("components")).containsOnly(project.uuid(), nonMainBranch.uuid(), recentPullRequest.uuid());
    assertThat(uuidsIn("projects")).containsOnly(project.uuid());
  }

  @Test
  public void purge_inactive_branches_when_analyzing_non_main_branch() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto nonMainBranch = db.components().insertProjectBranch(project);
    db.components().insertSnapshot(nonMainBranch);

    // branch updated 31 days ago
    ComponentDto branch1 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH));
    db.components().insertSnapshot(branch1, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -31).getTime()));

    // branches with other components and issues, updated 31 days ago
    ComponentDto branch2 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.PULL_REQUEST));
    db.components().insertSnapshot(branch2, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -31).getTime()));
    ComponentDto file = db.components().insertComponent(newFileDto(branch2));
    db.issues().insert(rule, branch2, file);

    ComponentDto branch3 = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.BRANCH));
    db.components().insertSnapshot(branch3, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -31).getTime()));

    // properties exist or active and for inactive branch
    insertPropertyFor(branch3, branch1);

    // analysing branch1
    underTest.purge(dbSession, newConfigurationWith30Days(System2.INSTANCE, branch1.uuid(), branch1.getMainBranchProjectUuid()), PurgeListener.EMPTY, new PurgeProfiler());
    dbSession.commit();

    // branch1 wasn't deleted since it was being analyzed!
    assertThat(uuidsIn("components")).containsOnly(project.uuid(), nonMainBranch.uuid(), branch1.uuid());
    assertThat(uuidsIn("projects")).containsOnly(project.uuid());
    assertThat(componentUuidsIn("properties")).containsOnly(branch1.uuid());
  }

  @Test
  public void close_issues_clean_index_and_file_sources_of_disabled_components_specified_by_uuid_in_configuration() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();
    db.components().insertSnapshot(project);
    db.components().insertSnapshot(project);
    db.components().insertSnapshot(project, s -> s.setLast(false));

    ComponentDto module = db.components().insertComponent(newModuleDto(project).setEnabled(false));
    ComponentDto dir = db.components().insertComponent(newDirectory(module, "sub").setEnabled(false));
    ComponentDto srcFile = db.components().insertComponent(newFileDto(module, dir).setEnabled(false));
    ComponentDto testFile = db.components().insertComponent(newFileDto(module, dir).setEnabled(false));
    ComponentDto enabledFile = db.components().insertComponent(newFileDto(module, dir).setEnabled(true));
    IssueDto openOnFile = db.issues().insert(rule, project, srcFile, issue -> issue.setStatus("OPEN"));
    IssueDto confirmOnFile = db.issues().insert(rule, project, srcFile, issue -> issue.setStatus("CONFIRM"));
    IssueDto openOnDir = db.issues().insert(rule, project, dir, issue -> issue.setStatus("OPEN"));
    IssueDto confirmOnDir = db.issues().insert(rule, project, dir, issue -> issue.setStatus("CONFIRM"));
    IssueDto openOnEnabledComponent = db.issues().insert(rule, project, enabledFile, issue -> issue.setStatus("OPEN"));
    IssueDto confirmOnEnabledComponent = db.issues().insert(rule, project, enabledFile, issue -> issue.setStatus("CONFIRM"));

    assertThat(db.countSql("select count(*) from snapshots where purge_status = 1")).isZero();

    assertThat(db.countSql("select count(*) from issues where status = 'CLOSED'")).isZero();
    assertThat(db.countSql("select count(*) from issues where resolution = 'REMOVED'")).isZero();

    db.fileSources().insertFileSource(srcFile);
    FileSourceDto nonSelectedFileSource = db.fileSources().insertFileSource(enabledFile);
    assertThat(db.countRowsOfTable("file_sources")).isEqualTo(2);

    MetricDto metric1 = db.measures().insertMetric();
    MetricDto metric2 = db.measures().insertMetric();
    LiveMeasureDto liveMeasureMetric1OnFile = db.measures().insertLiveMeasure(srcFile, metric1);
    LiveMeasureDto liveMeasureMetric2OnFile = db.measures().insertLiveMeasure(srcFile, metric2);
    LiveMeasureDto liveMeasureMetric1OnDir = db.measures().insertLiveMeasure(dir, metric1);
    LiveMeasureDto liveMeasureMetric2OnDir = db.measures().insertLiveMeasure(dir, metric2);
    LiveMeasureDto liveMeasureMetric1OnProject = db.measures().insertLiveMeasure(project, metric1);
    LiveMeasureDto liveMeasureMetric2OnProject = db.measures().insertLiveMeasure(project, metric2);
    LiveMeasureDto liveMeasureMetric1OnNonSelected = db.measures().insertLiveMeasure(enabledFile, metric1);
    LiveMeasureDto liveMeasureMetric2OnNonSelected = db.measures().insertLiveMeasure(enabledFile, metric2);
    assertThat(db.countRowsOfTable("live_measures")).isEqualTo(8);
    PurgeListener purgeListener = mock(PurgeListener.class);

    // back to present
    Set<String> selectedComponentUuids = ImmutableSet.of(module.uuid(), srcFile.uuid(), testFile.uuid());
    underTest.purge(dbSession, newConfigurationWith30Days(system2, project.uuid(), project.uuid(), selectedComponentUuids),
      purgeListener, new PurgeProfiler());
    dbSession.commit();

    verify(purgeListener).onComponentsDisabling(project.uuid(), selectedComponentUuids);
    verify(purgeListener).onComponentsDisabling(project.uuid(), ImmutableSet.of(dir.uuid()));

    // set purge_status=1 for non-last snapshot
    assertThat(db.countSql("select count(*) from snapshots where purge_status = 1")).isOne();

    // close open issues of selected
    assertThat(db.countSql("select count(*) from issues where status = 'CLOSED'")).isEqualTo(4);
    for (IssueDto issue : Arrays.asList(openOnFile, confirmOnFile, openOnDir, confirmOnDir)) {
      assertThat(db.getDbClient().issueDao().selectOrFailByKey(dbSession, issue.getKey()))
        .extracting(IssueDto::getStatus, IssueDto::getResolution)
        .containsExactlyInAnyOrder("CLOSED", "REMOVED");
    }
    for (IssueDto issue : Arrays.asList(openOnEnabledComponent, confirmOnEnabledComponent)) {
      assertThat(db.getDbClient().issueDao().selectByKey(dbSession, issue.getKey()).get())
        .extracting("status", "resolution")
        .containsExactlyInAnyOrder(issue.getStatus(), null);
    }

    // delete file sources of selected
    assertThat(db.countRowsOfTable("file_sources")).isOne();
    assertThat(db.getDbClient().fileSourceDao().selectByFileUuid(dbSession, nonSelectedFileSource.getFileUuid())).isNotNull();

    // deletes live measure of selected
    assertThat(db.countRowsOfTable("live_measures")).isEqualTo(4);
    List<LiveMeasureDto> liveMeasureDtos = db.getDbClient().liveMeasureDao()
      .selectByComponentUuidsAndMetricUuids(dbSession, ImmutableSet.of(srcFile.uuid(), dir.uuid(), project.uuid(), enabledFile.uuid()),
        ImmutableSet.of(metric1.getUuid(), metric2.getUuid()));
    assertThat(liveMeasureDtos)
      .extracting(LiveMeasureDto::getComponentUuid)
      .containsOnly(enabledFile.uuid(), project.uuid());
    assertThat(liveMeasureDtos)
      .extracting(LiveMeasureDto::getMetricUuid)
      .containsOnly(metric1.getUuid(), metric2.getUuid());
  }

  @Test
  public void shouldDeleteAnalyses() {
    ComponentDto project = db.components().insertPrivateProject();
    SnapshotDto analysis1 = db.components().insertSnapshot(project);
    SnapshotDto analysis2 = db.components().insertSnapshot(project);
    SnapshotDto analysis3 = db.components().insertSnapshot(project);
    ComponentDto otherProject = db.components().insertPrivateProject();
    SnapshotDto otherAnalysis1 = db.components().insertSnapshot(otherProject);

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList(analysis1.getUuid()));

    assertThat(uuidsIn("snapshots")).containsOnly(analysis2.getUuid(), analysis3.getUuid(), otherAnalysis1.getUuid());

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), asList(analysis1.getUuid(), analysis3.getUuid(), otherAnalysis1.getUuid()));

    assertThat(uuidsIn("snapshots")).containsOnly(analysis2.getUuid());
  }

  @Test
  public void deleteAnalyses_deletes_rows_in_events_and_event_component_changes() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project);
    SnapshotDto projectAnalysis1 = db.components().insertSnapshot(project);
    SnapshotDto projectAnalysis2 = db.components().insertSnapshot(project);
    SnapshotDto projectAnalysis3 = db.components().insertSnapshot(project);
    SnapshotDto projectAnalysis4 = db.components().insertSnapshot(project);
    EventDto projectEvent1 = db.events().insertEvent(projectAnalysis1);
    EventDto projectEvent2 = db.events().insertEvent(projectAnalysis2);
    EventDto projectEvent3 = db.events().insertEvent(projectAnalysis3);
    // note: projectAnalysis4 has no event
    ComponentDto referencedProjectA = db.components().insertPublicProject();
    ComponentDto referencedProjectB = db.components().insertPublicProject();
    db.events().insertEventComponentChanges(projectEvent1, projectAnalysis1, randomChangeCategory(), referencedProjectA, null);
    db.events().insertEventComponentChanges(projectEvent1, projectAnalysis1, randomChangeCategory(), referencedProjectB, null);
    BranchDto branchProjectA = newBranchDto(referencedProjectA);
    ComponentDto cptBranchProjectA = ComponentTesting.newBranchComponent(referencedProjectA, branchProjectA);
    db.events().insertEventComponentChanges(projectEvent2, projectAnalysis2, randomChangeCategory(), cptBranchProjectA, branchProjectA);
    // note: projectEvent3 has no component change

    // delete non existing analysis has no effect
    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList("foo"));
    assertThat(uuidsIn("event_component_changes", "event_analysis_uuid"))
      .containsOnly(projectAnalysis1.getUuid(), projectAnalysis2.getUuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isEqualTo(3);
    assertThat(uuidsIn("events"))
      .containsOnly(projectEvent1.getUuid(), projectEvent2.getUuid(), projectEvent3.getUuid());

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList(projectAnalysis1.getUuid()));
    assertThat(uuidsIn("event_component_changes", "event_analysis_uuid"))
      .containsOnly(projectAnalysis2.getUuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isOne();
    assertThat(uuidsIn("events"))
      .containsOnly(projectEvent2.getUuid(), projectEvent3.getUuid());

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList(projectAnalysis4.getUuid()));
    assertThat(uuidsIn("event_component_changes", "event_analysis_uuid"))
      .containsOnly(projectAnalysis2.getUuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isOne();
    assertThat(uuidsIn("events"))
      .containsOnly(projectEvent2.getUuid(), projectEvent3.getUuid());

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList(projectAnalysis3.getUuid()));
    assertThat(uuidsIn("event_component_changes", "event_analysis_uuid"))
      .containsOnly(projectAnalysis2.getUuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isOne();
    assertThat(uuidsIn("events"))
      .containsOnly(projectEvent2.getUuid());

    underTest.deleteAnalyses(dbSession, new PurgeProfiler(), singletonList(projectAnalysis2.getUuid()));
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isZero();
    assertThat(db.countRowsOfTable("events"))
      .isZero();
  }

  @Test
  public void selectPurgeableAnalyses() {
    SnapshotDto[] analyses = new SnapshotDto[] {
      newSnapshot()
        .setUuid("u1")
        .setComponentUuid(PROJECT_UUID)
        .setStatus(STATUS_PROCESSED)
        .setLast(true),
      // not processed -> exclude
      newSnapshot()
        .setUuid("u2")
        .setComponentUuid(PROJECT_UUID)
        .setStatus(STATUS_UNPROCESSED)
        .setLast(false),
      // on other resource -> exclude
      newSnapshot()
        .setUuid("u3")
        .setComponentUuid("uuid_222")
        .setStatus(STATUS_PROCESSED)
        .setLast(true),
      // without event -> select
      newSnapshot()
        .setUuid("u4")
        .setComponentUuid(PROJECT_UUID)
        .setStatus(STATUS_PROCESSED)
        .setLast(false),
      // with event -> select
      newSnapshot()
        .setUuid("u5")
        .setComponentUuid(PROJECT_UUID)
        .setStatus(STATUS_PROCESSED)
        .setLast(false)
        .setProjectVersion("V5")
    };
    db.components().insertSnapshots(analyses);
    db.events().insertEvent(EventTesting.newEvent(analyses[4])
      .setName("V5")
      .setCategory(CATEGORY_VERSION));

    List<PurgeableAnalysisDto> purgeableAnalyses = underTest.selectPurgeableAnalyses(PROJECT_UUID, dbSession);

    assertThat(purgeableAnalyses).hasSize(3);
    assertThat(getById(purgeableAnalyses, "u1").isLast()).isTrue();
    assertThat(getById(purgeableAnalyses, "u1").hasEvents()).isFalse();
    assertThat(getById(purgeableAnalyses, "u1").getVersion()).isNull();
    assertThat(getById(purgeableAnalyses, "u4").isLast()).isFalse();
    assertThat(getById(purgeableAnalyses, "u4").hasEvents()).isFalse();
    assertThat(getById(purgeableAnalyses, "u4").getVersion()).isNull();
    assertThat(getById(purgeableAnalyses, "u5").isLast()).isFalse();
    assertThat(getById(purgeableAnalyses, "u5").hasEvents()).isTrue();
    assertThat(getById(purgeableAnalyses, "u5").getVersion()).isEqualTo("V5");
  }

  @Test
  public void selectPurgeableAnalyses_does_not_return_the_baseline() {
    ComponentDto project1 = db.components().insertPublicProject("master");
    SnapshotDto analysis1 = db.components().insertSnapshot(newSnapshot()
      .setComponentUuid(project1.uuid())
      .setStatus(STATUS_PROCESSED)
      .setLast(false));
    dbClient.newCodePeriodDao().insert(dbSession,
      new NewCodePeriodDto()
        .setProjectUuid(project1.uuid())
        .setBranchUuid(project1.uuid())
        .setType(NewCodePeriodType.SPECIFIC_ANALYSIS)
        .setValue(analysis1.getUuid()));
    ComponentDto project2 = db.components().insertPrivateProject();
    SnapshotDto analysis2 = db.components().insertSnapshot(newSnapshot()
      .setComponentUuid(project2.uuid())
      .setStatus(STATUS_PROCESSED)
      .setLast(false));
    db.components().insertProjectBranch(project2);

    assertThat(underTest.selectPurgeableAnalyses(project1.uuid(), dbSession)).isEmpty();
    assertThat(underTest.selectPurgeableAnalyses(project2.uuid(), dbSession))
      .extracting(PurgeableAnalysisDto::getAnalysisUuid)
      .containsOnly(analysis2.getUuid());
  }

  @Test
  public void selectPurgeableAnalyses_does_not_return_the_baseline_of_specific_branch() {
    ComponentDto project = db.components().insertPublicProject("master");
    SnapshotDto analysisProject = db.components().insertSnapshot(newSnapshot()
      .setComponentUuid(project.uuid())
      .setStatus(STATUS_PROCESSED)
      .setLast(false));
    dbClient.newCodePeriodDao().insert(dbSession,
      new NewCodePeriodDto()
        .setProjectUuid(project.uuid())
        .setBranchUuid(project.uuid())
        .setType(NewCodePeriodType.SPECIFIC_ANALYSIS)
        .setValue(analysisProject.getUuid()));
    ComponentDto branch1 = db.components().insertProjectBranch(project);
    SnapshotDto analysisBranch1 = db.components().insertSnapshot(newSnapshot()
      .setComponentUuid(branch1.uuid())
      .setStatus(STATUS_PROCESSED)
      .setLast(false));
    ComponentDto branch2 = db.components().insertProjectBranch(project);
    SnapshotDto analysisBranch2 = db.components().insertSnapshot(newSnapshot()
      .setComponentUuid(branch2.uuid())
      .setStatus(STATUS_PROCESSED)
      .setLast(false));
    dbClient.newCodePeriodDao().insert(dbSession,
      new NewCodePeriodDto()
        .setProjectUuid(project.uuid())
        .setBranchUuid(branch2.uuid())
        .setType(NewCodePeriodType.SPECIFIC_ANALYSIS)
        .setValue(analysisBranch2.getUuid()));
    dbSession.commit();

    assertThat(underTest.selectPurgeableAnalyses(project.uuid(), dbSession))
      .isEmpty();
    assertThat(underTest.selectPurgeableAnalyses(branch1.uuid(), dbSession))
      .extracting(PurgeableAnalysisDto::getAnalysisUuid)
      .containsOnly(analysisBranch1.getUuid());
    assertThat(underTest.selectPurgeableAnalyses(branch2.uuid(), dbSession))
      .isEmpty();
  }

  @Test
  public void delete_project_and_associated_data() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto module = db.components().insertComponent(newModuleDto(project));
    ComponentDto directory = db.components().insertComponent(newDirectory(module, "a/b"));
    ComponentDto file = db.components().insertComponent(newFileDto(directory));
    SnapshotDto analysis = db.components().insertSnapshot(project);
    IssueDto issue1 = db.issues().insert(rule, project, file);
    IssueChangeDto issueChange1 = db.issues().insertChange(issue1);
    IssueDto issue2 = db.issues().insert(rule, project, file);
    FileSourceDto fileSource = db.fileSources().insertFileSource(file);
    db.issues().insertNewCodeReferenceIssue(newCodeReferenceIssue(issue1));

    ComponentDto otherProject = db.components().insertPrivateProject();
    ComponentDto otherModule = db.components().insertComponent(newModuleDto(otherProject));
    ComponentDto otherDirectory = db.components().insertComponent(newDirectory(otherModule, "a/b"));
    ComponentDto otherFile = db.components().insertComponent(newFileDto(otherDirectory));
    SnapshotDto otherAnalysis = db.components().insertSnapshot(otherProject);
    IssueDto otherIssue1 = db.issues().insert(rule, otherProject, otherFile);
    IssueChangeDto otherIssueChange1 = db.issues().insertChange(otherIssue1);
    IssueDto otherIssue2 = db.issues().insert(rule, otherProject, otherFile);
    FileSourceDto otherFileSource = db.fileSources().insertFileSource(otherFile);
    db.issues().insertNewCodeReferenceIssue(newCodeReferenceIssue(otherIssue1));

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("components")).containsOnly(otherProject.uuid(), otherModule.uuid(), otherDirectory.uuid(), otherFile.uuid());
    assertThat(uuidsIn("projects")).containsOnly(otherProject.uuid());
    assertThat(uuidsIn("snapshots")).containsOnly(otherAnalysis.getUuid());
    assertThat(uuidsIn("issues", "kee")).containsOnly(otherIssue1.getKey(), otherIssue2.getKey());
    assertThat(uuidsIn("issue_changes", "kee")).containsOnly(otherIssueChange1.getKey());
    assertThat(uuidsIn("new_code_reference_issues", "issue_key")).containsOnly(otherIssue1.getKey());
    assertThat(uuidsIn("file_sources", "file_uuid")).containsOnly(otherFileSource.getFileUuid());
  }

  @Test
  public void delete_application() {
    MetricDto metric = db.measures().insertMetric();
    ComponentDto project = db.components().insertPrivateProject();
    BranchDto projectBranch = db.getDbClient().branchDao().selectByUuid(db.getSession(), project.uuid()).get();
    RuleDefinitionDto rule = db.rules().insert();

    ComponentDto app = db.components().insertPrivateApplication();
    ComponentDto appBranch = db.components().insertProjectBranch(app);
    ComponentDto otherApp = db.components().insertPrivateApplication();
    ComponentDto otherAppBranch = db.components().insertProjectBranch(otherApp);

    SnapshotDto appAnalysis = db.components().insertSnapshot(app);
    SnapshotDto appBranchAnalysis = db.components().insertSnapshot(appBranch);
    SnapshotDto otherAppAnalysis = db.components().insertSnapshot(otherApp);
    SnapshotDto otherAppBranchAnalysis = db.components().insertSnapshot(otherAppBranch);

    MeasureDto appMeasure = db.measures().insertMeasure(app, appAnalysis, metric);
    MeasureDto appBranchMeasure = db.measures().insertMeasure(appBranch, appBranchAnalysis, metric);
    MeasureDto otherAppMeasure = db.measures().insertMeasure(otherApp, otherAppAnalysis, metric);
    MeasureDto otherAppBranchMeasure = db.measures().insertMeasure(otherAppBranch, otherAppBranchAnalysis, metric);

    db.components().addApplicationProject(app, project);
    db.components().addApplicationProject(otherApp, project);
    db.components().addProjectBranchToApplicationBranch(dbClient.branchDao().selectByUuid(dbSession, appBranch.uuid()).get(), projectBranch);
    db.components().addProjectBranchToApplicationBranch(dbClient.branchDao().selectByUuid(dbSession, otherAppBranch.uuid()).get(), projectBranch);

    underTest.deleteProject(dbSession, app.uuid(), app.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("components")).containsOnly(project.uuid(), otherApp.uuid(), otherAppBranch.uuid());
    assertThat(uuidsIn("projects")).containsOnly(project.uuid(), otherApp.uuid());
    assertThat(uuidsIn("snapshots")).containsOnly(otherAppAnalysis.getUuid(), otherAppBranchAnalysis.getUuid());
    assertThat(uuidsIn("project_branches")).containsOnly(project.uuid(), otherApp.uuid(), otherAppBranch.uuid());
    assertThat(uuidsIn("project_measures")).containsOnly(otherAppMeasure.getUuid(), otherAppBranchMeasure.getUuid());
    assertThat(uuidsIn("app_projects", "application_uuid")).containsOnly(otherApp.uuid());
    assertThat(uuidsIn("app_branch_project_branch", "application_branch_uuid")).containsOnly(otherAppBranch.uuid());
  }

  @Test
  public void delete_application_branch() {
    MetricDto metric = db.measures().insertMetric();
    ComponentDto project = db.components().insertPrivateProject();
    BranchDto projectBranch = db.getDbClient().branchDao().selectByUuid(db.getSession(), project.uuid()).get();
    RuleDefinitionDto rule = db.rules().insert();

    ComponentDto app = db.components().insertPrivateApplication();
    ComponentDto appBranch = db.components().insertProjectBranch(app);
    ComponentDto otherApp = db.components().insertPrivateApplication();
    ComponentDto otherAppBranch = db.components().insertProjectBranch(otherApp);

    SnapshotDto appAnalysis = db.components().insertSnapshot(app);
    SnapshotDto appBranchAnalysis = db.components().insertSnapshot(appBranch);
    SnapshotDto otherAppAnalysis = db.components().insertSnapshot(otherApp);
    SnapshotDto otherAppBranchAnalysis = db.components().insertSnapshot(otherAppBranch);

    MeasureDto appMeasure = db.measures().insertMeasure(app, appAnalysis, metric);
    MeasureDto appBranchMeasure = db.measures().insertMeasure(appBranch, appBranchAnalysis, metric);
    MeasureDto otherAppMeasure = db.measures().insertMeasure(otherApp, otherAppAnalysis, metric);
    MeasureDto otherAppBranchMeasure = db.measures().insertMeasure(otherAppBranch, otherAppBranchAnalysis, metric);

    db.components().addApplicationProject(app, project);
    db.components().addApplicationProject(otherApp, project);
    db.components().addProjectBranchToApplicationBranch(dbClient.branchDao().selectByUuid(dbSession, appBranch.uuid()).get(), projectBranch);
    db.components().addProjectBranchToApplicationBranch(dbClient.branchDao().selectByUuid(dbSession, otherAppBranch.uuid()).get(), projectBranch);

    // properties exist or active and for inactive branch
    insertPropertyFor(appBranch, otherAppBranch);

    underTest.deleteBranch(dbSession, appBranch.uuid());
    dbSession.commit();

    assertThat(uuidsIn("components")).containsOnly(project.uuid(), app.uuid(), otherApp.uuid(), otherAppBranch.uuid());
    assertThat(uuidsIn("projects")).containsOnly(project.uuid(), app.uuid(), otherApp.uuid());
    assertThat(uuidsIn("snapshots")).containsOnly(otherAppAnalysis.getUuid(), appAnalysis.getUuid(), otherAppBranchAnalysis.getUuid());
    assertThat(uuidsIn("project_branches")).containsOnly(project.uuid(), app.uuid(), otherApp.uuid(), otherAppBranch.uuid());
    assertThat(uuidsIn("project_measures")).containsOnly(appMeasure.getUuid(), otherAppMeasure.getUuid(), otherAppBranchMeasure.getUuid());
    assertThat(uuidsIn("app_projects", "application_uuid")).containsOnly(app.uuid(), otherApp.uuid());
    assertThat(uuidsIn("app_branch_project_branch", "application_branch_uuid")).containsOnly(otherAppBranch.uuid());
    assertThat(componentUuidsIn("properties")).containsOnly(otherAppBranch.uuid());
  }

  @Test
  public void delete_webhooks_from_project() {
    ProjectDto project1 = db.components().insertPrivateProjectDto();
    WebhookDto webhook = db.webhooks().insertWebhook(project1);
    db.webhookDelivery().insert(webhook);
    ProjectDto projectNotToBeDeleted = db.components().insertPrivateProjectDto();
    WebhookDto webhookNotDeleted = db.webhooks().insertWebhook(projectNotToBeDeleted);
    WebhookDeliveryLiteDto webhookDeliveryNotDeleted = db.webhookDelivery().insert(webhookNotDeleted);

    underTest.deleteProject(dbSession, project1.getUuid(), project1.getQualifier(), project1.getName(), project1.getKey());

    assertThat(uuidsIn("webhooks")).containsOnly(webhookNotDeleted.getUuid());
    assertThat(uuidsIn("webhook_deliveries")).containsOnly(webhookDeliveryNotDeleted.getUuid());
  }

  private Stream<String> uuidsOfTable(String tableName) {
    return db.select("select uuid as \"UUID\" from " + tableName)
      .stream()
      .map(s -> (String) s.get("UUID"));
  }

  @Test
  public void delete_row_in_ce_activity_when_deleting_project() {
    ComponentDto projectToBeDeleted = ComponentTesting.newPrivateProjectDto();
    ComponentDto anotherLivingProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, projectToBeDeleted, anotherLivingProject);

    // Insert 2 rows in CE_ACTIVITY : one for the project that will be deleted, and one on another project
    CeActivityDto toBeDeletedActivity = insertCeActivity(projectToBeDeleted);
    CeActivityDto notDeletedActivity = insertCeActivity(anotherLivingProject);
    dbSession.commit();

    underTest.deleteProject(dbSession, projectToBeDeleted.uuid(), projectToBeDeleted.qualifier(), projectToBeDeleted.name(), projectToBeDeleted.getKey());
    dbSession.commit();

    assertThat(uuidsOfTable("ce_activity"))
      .containsOnly(notDeletedActivity.getUuid())
      .hasSize(1);
  }

  @Test
  public void delete_row_in_ce_task_input_referring_to_a_row_in_ce_activity_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeActivityDto projectTask = insertCeActivity(project);
    insertCeTaskInput(projectTask.getUuid());
    CeActivityDto branchTask = insertCeActivity(branch);
    insertCeTaskInput(branchTask.getUuid());
    CeActivityDto anotherBranchTask = insertCeActivity(anotherBranch);
    insertCeTaskInput(anotherBranchTask.getUuid());
    CeActivityDto anotherProjectTask = insertCeActivity(anotherProject);
    insertCeTaskInput(anotherProjectTask.getUuid());
    insertCeTaskInput("non existing task");
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_input")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_input")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_scanner_context_referring_to_a_row_in_ce_activity_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeActivityDto projectTask = insertCeActivity(project);
    insertCeScannerContext(projectTask.getUuid());
    CeActivityDto branchTask = insertCeActivity(branch);
    insertCeScannerContext(branchTask.getUuid());
    CeActivityDto anotherBranchTask = insertCeActivity(anotherBranch);
    insertCeScannerContext(anotherBranchTask.getUuid());
    CeActivityDto anotherProjectTask = insertCeActivity(anotherProject);
    insertCeScannerContext(anotherProjectTask.getUuid());
    insertCeScannerContext("non existing task");
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_scanner_context")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_scanner_context")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_task_characteristics_referring_to_a_row_in_ce_activity_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeActivityDto projectTask = insertCeActivity(project);
    insertCeTaskCharacteristics(projectTask.getUuid(), 3);
    CeActivityDto branchTask = insertCeActivity(branch);
    insertCeTaskCharacteristics(branchTask.getUuid(), 2);
    CeActivityDto anotherBranchTask = insertCeActivity(anotherBranch);
    insertCeTaskCharacteristics(anotherBranchTask.getUuid(), 6);
    CeActivityDto anotherProjectTask = insertCeActivity(anotherProject);
    insertCeTaskCharacteristics(anotherProjectTask.getUuid(), 2);
    insertCeTaskCharacteristics("non existing task", 5);
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_characteristics")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_characteristics")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_task_message_referring_to_a_row_in_ce_activity_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeActivityDto projectTask = insertCeActivity(project);
    insertCeTaskMessages(projectTask.getUuid(), 3);
    CeActivityDto branchTask = insertCeActivity(branch);
    insertCeTaskMessages(branchTask.getUuid(), 2);
    CeActivityDto anotherBranchTask = insertCeActivity(anotherBranch);
    insertCeTaskMessages(anotherBranchTask.getUuid(), 6);
    CeActivityDto anotherProjectTask = insertCeActivity(anotherProject);
    insertCeTaskMessages(anotherProjectTask.getUuid(), 2);
    insertCeTaskMessages("non existing task", 5);
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_message")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_activity")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_message")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_rows_in_user_dismissed_messages_when_deleting_project() {
    UserDto user1 = db.users().insertUser();
    UserDto user2 = db.users().insertUser();
    ProjectDto project = db.components().insertPrivateProjectDto();
    ProjectDto anotherProject = db.components().insertPrivateProjectDto();

    UserDismissedMessageDto msg1 = db.users().insertUserDismissedMessage(user1, project, CeTaskMessageType.SUGGEST_DEVELOPER_EDITION_UPGRADE);
    UserDismissedMessageDto msg2 = db.users().insertUserDismissedMessage(user2, project, CeTaskMessageType.SUGGEST_DEVELOPER_EDITION_UPGRADE);
    UserDismissedMessageDto msg3 = db.users().insertUserDismissedMessage(user1, anotherProject, CeTaskMessageType.SUGGEST_DEVELOPER_EDITION_UPGRADE);

    assertThat(uuidsIn("user_dismissed_messages")).containsOnly(msg1.getUuid(), msg2.getUuid(), msg3.getUuid());

    underTest.deleteProject(dbSession, project.getUuid(), project.getQualifier(), project.getName(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("user_dismissed_messages")).containsOnly(msg3.getUuid());
  }

  @Test
  public void delete_tasks_in_ce_queue_when_deleting_project() {
    ComponentDto projectToBeDeleted = db.components().insertPrivateProject();
    ComponentDto anotherLivingProject = db.components().insertPrivateProject();

    // Insert 3 rows in CE_QUEUE: two for the project that will be deleted (in order to check that status
    // is not involved in deletion), and one on another project
    dbClient.ceQueueDao().insert(dbSession, createCeQueue(projectToBeDeleted, Status.PENDING));
    dbClient.ceQueueDao().insert(dbSession, createCeQueue(projectToBeDeleted, Status.IN_PROGRESS));
    dbClient.ceQueueDao().insert(dbSession, createCeQueue(anotherLivingProject, Status.PENDING));
    dbSession.commit();

    underTest.deleteProject(dbSession, projectToBeDeleted.uuid(), projectToBeDeleted.qualifier(), projectToBeDeleted.name(), projectToBeDeleted.getKey());
    dbSession.commit();

    assertThat(db.countRowsOfTable("ce_queue")).isOne();
    assertThat(db.countSql("select count(*) from ce_queue where main_component_uuid='" + projectToBeDeleted.uuid() + "'")).isZero();
  }

  @Test
  public void delete_row_in_ce_task_input_referring_to_a_row_in_ce_queue_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeQueueDto projectTask = insertCeQueue(project);
    insertCeTaskInput(projectTask.getUuid());
    CeQueueDto branchTask = insertCeQueue(branch);
    insertCeTaskInput(branchTask.getUuid());
    CeQueueDto anotherBranchTask = insertCeQueue(anotherBranch);
    insertCeTaskInput(anotherBranchTask.getUuid());
    CeQueueDto anotherProjectTask = insertCeQueue(anotherProject);
    insertCeTaskInput(anotherProjectTask.getUuid());
    insertCeTaskInput("non existing task");
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_input")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_input")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_scanner_context_referring_to_a_row_in_ce_queue_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeQueueDto projectTask = insertCeQueue(project);
    insertCeScannerContext(projectTask.getUuid());
    CeQueueDto branchTask = insertCeQueue(branch);
    insertCeScannerContext(branchTask.getUuid());
    CeQueueDto anotherBranchTask = insertCeQueue(anotherBranch);
    insertCeScannerContext(anotherBranchTask.getUuid());
    CeQueueDto anotherProjectTask = insertCeQueue(anotherProject);
    insertCeScannerContext(anotherProjectTask.getUuid());
    insertCeScannerContext("non existing task");
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_scanner_context"))
      .containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_scanner_context")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_task_characteristics_referring_to_a_row_in_ce_queue_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeQueueDto projectTask = insertCeQueue(project);
    insertCeTaskCharacteristics(projectTask.getUuid(), 3);
    CeQueueDto branchTask = insertCeQueue(branch);
    insertCeTaskCharacteristics(branchTask.getUuid(), 1);
    CeQueueDto anotherBranchTask = insertCeQueue(anotherBranch);
    insertCeTaskCharacteristics(anotherBranchTask.getUuid(), 5);
    CeQueueDto anotherProjectTask = insertCeQueue(anotherProject);
    insertCeTaskCharacteristics(anotherProjectTask.getUuid(), 2);
    insertCeTaskCharacteristics("non existing task", 5);
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_characteristics"))
      .containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_characteristics")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_ce_task_message_referring_to_a_row_in_ce_queue_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);

    CeQueueDto projectTask = insertCeQueue(project);
    insertCeTaskMessages(projectTask.getUuid(), 3);
    CeQueueDto branchTask = insertCeQueue(branch);
    insertCeTaskMessages(branchTask.getUuid(), 1);
    CeQueueDto anotherBranchTask = insertCeQueue(anotherBranch);
    insertCeTaskMessages(anotherBranchTask.getUuid(), 5);
    CeQueueDto anotherProjectTask = insertCeQueue(anotherProject);
    insertCeTaskMessages(anotherProjectTask.getUuid(), 2);
    insertCeTaskMessages("non existing task", 5);
    dbSession.commit();

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_message"))
      .containsOnly(projectTask.getUuid(), anotherBranchTask.getUuid(), anotherProjectTask.getUuid(), "non existing task");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("ce_queue")).containsOnly(anotherProjectTask.getUuid());
    assertThat(taskUuidsIn("ce_task_message")).containsOnly(anotherProjectTask.getUuid(), "non existing task");
  }

  @Test
  public void delete_row_in_events_and_event_component_changes_when_deleting_project() {
    ComponentDto project = ComponentTesting.newPrivateProjectDto();
    ComponentDto branch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherBranch = ComponentTesting.newBranchComponent(project, newBranchDto(project));
    ComponentDto anotherProject = ComponentTesting.newPrivateProjectDto();
    dbClient.componentDao().insert(dbSession, project, branch, anotherBranch, anotherProject);
    SnapshotDto projectAnalysis1 = db.components().insertSnapshot(project);
    SnapshotDto projectAnalysis2 = db.components().insertSnapshot(project);
    EventDto projectEvent1 = db.events().insertEvent(projectAnalysis1);
    EventDto projectEvent2 = db.events().insertEvent(projectAnalysis2);
    EventDto projectEvent3 = db.events().insertEvent(db.components().insertSnapshot(project));
    SnapshotDto branchAnalysis1 = db.components().insertSnapshot(branch);
    SnapshotDto branchAnalysis2 = db.components().insertSnapshot(branch);
    EventDto branchEvent1 = db.events().insertEvent(branchAnalysis1);
    EventDto branchEvent2 = db.events().insertEvent(branchAnalysis2);
    SnapshotDto anotherBranchAnalysis = db.components().insertSnapshot(anotherBranch);
    EventDto anotherBranchEvent = db.events().insertEvent(anotherBranchAnalysis);
    SnapshotDto anotherProjectAnalysis = db.components().insertSnapshot(anotherProject);
    EventDto anotherProjectEvent = db.events().insertEvent(anotherProjectAnalysis);
    ComponentDto referencedProjectA = db.components().insertPublicProject();
    ComponentDto referencedProjectB = db.components().insertPublicProject();
    db.events().insertEventComponentChanges(projectEvent1, projectAnalysis1, randomChangeCategory(), referencedProjectA, null);
    db.events().insertEventComponentChanges(projectEvent1, projectAnalysis1, randomChangeCategory(), referencedProjectB, null);
    BranchDto branchProjectA = newBranchDto(referencedProjectA);
    ComponentDto cptBranchProjectA = ComponentTesting.newBranchComponent(referencedProjectA, branchProjectA);
    db.events().insertEventComponentChanges(projectEvent2, projectAnalysis2, randomChangeCategory(), cptBranchProjectA, branchProjectA);
    // note: projectEvent3 has no component change
    db.events().insertEventComponentChanges(branchEvent1, branchAnalysis1, randomChangeCategory(), referencedProjectB, null);
    db.events().insertEventComponentChanges(branchEvent2, branchAnalysis2, randomChangeCategory(), cptBranchProjectA, branchProjectA);
    db.events().insertEventComponentChanges(anotherBranchEvent, anotherBranchAnalysis, randomChangeCategory(), referencedProjectB, null);
    db.events().insertEventComponentChanges(anotherProjectEvent, anotherProjectAnalysis, randomChangeCategory(), referencedProjectB, null);

    // deleting referenced project does not delete any data
    underTest.deleteProject(dbSession, referencedProjectA.uuid(), referencedProjectA.qualifier(), project.name(), project.getKey());

    assertThat(db.countRowsOfTable("event_component_changes"))
      .isEqualTo(7);
    assertThat(db.countRowsOfTable("events"))
      .isEqualTo(7);

    underTest.deleteProject(dbSession, branch.uuid(), branch.qualifier(), project.name(), project.getKey());
    assertThat(uuidsIn("event_component_changes", "event_component_uuid"))
      .containsOnly(project.uuid(), anotherBranch.uuid(), anotherProject.uuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isEqualTo(5);
    assertThat(uuidsIn("events"))
      .containsOnly(projectEvent1.getUuid(), projectEvent2.getUuid(), projectEvent3.getUuid(), anotherBranchEvent.getUuid(), anotherProjectEvent.getUuid());

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());
    assertThat(uuidsIn("event_component_changes", "event_component_uuid"))
      .containsOnly(anotherBranch.uuid(), anotherProject.uuid());
    assertThat(db.countRowsOfTable("event_component_changes"))
      .isEqualTo(2);
    assertThat(uuidsIn("events"))
      .containsOnly(anotherBranchEvent.getUuid(), anotherProjectEvent.getUuid());
  }

  private static EventComponentChangeDto.ChangeCategory randomChangeCategory() {
    return EventComponentChangeDto.ChangeCategory.values()[new Random().nextInt(EventComponentChangeDto.ChangeCategory.values().length)];
  }

  private ComponentDto insertProjectWithBranchAndRelatedData() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto branch = db.components().insertProjectBranch(project);
    ComponentDto module = db.components().insertComponent(newModuleDto(branch));
    ComponentDto subModule = db.components().insertComponent(newModuleDto(module));
    ComponentDto file = db.components().insertComponent(newFileDto(subModule));
    db.issues().insert(rule, branch, file);
    db.issues().insert(rule, branch, subModule);
    db.issues().insert(rule, branch, module);
    return project;
  }

  @Test
  public void delete_branch_content_when_deleting_project() {
    ComponentDto anotherLivingProject = insertProjectWithBranchAndRelatedData();
    int projectEntryCount = db.countRowsOfTable("components");
    int issueCount = db.countRowsOfTable("issues");
    int branchCount = db.countRowsOfTable("project_branches");
    Collection<BranchDto> anotherLivingProjectBranches = db.getDbClient().branchDao()
      .selectByComponent(db.getSession(), anotherLivingProject);
    insertPropertyFor(anotherLivingProjectBranches);

    assertThat(db.countRowsOfTable("properties")).isEqualTo(anotherLivingProjectBranches.size());

    ComponentDto projectToDelete = insertProjectWithBranchAndRelatedData();
    Collection<BranchDto> projectToDeleteBranches = db.getDbClient().branchDao()
      .selectByComponent(db.getSession(), projectToDelete);
    insertPropertyFor(projectToDeleteBranches);

    assertThat(db.countRowsOfTable("properties")).isEqualTo(anotherLivingProjectBranches.size() + projectToDeleteBranches.size());
    assertThat(db.countRowsOfTable("components")).isGreaterThan(projectEntryCount);
    assertThat(db.countRowsOfTable("issues")).isGreaterThan(issueCount);
    assertThat(db.countRowsOfTable("project_branches")).isGreaterThan(branchCount);

    underTest.deleteProject(dbSession, projectToDelete.uuid(), projectToDelete.qualifier(), projectToDelete.name(), projectToDelete.getKey());
    dbSession.commit();

    assertThat(db.countRowsOfTable("components")).isEqualTo(projectEntryCount);
    assertThat(db.countRowsOfTable("issues")).isEqualTo(issueCount);
    assertThat(db.countRowsOfTable("project_branches")).isEqualTo(branchCount);
    assertThat(db.countRowsOfTable("properties")).isEqualTo(anotherLivingProjectBranches.size());
  }

  @Test
  public void delete_view_and_child() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto view = db.components().insertPrivatePortfolio();
    ComponentDto subView = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto projectCopy = db.components().insertComponent(newProjectCopy(project, subView));
    ComponentDto otherView = db.components().insertPrivatePortfolio();
    ComponentDto otherSubView = db.components().insertComponent(newSubPortfolio(otherView));
    ComponentDto otherProjectCopy = db.components().insertComponent(newProjectCopy(project, otherSubView));

    underTest.deleteProject(dbSession, view.uuid(), view.qualifier(), project.name(), project.getKey());
    dbSession.commit();

    assertThat(uuidsIn("components"))
      .containsOnly(project.uuid(), otherView.uuid(), otherSubView.uuid(), otherProjectCopy.uuid());
  }

  @Test
  public void should_delete_old_closed_issues() {
    RuleDefinitionDto rule = db.rules().insert();
    ComponentDto project = db.components().insertPublicProject();

    ComponentDto module = db.components().insertComponent(newModuleDto(project));
    ComponentDto file = db.components().insertComponent(newFileDto(module));

    IssueDto oldClosed = db.issues().insert(rule, project, file, issue -> {
      issue.setStatus("CLOSED");
      issue.setIssueCloseDate(DateUtils.addDays(new Date(), -31));
    });
    db.issues().insertNewCodeReferenceIssue(newCodeReferenceIssue(oldClosed));

    IssueDto notOldEnoughClosed = db.issues().insert(rule, project, file, issue -> {
      issue.setStatus("CLOSED");
      issue.setIssueCloseDate(new Date());
    });
    db.issues().insertNewCodeReferenceIssue(newCodeReferenceIssue(notOldEnoughClosed));
    IssueDto notClosed = db.issues().insert(rule, project, file);
    db.issues().insertNewCodeReferenceIssue(newCodeReferenceIssue(notClosed));

    when(system2.now()).thenReturn(new Date().getTime());
    underTest.purge(dbSession, newConfigurationWith30Days(system2, project.uuid(), project.uuid()), PurgeListener.EMPTY, new PurgeProfiler());
    dbSession.commit();

    // old closed got deleted
    assertThat(db.getDbClient().issueDao().selectByKey(dbSession, oldClosed.getKey())).isEmpty();

    // others remain
    assertThat(db.countRowsOfTable("issues")).isEqualTo(2);

    Optional<IssueDto> notOldEnoughClosedFromQuery = db.getDbClient().issueDao().selectByKey(dbSession, notOldEnoughClosed.getKey());
    assertThat(notOldEnoughClosedFromQuery).isNotEmpty();
    assertThat(notOldEnoughClosedFromQuery.get().isNewCodeReferenceIssue()).isTrue();

    Optional<IssueDto> notClosedFromQuery = db.getDbClient().issueDao().selectByKey(dbSession, notClosed.getKey());
    assertThat(notClosedFromQuery).isNotEmpty();
    assertThat(notClosedFromQuery.get().isNewCodeReferenceIssue()).isTrue();

    Optional<IssueDto> oldClosedFromQuery = db.getDbClient().issueDao().selectByKey(dbSession, oldClosed.getKey());
    assertThat(oldClosedFromQuery).isEmpty();
  }

  @Test
  public void delete_disabled_components_without_issues() {
    ComponentDto project = db.components().insertPublicProject(p -> p.setEnabled(true));
    ComponentDto enabledFileWithIssues = db.components().insertComponent(newFileDto(project).setEnabled(true));
    ComponentDto disabledFileWithIssues = db.components().insertComponent(newFileDto(project).setEnabled(false));
    ComponentDto enabledFileWithoutIssues = db.components().insertComponent(newFileDto(project).setEnabled(true));
    ComponentDto disabledFileWithoutIssues = db.components().insertComponent(newFileDto(project).setEnabled(false));

    RuleDefinitionDto rule = db.rules().insert();
    IssueDto closed1 = db.issues().insert(rule, project, enabledFileWithIssues, issue -> {
      issue.setStatus("CLOSED");
      issue.setResolution(Issue.RESOLUTION_FIXED);
      issue.setIssueCloseDate(new Date());
    });
    IssueDto closed2 = db.issues().insert(rule, project, disabledFileWithIssues, issue -> {
      issue.setStatus("CLOSED");
      issue.setResolution(Issue.RESOLUTION_FIXED);
      issue.setIssueCloseDate(new Date());
    });
    PurgeListener purgeListener = mock(PurgeListener.class);

    Set<String> disabledComponentUuids = ImmutableSet.of(disabledFileWithIssues.uuid(), disabledFileWithoutIssues.uuid());
    underTest.purge(dbSession,
      newConfigurationWith30Days(System2.INSTANCE, project.uuid(), project.uuid(), disabledComponentUuids),
      purgeListener, new PurgeProfiler());

    assertThat(db.getDbClient().componentDao().selectByProjectUuid(project.uuid(), dbSession))
      .extracting("uuid")
      .containsOnly(project.uuid(), enabledFileWithIssues.uuid(), disabledFileWithIssues.uuid(),
        enabledFileWithoutIssues.uuid());
    verify(purgeListener).onComponentsDisabling(project.uuid(), disabledComponentUuids);
  }

  @Test
  public void delete_ce_analysis_older_than_180_and_scanner_context_older_than_40_days_of_specified_project_when_purging_project() {
    LocalDateTime now = LocalDateTime.now();
    ComponentDto project1 = db.components().insertPublicProject();
    Consumer<CeQueueDto> belongsToProject1 = t -> t.setMainComponentUuid(project1.uuid()).setComponentUuid(project1.uuid());
    ComponentDto project2 = db.components().insertPublicProject();
    Consumer<CeQueueDto> belongsToProject2 = t -> t.setMainComponentUuid(project2.uuid()).setComponentUuid(project2.uuid());

    insertCeActivityAndChildDataWithDate("VERY_OLD_1", now.minusDays(180).minusMonths(10), belongsToProject1);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_1", now.minusDays(180).minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_1", now.minusDays(180), belongsToProject1);
    insertCeActivityAndChildDataWithDate("RECENT_1", now.minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("VERY_OLD_2", now.minusDays(180).minusMonths(10), belongsToProject2);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_2", now.minusDays(180).minusDays(1), belongsToProject2);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_2", now.minusDays(180), belongsToProject2);
    insertCeActivityAndChildDataWithDate("RECENT_2", now.minusDays(1), belongsToProject2);

    when(system2.now()).thenReturn(now.toInstant(ZoneOffset.UTC).toEpochMilli());
    underTest.purge(db.getSession(), newConfigurationWith30Days(System2.INSTANCE, project1.uuid(), project1.uuid()),
      PurgeListener.EMPTY, new PurgeProfiler());

    assertThat(selectActivity("VERY_OLD_1")).isEmpty();
    assertThat(selectTaskInput("VERY_OLD_1")).isEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_1")).isEmpty();
    assertThat(scannerContextExists("VERY_OLD_1")).isFalse();
    assertThat(selectActivity("VERY_OLD_2")).isNotEmpty();
    assertThat(selectTaskInput("VERY_OLD_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_2")).hasSize(1);
    assertThat(scannerContextExists("VERY_OLD_2")).isTrue();

    assertThat(selectActivity("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_1")).isFalse();
    assertThat(selectActivity("JUST_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_2")).hasSize(1);
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_2")).isTrue();

    assertThat(selectActivity("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_1")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_1")).isFalse(); // because more than 4 weeks old
    assertThat(selectActivity("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_2")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_2")).isTrue();

    assertThat(selectActivity("RECENT_1")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_1")).hasSize(1);
    assertThat(scannerContextExists("RECENT_1")).isTrue();
    assertThat(selectActivity("RECENT_2")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_2")).hasSize(1);
    assertThat(scannerContextExists("RECENT_2")).isTrue();
  }

  @Test
  public void delete_ce_analysis_older_than_180_and_scanner_context_older_than_40_days_of_project_and_branches_when_purging_project() {
    LocalDateTime now = LocalDateTime.now();
    ComponentDto project1 = db.components().insertPublicProject();
    ComponentDto branch1 = db.components().insertProjectBranch(project1, b -> b.setExcludeFromPurge(true));
    Consumer<CeQueueDto> belongsToProject1 = t -> t.setMainComponentUuid(project1.uuid()).setComponentUuid(project1.uuid());
    Consumer<CeQueueDto> belongsToBranch1 = t -> t.setMainComponentUuid(project1.uuid()).setComponentUuid(branch1.uuid());

    insertCeActivityAndChildDataWithDate("VERY_OLD_1", now.minusDays(180).minusMonths(10), belongsToProject1);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_1", now.minusDays(180).minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_1", now.minusDays(180), belongsToProject1);
    insertCeActivityAndChildDataWithDate("RECENT_1", now.minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("VERY_OLD_2", now.minusDays(180).minusMonths(10), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_2", now.minusDays(180).minusDays(1), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_2", now.minusDays(180), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("RECENT_2", now.minusDays(1), belongsToBranch1);

    when(system2.now()).thenReturn(now.toInstant(ZoneOffset.UTC).toEpochMilli());
    underTest.purge(db.getSession(), newConfigurationWith30Days(System2.INSTANCE, project1.uuid(), project1.uuid()),
      PurgeListener.EMPTY, new PurgeProfiler());

    assertThat(selectActivity("VERY_OLD_1")).isEmpty();
    assertThat(selectTaskInput("VERY_OLD_1")).isEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_1")).isEmpty();
    assertThat(scannerContextExists("VERY_OLD_1")).isFalse();
    assertThat(selectActivity("VERY_OLD_2")).isEmpty();
    assertThat(selectTaskInput("VERY_OLD_2")).isEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_2")).isEmpty();
    assertThat(scannerContextExists("VERY_OLD_2")).isFalse();

    assertThat(selectActivity("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_1")).isEmpty();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_1")).isFalse();
    assertThat(selectActivity("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_2")).isFalse();

    assertThat(selectActivity("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_1")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_1")).isFalse(); // because more than 4 weeks old
    assertThat(selectActivity("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_2")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_2")).isFalse(); // because more than 4 weeks old

    assertThat(selectActivity("RECENT_1")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_1")).hasSize(1);
    assertThat(scannerContextExists("RECENT_1")).isTrue();
    assertThat(selectActivity("RECENT_2")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_2")).hasSize(1);
    assertThat(scannerContextExists("RECENT_2")).isTrue();
  }

  @Test
  public void delete_ce_analysis_of_branch_older_than_180_and_scanner_context_older_than_40_days_when_purging_branch() {
    LocalDateTime now = LocalDateTime.now();
    ComponentDto project1 = db.components().insertPublicProject();
    ComponentDto branch1 = db.components().insertProjectBranch(project1);
    Consumer<CeQueueDto> belongsToProject1 = t -> t.setMainComponentUuid(project1.uuid()).setComponentUuid(project1.uuid());
    Consumer<CeQueueDto> belongsToBranch1 = t -> t.setMainComponentUuid(project1.uuid()).setComponentUuid(branch1.uuid());

    insertCeActivityAndChildDataWithDate("VERY_OLD_1", now.minusDays(180).minusMonths(10), belongsToProject1);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_1", now.minusDays(180).minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_1", now.minusDays(180), belongsToProject1);
    insertCeActivityAndChildDataWithDate("RECENT_1", now.minusDays(1), belongsToProject1);
    insertCeActivityAndChildDataWithDate("VERY_OLD_2", now.minusDays(180).minusMonths(10), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH_2", now.minusDays(180).minusDays(1), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH_2", now.minusDays(180), belongsToBranch1);
    insertCeActivityAndChildDataWithDate("RECENT_2", now.minusDays(1), belongsToBranch1);

    when(system2.now()).thenReturn(now.toInstant(ZoneOffset.UTC).toEpochMilli());
    underTest.purge(db.getSession(), newConfigurationWith30Days(System2.INSTANCE, branch1.uuid(), branch1.uuid()),
      PurgeListener.EMPTY, new PurgeProfiler());

    assertThat(selectActivity("VERY_OLD_1")).isNotEmpty();
    assertThat(selectTaskInput("VERY_OLD_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_1")).hasSize(1);
    assertThat(scannerContextExists("VERY_OLD_1")).isTrue();
    assertThat(selectActivity("VERY_OLD_2")).isEmpty();
    assertThat(selectTaskInput("VERY_OLD_2")).isEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD_2")).isEmpty();
    assertThat(scannerContextExists("VERY_OLD_2")).isFalse();

    assertThat(selectActivity("JUST_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_1")).hasSize(1);
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_1")).isTrue();
    assertThat(selectActivity("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH_2")).isEmpty();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH_2")).isFalse();

    assertThat(selectActivity("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_1")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_1")).isTrue();
    assertThat(selectActivity("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH_2")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH_2")).isFalse(); // because more than 4 weeks old

    assertThat(selectActivity("RECENT_1")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_1")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_1")).hasSize(1);
    assertThat(scannerContextExists("RECENT_1")).isTrue();
    assertThat(selectActivity("RECENT_2")).isNotEmpty();
    assertThat(selectTaskInput("RECENT_2")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT_2")).hasSize(1);
    assertThat(scannerContextExists("RECENT_2")).isTrue();
  }

  @Test
  public void deleteProject_deletes_webhook_deliveries() {
    ComponentDto project = db.components().insertPublicProject();
    dbClient.webhookDeliveryDao().insert(dbSession, newDto().setComponentUuid(project.uuid()).setUuid("D1").setDurationMs(1000).setWebhookUuid("webhook-uuid"));
    dbClient.webhookDeliveryDao().insert(dbSession, newDto().setComponentUuid("P2").setUuid("D2").setDurationMs(1000).setWebhookUuid("webhook-uuid"));

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());

    assertThat(selectAllDeliveryUuids(db, dbSession)).containsOnly("D2");
  }

  @Test
  public void deleteProject_deletes_project_mappings() {
    ComponentDto project = db.components().insertPublicProject();
    dbClient.projectMappingsDao().put(dbSession, "a.key.type", "a.key", project.uuid());
    dbClient.projectMappingsDao().put(dbSession, "a.key.type", "another.key", "D2");

    underTest.deleteProject(dbSession, project.uuid(), project.qualifier(), project.name(), project.getKey());

    assertThat(dbClient.projectMappingsDao().get(dbSession, "a.key.type", "a.key")).isEmpty();
    assertThat(dbClient.projectMappingsDao().get(dbSession, "a.key.type", "another.key")).isNotEmpty();
  }

  @Test
  public void deleteProject_deletes_project_alm_settings() {
    ProjectDto project = db.components().insertPublicProjectDto();
    ProjectDto otherProject = db.components().insertPublicProjectDto();
    AlmSettingDto almSettingDto = db.almSettings().insertGitlabAlmSetting();
    db.almSettings().insertGitlabProjectAlmSetting(almSettingDto, project);
    db.almSettings().insertGitlabProjectAlmSetting(almSettingDto, otherProject);

    underTest.deleteProject(dbSession, project.getUuid(), project.getQualifier(), project.getName(), project.getKey());

    assertThat(dbClient.projectAlmSettingDao().selectByProject(dbSession, project)).isEmpty();
    assertThat(dbClient.projectAlmSettingDao().selectByProject(dbSession, otherProject)).isNotEmpty();
  }

  @Test
  public void deleteProject_deletes_project_badge_tokens() {
    ProjectDto project = db.components().insertPublicProjectDto();
    ProjectDto otherProject = db.components().insertPublicProjectDto();
    dbClient.projectBadgeTokenDao().insert(dbSession, "token_to_delete", project, "user-uuid", "user-login");
    dbClient.projectBadgeTokenDao().insert(dbSession, "token_to_not_delete", otherProject, "user-uuid", "user-login");

    underTest.deleteProject(dbSession, project.getUuid(), project.getQualifier(), project.getName(), project.getKey());

    assertThat(dbClient.projectBadgeTokenDao().selectTokenByProject(dbSession, project)).isNull();
    assertThat(dbClient.projectBadgeTokenDao().selectTokenByProject(dbSession, otherProject)).isNotNull();
  }

  @Test
  public void deleteProject_deletes_portfolio_projects() {
    ComponentDto portfolio1 = db.components().insertPrivatePortfolio();
    ComponentDto portfolio2 = db.components().insertPrivatePortfolio();

    ProjectDto project = db.components().insertPublicProjectDto();
    ProjectDto otherProject = db.components().insertPublicProjectDto();

    db.components().addPortfolioProject(portfolio1, project.getUuid(), otherProject.getUuid());
    db.components().addPortfolioProject(portfolio2, project.getUuid());

    underTest.deleteProject(dbSession, project.getUuid(), project.getQualifier(), project.getName(), project.getKey());

    assertThat(dbClient.portfolioDao().selectAllPortfolioProjects(dbSession))
      .extracting(PortfolioProjectDto::getPortfolioUuid, PortfolioProjectDto::getProjectUuid)
      .containsExactlyInAnyOrder(tuple(portfolio1.uuid(), otherProject.getUuid()));
  }

  @Test
  public void deleteProject_deletes_app_projects() {
    ProjectDto app = db.components().insertPrivateApplicationDto();
    BranchDto appBranch = db.components().insertProjectBranch(app);

    ProjectDto project = db.components().insertPublicProjectDto();
    BranchDto projectBranch = db.components().insertProjectBranch(project);

    ProjectDto otherProject = db.components().insertPublicProjectDto();

    db.components().addApplicationProject(app, project, otherProject);
    db.components().addProjectBranchToApplicationBranch(appBranch, projectBranch);

    assertThat(db.countRowsOfTable("app_branch_project_branch")).isOne();

    underTest.deleteProject(dbSession, project.getUuid(), project.getQualifier(), project.getName(), project.getKey());

    assertThat(dbClient.applicationProjectsDao().selectProjects(dbSession, app.getUuid()))
      .extracting(ProjectDto::getUuid)
      .containsExactlyInAnyOrder(otherProject.getUuid());
    assertThat(db.countRowsOfTable("app_branch_project_branch")).isZero();
  }

  @Test
  public void deleteNonRootComponents_has_no_effect_when_parameter_is_empty() {
    DbSession dbSession = mock(DbSession.class);

    underTest.deleteNonRootComponentsInView(dbSession, Collections.emptyList());

    verifyZeroInteractions(dbSession);
  }

  @Test
  public void deleteNonRootComponents_has_no_effect_when_parameter_contains_only_projects_and_or_views() {
    ComponentDbTester componentDbTester = db.components();

    verifyNoEffect(componentDbTester.insertPrivateProject());
    verifyNoEffect(componentDbTester.insertPublicProject());
    verifyNoEffect(componentDbTester.insertPrivatePortfolio());
    verifyNoEffect(componentDbTester.insertPrivatePortfolio(), componentDbTester.insertPrivateProject(), componentDbTester.insertPublicProject());
  }

  @Test
  public void delete_live_measures_when_deleting_project() {
    MetricDto metric = db.measures().insertMetric();

    ComponentDto project1 = db.components().insertPublicProject();
    ComponentDto module1 = db.components().insertComponent(ComponentTesting.newModuleDto(project1));
    db.measures().insertLiveMeasure(project1, metric);
    db.measures().insertLiveMeasure(module1, metric);

    ComponentDto project2 = db.components().insertPublicProject();
    ComponentDto module2 = db.components().insertComponent(ComponentTesting.newModuleDto(project2));
    db.measures().insertLiveMeasure(project2, metric);
    db.measures().insertLiveMeasure(module2, metric);

    underTest.deleteProject(dbSession, project1.uuid(), project1.qualifier(), project1.name(), project1.getKey());

    assertThat(dbClient.liveMeasureDao().selectByComponentUuidsAndMetricUuids(dbSession, asList(project1.uuid(), module1.uuid()), asList(metric.getUuid()))).isEmpty();
    assertThat(dbClient.liveMeasureDao().selectByComponentUuidsAndMetricUuids(dbSession, asList(project2.uuid(), module2.uuid()), asList(metric.getUuid()))).hasSize(2);
  }

  private void verifyNoEffect(ComponentDto firstRoot, ComponentDto... otherRoots) {
    DbSession dbSession = mock(DbSession.class);

    List<ComponentDto> componentDtos = Stream.concat(Stream.of(firstRoot), Arrays.stream(otherRoots)).collect(Collectors.toList());
    Collections.shuffle(componentDtos); // order of collection must not matter
    underTest.deleteNonRootComponentsInView(dbSession, componentDtos);

    verifyZeroInteractions(dbSession);
  }

  @Test
  public void deleteNonRootComponents_deletes_only_non_root_components_of_a_project_from_table_components() {
    ComponentDto project = new Random().nextBoolean() ? db.components().insertPublicProject() : db.components().insertPrivateProject();
    ComponentDto module1 = db.components().insertComponent(ComponentTesting.newModuleDto(project));
    ComponentDto module2 = db.components().insertComponent(ComponentTesting.newModuleDto(module1));
    ComponentDto dir1 = db.components().insertComponent(newDirectory(module1, "A/B"));

    List<ComponentDto> components = asList(
      project,
      module1,
      module2,
      dir1,
      db.components().insertComponent(newDirectory(module2, "A/C")),
      db.components().insertComponent(newFileDto(dir1)),
      db.components().insertComponent(newFileDto(module2)),
      db.components().insertComponent(newFileDto(project)));
    Collections.shuffle(components);

    underTest.deleteNonRootComponentsInView(dbSession, components);

    assertThat(uuidsIn(" components"))
      .containsOnly(project.uuid());
  }

  @Test
  public void deleteNonRootComponents_deletes_only_non_root_components_of_a_view_from_table_components() {
    ComponentDto[] projects = {
      db.components().insertPrivateProject(),
      db.components().insertPrivateProject(),
      db.components().insertPrivateProject()
    };

    ComponentDto view = db.components().insertPrivatePortfolio();
    ComponentDto subview1 = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto subview2 = db.components().insertComponent(newSubPortfolio(subview1));
    List<ComponentDto> components = asList(
      view,
      subview1,
      subview2,
      db.components().insertComponent(newProjectCopy("a", projects[0], view)),
      db.components().insertComponent(newProjectCopy("b", projects[1], subview1)),
      db.components().insertComponent(newProjectCopy("c", projects[2], subview2)));
    Collections.shuffle(components);

    underTest.deleteNonRootComponentsInView(dbSession, components);

    assertThat(uuidsIn(" components"))
      .containsOnly(view.uuid(), projects[0].uuid(), projects[1].uuid(), projects[2].uuid());
  }

  @Test
  public void deleteNonRootComponents_deletes_only_specified_non_root_components_of_a_project_from_table_components() {
    ComponentDto project = new Random().nextBoolean() ? db.components().insertPublicProject() : db.components().insertPrivateProject();
    ComponentDto module1 = db.components().insertComponent(ComponentTesting.newModuleDto(project));
    ComponentDto module2 = db.components().insertComponent(ComponentTesting.newModuleDto(module1));
    ComponentDto dir1 = db.components().insertComponent(newDirectory(module1, "A/B"));
    ComponentDto dir2 = db.components().insertComponent(newDirectory(module2, "A/C"));
    ComponentDto file1 = db.components().insertComponent(newFileDto(dir1));
    ComponentDto file2 = db.components().insertComponent(newFileDto(module2));
    ComponentDto file3 = db.components().insertComponent(newFileDto(project));

    underTest.deleteNonRootComponentsInView(dbSession, singletonList(file3));
    assertThat(uuidsIn("components"))
      .containsOnly(project.uuid(), module1.uuid(), module2.uuid(), dir1.uuid(), dir2.uuid(), file1.uuid(), file2.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, asList(module1, dir2, file1));
    assertThat(uuidsIn("components"))
      .containsOnly(project.uuid(), module2.uuid(), dir1.uuid(), file2.uuid());
  }

  @Test
  public void deleteNonRootComponents_deletes_only_specified_non_root_components_of_a_view_from_table_components() {
    ComponentDto[] projects = {
      db.components().insertPrivateProject(),
      db.components().insertPrivateProject(),
      db.components().insertPrivateProject()
    };

    ComponentDto view = db.components().insertPrivatePortfolio();
    ComponentDto subview1 = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto subview2 = db.components().insertComponent(newSubPortfolio(subview1));
    ComponentDto pc1 = db.components().insertComponent(newProjectCopy("a", projects[0], view));
    ComponentDto pc2 = db.components().insertComponent(newProjectCopy("b", projects[1], subview1));
    ComponentDto pc3 = db.components().insertComponent(newProjectCopy("c", projects[2], subview2));

    underTest.deleteNonRootComponentsInView(dbSession, singletonList(pc3));
    assertThat(uuidsIn("components"))
      .containsOnly(view.uuid(), projects[0].uuid(), projects[1].uuid(), projects[2].uuid(),
        subview1.uuid(), subview2.uuid(), pc1.uuid(), pc2.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, asList(subview1, pc2));
    assertThat(uuidsIn("components"))
      .containsOnly(view.uuid(), projects[0].uuid(), projects[1].uuid(), projects[2].uuid(), subview2.uuid(), pc1.uuid());
    assertThat(uuidsIn("projects")).containsOnly(projects[0].uuid(), projects[1].uuid(), projects[2].uuid());
  }

  @Test
  public void deleteNonRootComponents_deletes_measures_of_any_non_root_component_of_a_view() {
    ComponentDto view = db.components().insertPrivatePortfolio();
    ComponentDto subview = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto pc = db.components().insertComponent(newProjectCopy("a", db.components().insertPrivateProject(), view));
    insertMeasureFor(view, subview, pc);
    assertThat(getComponentUuidsOfMeasures()).containsOnly(view.uuid(), subview.uuid(), pc.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, singletonList(pc));
    assertThat(getComponentUuidsOfMeasures())
      .containsOnly(view.uuid(), subview.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, singletonList(subview));
    assertThat(getComponentUuidsOfMeasures())
      .containsOnly(view.uuid());
  }

  @Test
  public void deleteNonRootComponents_deletes_properties_of_subviews_of_a_view() {
    ComponentDto view = db.components().insertPrivatePortfolio();
    ComponentDto subview1 = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto subview2 = db.components().insertComponent(newSubPortfolio(subview1));
    ComponentDto subview3 = db.components().insertComponent(newSubPortfolio(view));
    ComponentDto pc = db.components().insertComponent(newProjectCopy("a", db.components().insertPrivateProject(), view));
    insertPropertyFor(view, subview1, subview2, subview3, pc);
    assertThat(getResourceIdOfProperties()).containsOnly(view.uuid(), subview1.uuid(), subview2.uuid(), subview3.uuid(), pc.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, singletonList(subview1));
    assertThat(getResourceIdOfProperties())
      .containsOnly(view.uuid(), subview2.uuid(), subview3.uuid(), pc.uuid());

    underTest.deleteNonRootComponentsInView(dbSession, asList(subview2, subview3, pc));
    assertThat(getResourceIdOfProperties())
      .containsOnly(view.uuid(), pc.uuid());
  }

  @Test
  public void purgeCeActivities_deletes_activity_older_than_180_days_and_their_scanner_context() {
    LocalDateTime now = LocalDateTime.now();
    insertCeActivityAndChildDataWithDate("VERY_OLD", now.minusDays(180).minusMonths(10));
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH", now.minusDays(180).minusDays(1));
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH", now.minusDays(180));
    insertCeActivityAndChildDataWithDate("RECENT", now.minusDays(1));
    when(system2.now()).thenReturn(now.toInstant(ZoneOffset.UTC).toEpochMilli());

    underTest.purgeCeActivities(db.getSession(), new PurgeProfiler());

    assertThat(selectActivity("VERY_OLD")).isEmpty();
    assertThat(selectTaskInput("VERY_OLD")).isEmpty();
    assertThat(selectTaskCharacteristic("VERY_OLD")).isEmpty();
    assertThat(scannerContextExists("VERY_OLD")).isFalse();

    assertThat(selectActivity("JUST_OLD_ENOUGH")).isEmpty();
    assertThat(selectTaskInput("JUST_OLD_ENOUGH")).isEmpty();
    assertThat(selectTaskCharacteristic("JUST_OLD_ENOUGH")).isEmpty();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH")).isFalse();

    assertThat(selectActivity("NOT_OLD_ENOUGH")).isNotEmpty();
    assertThat(selectTaskInput("NOT_OLD_ENOUGH")).isNotEmpty();
    assertThat(selectTaskCharacteristic("NOT_OLD_ENOUGH")).hasSize(1);
    assertThat(scannerContextExists("NOT_OLD_ENOUGH")).isTrue();

    assertThat(selectActivity("RECENT")).isNotEmpty();
    assertThat(selectTaskInput("RECENT")).isNotEmpty();
    assertThat(selectTaskCharacteristic("RECENT")).hasSize(1);
    assertThat(scannerContextExists("RECENT")).isTrue();
  }

  @Test
  public void purgeCeScannerContexts_deletes_ce_scanner_context_older_than_28_days() {
    LocalDateTime now = LocalDateTime.now();
    insertCeActivityAndChildDataWithDate("VERY_OLD", now.minusDays(28).minusMonths(12));
    insertCeActivityAndChildDataWithDate("JUST_OLD_ENOUGH", now.minusDays(28).minusDays(1));
    insertCeActivityAndChildDataWithDate("NOT_OLD_ENOUGH", now.minusDays(28));
    insertCeActivityAndChildDataWithDate("RECENT", now.minusDays(1));
    when(system2.now()).thenReturn(now.toInstant(ZoneOffset.UTC).toEpochMilli());

    underTest.purgeCeScannerContexts(db.getSession(), new PurgeProfiler());

    assertThat(scannerContextExists("VERY_OLD")).isFalse();
    assertThat(scannerContextExists("JUST_OLD_ENOUGH")).isFalse();
    assertThat(scannerContextExists("NOT_OLD_ENOUGH")).isTrue();
    assertThat(scannerContextExists("RECENT")).isTrue();
  }

  private Optional<CeActivityDto> selectActivity(String taskUuid) {
    return db.getDbClient().ceActivityDao().selectByUuid(db.getSession(), taskUuid);
  }

  private List<CeTaskCharacteristicDto> selectTaskCharacteristic(String taskUuid) {
    return db.getDbClient().ceTaskCharacteristicsDao().selectByTaskUuids(db.getSession(), Collections.singletonList(taskUuid));
  }

  private Optional<DbInputStream> selectTaskInput(String taskUuid) {
    return db.getDbClient().ceTaskInputDao().selectData(db.getSession(), taskUuid);
  }

  private boolean scannerContextExists(String uuid) {
    return db.countSql("select count(1) from ce_scanner_context where task_uuid = '" + uuid + "'") == 1;
  }

  @SafeVarargs
  private final void insertCeActivityAndChildDataWithDate(String ceActivityUuid, LocalDateTime dateTime,
    Consumer<CeQueueDto>... queueDtoConsumers) {
    long date = dateTime.toInstant(UTC).toEpochMilli();
    CeQueueDto queueDto = new CeQueueDto();
    queueDto.setUuid(ceActivityUuid);
    queueDto.setTaskType(CeTaskTypes.REPORT);
    Arrays.stream(queueDtoConsumers).forEach(t -> t.accept(queueDto));
    CeActivityDto dto = new CeActivityDto(queueDto);
    dto.setStatus(CeActivityDto.Status.SUCCESS);

    when(system2.now()).thenReturn(date);
    insertCeTaskInput(dto.getUuid());
    insertCeTaskCharacteristics(dto.getUuid(), 1);
    insertCeScannerContext(dto.getUuid());
    insertCeTaskMessages(dto.getUuid(), 2);
    db.getDbClient().ceActivityDao().insert(db.getSession(), dto);
    db.getSession().commit();
  }

  private Stream<String> getResourceIdOfProperties() {
    return db.select("select component_uuid as \"uuid\" from properties").stream()
      .map(row -> (String) row.get("uuid"));
  }

  private void insertPropertyFor(ComponentDto... components) {
    Stream.of(components).forEach(componentDto -> db.properties().insertProperty(new PropertyDto()
      .setKey(randomAlphabetic(3))
      .setValue(randomAlphabetic(3))
      .setComponentUuid(componentDto.uuid()),
      componentDto.getKey(), componentDto.name(), componentDto.qualifier(), null));
  }

  private void insertPropertyFor(Collection<BranchDto> branches) {
    branches.stream().forEach(branchDto -> db.properties().insertProperty(new PropertyDto()
      .setKey(randomAlphabetic(3))
      .setValue(randomAlphabetic(3))
      .setComponentUuid(branchDto.getUuid()),
      null, branchDto.getKey(), null, null));
  }

  private Stream<String> getComponentUuidsOfMeasures() {
    return db.select("select component_uuid as \"COMPONENT_UUID\" from project_measures").stream()
      .map(row -> (String) row.get("COMPONENT_UUID"));
  }

  private void insertMeasureFor(ComponentDto... components) {
    Arrays.stream(components).forEach(componentDto -> db.getDbClient().measureDao().insert(dbSession, new MeasureDto()
      .setMetricUuid(randomAlphabetic(3))
      .setComponentUuid(componentDto.uuid())
      .setAnalysisUuid(randomAlphabetic(3))));
    dbSession.commit();
  }

  private CeQueueDto createCeQueue(ComponentDto component, Status status) {
    CeQueueDto queueDto = new CeQueueDto();
    queueDto.setUuid(Uuids.create());
    queueDto.setTaskType(REPORT);
    queueDto.setComponentUuid(component.uuid());
    queueDto.setMainComponentUuid(firstNonNull(component.getMainBranchProjectUuid(), component.uuid()));
    queueDto.setSubmitterUuid("submitter uuid");
    queueDto.setCreatedAt(1_300_000_000_000L);
    queueDto.setStatus(status);
    return queueDto;
  }

  private CeActivityDto insertCeActivity(ComponentDto component) {
    Status unusedStatus = Status.values()[RandomUtils.nextInt(Status.values().length)];
    CeQueueDto queueDto = createCeQueue(component, unusedStatus);

    CeActivityDto dto = new CeActivityDto(queueDto);
    dto.setStatus(CeActivityDto.Status.SUCCESS);
    dto.setStartedAt(1_500_000_000_000L);
    dto.setExecutedAt(1_500_000_000_500L);
    dto.setExecutionTimeMs(500L);
    dbClient.ceActivityDao().insert(dbSession, dto);
    return dto;
  }

  private CeQueueDto insertCeQueue(ComponentDto component) {
    CeQueueDto res = new CeQueueDto()
      .setUuid(UuidFactoryFast.getInstance().create())
      .setTaskType("foo")
      .setComponentUuid(component.uuid())
      .setMainComponentUuid(firstNonNull(component.getMainBranchProjectUuid(), component.uuid()))
      .setStatus(Status.PENDING)
      .setCreatedAt(1_2323_222L)
      .setUpdatedAt(1_2323_222L);
    dbClient.ceQueueDao().insert(dbSession, res);
    dbSession.commit();
    return res;
  }

  private void insertCeScannerContext(String uuid) {
    dbClient.ceScannerContextDao().insert(dbSession, uuid, CloseableIterator.from(Arrays.asList("a", "b", "c").iterator()));
    dbSession.commit();
  }

  private void insertCeTaskCharacteristics(String uuid, int count) {
    List<CeTaskCharacteristicDto> dtos = IntStream.range(0, count)
      .mapToObj(i -> new CeTaskCharacteristicDto()
        .setUuid(UuidFactoryFast.getInstance().create())
        .setTaskUuid(uuid)
        .setKey("key_" + uuid.hashCode() + i)
        .setValue("value_" + uuid.hashCode() + i))
      .collect(Collectors.toList());
    dbClient.ceTaskCharacteristicsDao().insert(dbSession, dtos);
    dbSession.commit();
  }

  private void insertCeTaskInput(String uuid) {
    dbClient.ceTaskInputDao().insert(dbSession, uuid, new ByteArrayInputStream("some content man!".getBytes()));
    dbSession.commit();
  }

  private void insertCeTaskMessages(String uuid, int count) {
    IntStream.range(0, count)
      .mapToObj(i -> new CeTaskMessageDto()
        .setUuid(UuidFactoryFast.getInstance().create())
        .setTaskUuid(uuid)
        .setMessage("key_" + uuid.hashCode() + i)
        .setType(CeTaskMessageType.GENERIC)
        .setCreatedAt(2_333_444L + i))
      .forEach(dto -> dbClient.ceTaskMessageDao().insert(dbSession, dto));
    dbSession.commit();
  }

  private static PurgeableAnalysisDto getById(List<PurgeableAnalysisDto> snapshots, String uuid) {
    return snapshots.stream()
      .filter(snapshot -> uuid.equals(snapshot.getAnalysisUuid()))
      .findFirst()
      .orElse(null);
  }

  private Stream<String> uuidsIn(String tableName) {
    return uuidsIn(tableName, "uuid");
  }

  private Stream<String> componentUuidsIn(String tableName) {
    return uuidsIn(tableName, "component_uuid");
  }

  private Stream<String> taskUuidsIn(String tableName) {
    return uuidsIn(tableName, "task_uuid");
  }

  private Stream<String> uuidsIn(String tableName, String columnName) {
    return db.select("select " + columnName + " as \"UUID\" from " + tableName)
      .stream()
      .map(row -> (String) row.get("UUID"));
  }

  private static PurgeConfiguration newConfigurationWith30Days(String rootUuid) {
    return new PurgeConfiguration(rootUuid, rootUuid, 30, Optional.of(30), System2.INSTANCE, emptySet());
  }

  private static PurgeConfiguration newConfigurationWith30Days(System2 system2, String rootUuid, String projectUuid) {
    return newConfigurationWith30Days(system2, rootUuid, projectUuid, Collections.emptySet());
  }

  private static PurgeConfiguration newConfigurationWith30Days(System2 system2, String rootUuid, String projectUuid, Set<String> disabledComponentUuids) {
    return new PurgeConfiguration(rootUuid, projectUuid, 30, Optional.of(30), system2, disabledComponentUuids);
  }

  private Stream<String> uuidsOfAnalysesOfRoot(ComponentDto rootComponent) {
    return db.select("select uuid as \"UUID\" from snapshots where component_uuid='" + rootComponent.uuid() + "'")
      .stream()
      .map(t -> (String) t.get("UUID"));
  }

  private void addComponentsSnapshotsAndIssuesToBranch(ComponentDto branch, RuleDefinitionDto rule, int branchAge) {
    db.components().insertSnapshot(branch, dto -> dto.setCreatedAt(DateUtils.addDays(new Date(), -branchAge).getTime()));
    ComponentDto module = db.components().insertComponent(newModuleDto(branch));
    ComponentDto subModule = db.components().insertComponent(newModuleDto(module));
    ComponentDto file = db.components().insertComponent(newFileDto(subModule));
    db.issues().insert(rule, branch, file);
    db.issues().insert(rule, branch, subModule);
    db.issues().insert(rule, branch, module);
  }

}
