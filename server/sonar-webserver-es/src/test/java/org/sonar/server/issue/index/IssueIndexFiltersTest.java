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
package org.sonar.server.issue.index;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.Fail;
import org.elasticsearch.search.SearchHit;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.impl.utils.TestSystem2;
import org.sonar.api.issue.Issue;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.System2;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.permission.index.IndexPermissions;
import org.sonar.server.permission.index.PermissionIndexerTester;
import org.sonar.server.permission.index.WebAuthorizationTypeSupport;
import org.sonar.server.security.SecurityStandards.SQCategory;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.view.index.ViewDoc;
import org.sonar.server.view.index.ViewIndexer;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.TimeZone.getTimeZone;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sonar.api.resources.Qualifiers.APP;
import static org.sonar.api.resources.Qualifiers.PROJECT;
import static org.sonar.api.utils.DateUtils.addDays;
import static org.sonar.api.utils.DateUtils.parseDate;
import static org.sonar.api.utils.DateUtils.parseDateTime;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.component.ComponentTesting.newModuleDto;
import static org.sonar.db.component.ComponentTesting.newPrivateProjectDto;
import static org.sonar.db.rule.RuleTesting.newRule;
import static org.sonar.server.issue.IssueDocTesting.newDoc;

public class IssueIndexFiltersTest {

  @Rule
  public EsTester es = EsTester.create();
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  private final System2 system2 = new TestSystem2().setNow(1_500_000_000_000L).setDefaultTimeZone(getTimeZone("GMT-01:00"));
  @Rule
  public DbTester db = DbTester.create(system2);

  private final IssueIndexer issueIndexer = new IssueIndexer(es.client(), db.getDbClient(), new IssueIteratorFactory(db.getDbClient()), null);
  private final ViewIndexer viewIndexer = new ViewIndexer(db.getDbClient(), es.client());
  private final PermissionIndexerTester authorizationIndexer = new PermissionIndexerTester(es, issueIndexer);
  private final IssueIndex underTest = new IssueIndex(es.client(), system2, userSessionRule, new WebAuthorizationTypeSupport(userSessionRule));

  @Test
  public void filter_by_keys() {
    ComponentDto project = newPrivateProjectDto();

    indexIssues(
      newDoc("I1", newFileDto(project, null)),
      newDoc("I2", newFileDto(project, null)));

    assertThatSearchReturnsOnly(IssueQuery.builder().issueKeys(asList("I1", "I2")), "I1", "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().issueKeys(singletonList("I1")), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().issueKeys(asList("I3", "I4")));
  }

  @Test
  public void filter_by_projects() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto module = newModuleDto(project);
    ComponentDto subModule = newModuleDto(module);

    indexIssues(
      newDoc("I1", project),
      newDoc("I2", newFileDto(project, null)),
      newDoc("I3", module),
      newDoc("I4", newFileDto(module, null)),
      newDoc("I5", subModule),
      newDoc("I6", newFileDto(subModule, null)));

    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())), "I1", "I2", "I3", "I4", "I5", "I6");
    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_modules() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto module = newModuleDto(project);
    ComponentDto subModule = newModuleDto(module);
    ComponentDto file = newFileDto(subModule, null);

    indexIssues(
      newDoc("I3", module),
      newDoc("I5", subModule),
      newDoc("I2", file));

    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList(project.uuid())).moduleUuids(singletonList(file.uuid())));
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())).moduleUuids(singletonList(module.uuid())), "I3");
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())).moduleUuids(singletonList(subModule.uuid())), "I2", "I5");
    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList(project.uuid())).moduleUuids(singletonList(project.uuid())));
    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList(project.uuid())).moduleUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_components_on_contextualized_search() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto module = newModuleDto(project);
    ComponentDto subModule = newModuleDto(module);
    ComponentDto file1 = newFileDto(project, null);
    ComponentDto file2 = newFileDto(module, null);
    ComponentDto file3 = newFileDto(subModule, null);
    String view = "ABCD";
    indexView(view, singletonList(project.uuid()));

    indexIssues(
      newDoc("I1", project),
      newDoc("I2", file1),
      newDoc("I3", module),
      newDoc("I4", file2),
      newDoc("I5", subModule),
      newDoc("I6", file3));

    assertThatSearchReturnsOnly(IssueQuery.builder().files(asList(file1.path(), file2.path(), file3.path())), "I2", "I4", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().files(singletonList(file1.path())), "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().moduleRootUuids(singletonList(subModule.uuid())), "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().moduleRootUuids(singletonList(module.uuid())), "I3", "I4", "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())), "I1", "I2", "I3", "I4", "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(view)), "I1", "I2", "I3", "I4", "I5", "I6");
    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_components_on_non_contextualized_search() {
    ComponentDto project = newPrivateProjectDto("project");
    ComponentDto file1 = newFileDto(project, null, "file1");
    ComponentDto module = newModuleDto(project).setUuid("module");
    ComponentDto file2 = newFileDto(module, null, "file2");
    ComponentDto subModule = newModuleDto(module).setUuid("subModule");
    ComponentDto file3 = newFileDto(subModule, null, "file3");
    String view = "ABCD";
    indexView(view, singletonList(project.uuid()));

    indexIssues(
      newDoc("I1", project),
      newDoc("I2", file1),
      newDoc("I3", module),
      newDoc("I4", file2),
      newDoc("I5", subModule),
      newDoc("I6", file3));

    assertThatSearchReturnsEmpty(IssueQuery.builder().projectUuids(singletonList("unknown")));
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())), "I1", "I2", "I3", "I4", "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(view)), "I1", "I2", "I3", "I4", "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().moduleUuids(singletonList(module.uuid())), "I3", "I4");
    assertThatSearchReturnsOnly(IssueQuery.builder().moduleUuids(singletonList(subModule.uuid())), "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().files(singletonList(file1.path())), "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().files(asList(file1.path(), file2.path(), file3.path())), "I2", "I4", "I6");
  }

  @Test
  public void filter_by_directories() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file1 = newFileDto(project, null).setPath("src/main/xoo/F1.xoo");
    ComponentDto file2 = newFileDto(project, null).setPath("F2.xoo");

    indexIssues(
      newDoc("I1", file1).setDirectoryPath("/src/main/xoo"),
      newDoc("I2", file2).setDirectoryPath("/"));

    assertThatSearchReturnsOnly(IssueQuery.builder().directories(singletonList("/src/main/xoo")), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().directories(singletonList("/")), "I2");
    assertThatSearchReturnsEmpty(IssueQuery.builder().directories(singletonList("unknown")));
  }

  @Test
  public void filter_by_portfolios() {
    ComponentDto portfolio1 = db.components().insertPrivateApplication();
    ComponentDto portfolio2 = db.components().insertPrivateApplication();
    ComponentDto project1 = db.components().insertPrivateProject();
    ComponentDto file = db.components().insertComponent(newFileDto(project1));
    ComponentDto project2 = db.components().insertPrivateProject();

    IssueDoc issueOnProject1 = newDoc(project1);
    IssueDoc issueOnFile = newDoc(file);
    IssueDoc issueOnProject2 = newDoc(project2);

    indexIssues(issueOnProject1, issueOnFile, issueOnProject2);
    indexView(portfolio1.uuid(), singletonList(project1.uuid()));
    indexView(portfolio2.uuid(), singletonList(project2.uuid()));

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio1.uuid())), issueOnProject1.key(), issueOnFile.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio2.uuid())), issueOnProject2.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(asList(portfolio1.uuid(), portfolio2.uuid())), issueOnProject1.key(), issueOnFile.key(), issueOnProject2.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio1.uuid())).projectUuids(singletonList(project1.uuid())), issueOnProject1.key(),
      issueOnFile.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio1.uuid())).files(singletonList(file.path())), issueOnFile.key());
    assertThatSearchReturnsEmpty(IssueQuery.builder().viewUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_portfolios_not_having_projects() {
    ComponentDto project1 = newPrivateProjectDto();
    ComponentDto file1 = newFileDto(project1, null);
    indexIssues(newDoc("I2", file1));
    String view1 = "ABCD";
    indexView(view1, emptyList());

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(view1)));
  }

  @Test
  public void do_not_return_issues_from_project_branch_when_filtering_by_portfolios() {
    ComponentDto portfolio = db.components().insertPrivateApplication();
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto projectBranch = db.components().insertProjectBranch(project);
    ComponentDto fileOnProjectBranch = db.components().insertComponent(newFileDto(projectBranch));
    indexView(portfolio.uuid(), singletonList(project.uuid()));

    IssueDoc issueOnProject = newDoc(project);
    IssueDoc issueOnProjectBranch = newDoc(projectBranch);
    IssueDoc issueOnFileOnProjectBranch = newDoc(fileOnProjectBranch);
    indexIssues(issueOnProject, issueOnFileOnProjectBranch, issueOnProjectBranch);

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio.uuid())), issueOnProject.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(portfolio.uuid())).projectUuids(singletonList(project.uuid())),
      issueOnProject.key());
    assertThatSearchReturnsEmpty(IssueQuery.builder().viewUuids(singletonList(portfolio.uuid())).projectUuids(singletonList(projectBranch.uuid())));
  }

  @Test
  public void filter_one_issue_by_project_and_branch() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto branch = db.components().insertProjectBranch(project);
    ComponentDto anotherbBranch = db.components().insertProjectBranch(project);

    IssueDoc issueOnProject = newDoc(project);
    IssueDoc issueOnBranch = newDoc(branch);
    IssueDoc issueOnAnotherBranch = newDoc(anotherbBranch);
    indexIssues(issueOnProject, issueOnBranch, issueOnAnotherBranch);

    assertThatSearchReturnsOnly(IssueQuery.builder().branchUuid(branch.uuid()).mainBranch(false), issueOnBranch.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().componentUuids(singletonList(branch.uuid())).branchUuid(branch.uuid()).mainBranch(false), issueOnBranch.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())).branchUuid(branch.uuid()).mainBranch(false), issueOnBranch.key());
    assertThatSearchReturnsOnly(
      IssueQuery.builder().componentUuids(singletonList(branch.uuid())).projectUuids(singletonList(project.uuid())).branchUuid(branch.uuid()).mainBranch(false),
      issueOnBranch.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())).mainBranch(null), issueOnProject.key(), issueOnBranch.key(), issueOnAnotherBranch.key());
    assertThatSearchReturnsEmpty(IssueQuery.builder().branchUuid("unknown"));
  }

  @Test
  public void issues_from_branch_component_children() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto projectModule = db.components().insertComponent(newModuleDto(project));
    ComponentDto projectFile = db.components().insertComponent(newFileDto(projectModule));
    ComponentDto branch = db.components().insertProjectBranch(project, b -> b.setKey("my_branch"));
    ComponentDto branchModule = db.components().insertComponent(newModuleDto(branch));
    ComponentDto branchFile = db.components().insertComponent(newFileDto(branchModule));

    indexIssues(
      newDoc("I1", project),
      newDoc("I2", projectFile),
      newDoc("I3", projectModule),
      newDoc("I4", branch),
      newDoc("I5", branchModule),
      newDoc("I6", branchFile));

    assertThatSearchReturnsOnly(IssueQuery.builder().branchUuid(branch.uuid()).mainBranch(false), "I4", "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().moduleUuids(singletonList(branchModule.uuid())).branchUuid(branch.uuid()).mainBranch(false), "I5", "I6");
    assertThatSearchReturnsOnly(IssueQuery.builder().files(singletonList(branchFile.path())).branchUuid(branch.uuid()).mainBranch(false), "I6");
    assertThatSearchReturnsEmpty(IssueQuery.builder().files(singletonList(branchFile.uuid())).mainBranch(false).branchUuid("unknown"));
  }

  @Test
  public void issues_from_main_branch() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto branch = db.components().insertProjectBranch(project);

    IssueDoc issueOnProject = newDoc(project);
    IssueDoc issueOnBranch = newDoc(branch);
    indexIssues(issueOnProject, issueOnBranch);

    assertThatSearchReturnsOnly(IssueQuery.builder().branchUuid(project.uuid()).mainBranch(true), issueOnProject.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().componentUuids(singletonList(project.uuid())).branchUuid(project.uuid()).mainBranch(true), issueOnProject.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().projectUuids(singletonList(project.uuid())).branchUuid(project.uuid()).mainBranch(true), issueOnProject.key());
    assertThatSearchReturnsOnly(
      IssueQuery.builder().componentUuids(singletonList(project.uuid())).projectUuids(singletonList(project.uuid())).branchUuid(project.uuid()).mainBranch(true),
      issueOnProject.key());
  }

  @Test
  public void branch_issues_are_ignored_when_no_branch_param() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto branch = db.components().insertProjectBranch(project, b -> b.setKey("my_branch"));

    IssueDoc projectIssue = newDoc(project);
    IssueDoc branchIssue = newDoc(branch);
    indexIssues(projectIssue, branchIssue);

    assertThatSearchReturnsOnly(IssueQuery.builder(), projectIssue.key());
  }

  @Test
  public void filter_by_main_application() {
    ComponentDto application1 = db.components().insertPrivateApplication();
    ComponentDto application2 = db.components().insertPrivateApplication();
    ComponentDto project1 = db.components().insertPrivateProject();
    ComponentDto file = db.components().insertComponent(newFileDto(project1));
    ComponentDto project2 = db.components().insertPrivateProject();
    indexView(application1.uuid(), singletonList(project1.uuid()));
    indexView(application2.uuid(), singletonList(project2.uuid()));

    IssueDoc issueOnProject1 = newDoc(project1);
    IssueDoc issueOnFile = newDoc(file);
    IssueDoc issueOnProject2 = newDoc(project2);
    indexIssues(issueOnProject1, issueOnFile, issueOnProject2);

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(application1.uuid())), issueOnProject1.key(), issueOnFile.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(application2.uuid())), issueOnProject2.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(asList(application1.uuid(), application2.uuid())), issueOnProject1.key(), issueOnFile.key(), issueOnProject2.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(application1.uuid())).projectUuids(singletonList(project1.uuid())), issueOnProject1.key(),
      issueOnFile.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(application1.uuid())).files(singletonList(file.path())), issueOnFile.key());
    assertThatSearchReturnsEmpty(IssueQuery.builder().viewUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_application_branch() {
    ComponentDto application = db.components().insertPublicProject(c -> c.setQualifier(APP));
    ComponentDto branch1 = db.components().insertProjectBranch(application);
    ComponentDto branch2 = db.components().insertProjectBranch(application);
    ComponentDto project1 = db.components().insertPrivateProject();
    ComponentDto file = db.components().insertComponent(newFileDto(project1));
    ComponentDto project2 = db.components().insertPrivateProject();
    indexView(branch1.uuid(), singletonList(project1.uuid()));
    indexView(branch2.uuid(), singletonList(project2.uuid()));

    IssueDoc issueOnProject1 = newDoc(project1);
    IssueDoc issueOnFile = newDoc(file);
    IssueDoc issueOnProject2 = newDoc(project2);
    indexIssues(issueOnProject1, issueOnFile, issueOnProject2);

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(branch1.uuid())).branchUuid(branch1.uuid()).mainBranch(false),
      issueOnProject1.key(), issueOnFile.key());
    assertThatSearchReturnsOnly(
      IssueQuery.builder().viewUuids(singletonList(branch1.uuid())).projectUuids(singletonList(project1.uuid())).branchUuid(branch1.uuid()).mainBranch(false),
      issueOnProject1.key(), issueOnFile.key());
    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(branch1.uuid())).files(singletonList(file.path())).branchUuid(branch1.uuid()).mainBranch(false),
      issueOnFile.key());
    assertThatSearchReturnsEmpty(IssueQuery.builder().branchUuid("unknown"));
  }

  @Test
  public void filter_by_application_branch_having_project_branches() {
    ComponentDto application = db.components().insertPublicProject(c -> c.setQualifier(APP).setDbKey("app"));
    ComponentDto applicationBranch1 = db.components().insertProjectBranch(application, a -> a.setKey("app-branch1"));
    ComponentDto applicationBranch2 = db.components().insertProjectBranch(application, a -> a.setKey("app-branch2"));
    ComponentDto project1 = db.components().insertPrivateProject(p -> p.setDbKey("prj1"));
    ComponentDto project1Branch1 = db.components().insertProjectBranch(project1);
    ComponentDto fileOnProject1Branch1 = db.components().insertComponent(newFileDto(project1Branch1));
    ComponentDto project1Branch2 = db.components().insertProjectBranch(project1);
    ComponentDto project2 = db.components().insertPrivateProject(p -> p.setDbKey("prj2"));
    indexView(applicationBranch1.uuid(), asList(project1Branch1.uuid(), project2.uuid()));
    indexView(applicationBranch2.uuid(), singletonList(project1Branch2.uuid()));

    IssueDoc issueOnProject1 = newDoc(project1);
    IssueDoc issueOnProject1Branch1 = newDoc(project1Branch1);
    IssueDoc issueOnFileOnProject1Branch1 = newDoc(fileOnProject1Branch1);
    IssueDoc issueOnProject1Branch2 = newDoc(project1Branch2);
    IssueDoc issueOnProject2 = newDoc(project2);
    indexIssues(issueOnProject1, issueOnProject1Branch1, issueOnFileOnProject1Branch1, issueOnProject1Branch2, issueOnProject2);

    assertThatSearchReturnsOnly(IssueQuery.builder().viewUuids(singletonList(applicationBranch1.uuid())).branchUuid(applicationBranch1.uuid()).mainBranch(false),
      issueOnProject1Branch1.key(), issueOnFileOnProject1Branch1.key(), issueOnProject2.key());
    assertThatSearchReturnsOnly(
      IssueQuery.builder().viewUuids(singletonList(applicationBranch1.uuid())).projectUuids(singletonList(project1.uuid())).branchUuid(applicationBranch1.uuid()).mainBranch(false),
      issueOnProject1Branch1.key(), issueOnFileOnProject1Branch1.key());
    assertThatSearchReturnsOnly(
      IssueQuery.builder().viewUuids(singletonList(applicationBranch1.uuid())).files(singletonList(fileOnProject1Branch1.path())).branchUuid(applicationBranch1.uuid())
        .mainBranch(false),
      issueOnFileOnProject1Branch1.key());
    assertThatSearchReturnsEmpty(
      IssueQuery.builder().viewUuids(singletonList(applicationBranch1.uuid())).projectUuids(singletonList("unknown")).branchUuid(applicationBranch1.uuid()).mainBranch(false));
  }

  @Test
  public void filter_by_created_after_by_projects() {
    Date now = new Date();
    ComponentDto project1 = newPrivateProjectDto();
    IssueDoc project1Issue1 = newDoc(project1).setFuncCreationDate(addDays(now, -10));
    IssueDoc project1Issue2 = newDoc(project1).setFuncCreationDate(addDays(now, -20));
    ComponentDto project2 = newPrivateProjectDto();
    IssueDoc project2Issue1 = newDoc(project2).setFuncCreationDate(addDays(now, -15));
    IssueDoc project2Issue2 = newDoc(project2).setFuncCreationDate(addDays(now, -30));
    indexIssues(project1Issue1, project1Issue2, project2Issue1, project2Issue2);

    // Search for issues of project 1 having less than 15 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .createdAfterByProjectUuids(ImmutableMap.of(project1.uuid(), new IssueQuery.PeriodStart(addDays(now, -15), true))),
      project1Issue1.key());

    // Search for issues of project 1 having less than 14 days and project 2 having less then 25 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .createdAfterByProjectUuids(ImmutableMap.of(
          project1.uuid(), new IssueQuery.PeriodStart(addDays(now, -14), true),
          project2.uuid(), new IssueQuery.PeriodStart(addDays(now, -25), true))),
      project1Issue1.key(), project2Issue1.key());

    // Search for issues of project 1 having less than 30 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .createdAfterByProjectUuids(ImmutableMap.of(
          project1.uuid(), new IssueQuery.PeriodStart(addDays(now, -30), true))),
      project1Issue1.key(), project1Issue2.key());

    // Search for issues of project 1 and project 2 having less than 5 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .createdAfterByProjectUuids(ImmutableMap.of(
        project1.uuid(), new IssueQuery.PeriodStart(addDays(now, -5), true),
        project2.uuid(), new IssueQuery.PeriodStart(addDays(now, -5), true))));
  }

  @Test
  public void filter_by_created_after_by_project_branches() {
    Date now = new Date();

    ComponentDto project1 = newPrivateProjectDto();
    IssueDoc project1Issue1 = newDoc(project1).setFuncCreationDate(addDays(now, -10));
    IssueDoc project1Issue2 = newDoc(project1).setFuncCreationDate(addDays(now, -20));

    ComponentDto project1Branch1 = db.components().insertProjectBranch(project1);
    IssueDoc project1Branch1Issue1 = newDoc(project1Branch1).setFuncCreationDate(addDays(now, -10));
    IssueDoc project1Branch1Issue2 = newDoc(project1Branch1).setFuncCreationDate(addDays(now, -20));

    ComponentDto project2 = newPrivateProjectDto();

    IssueDoc project2Issue1 = newDoc(project2).setFuncCreationDate(addDays(now, -15));
    IssueDoc project2Issue2 = newDoc(project2).setFuncCreationDate(addDays(now, -30));

    ComponentDto project2Branch1 = db.components().insertProjectBranch(project2);
    IssueDoc project2Branch1Issue1 = newDoc(project2Branch1).setFuncCreationDate(addDays(now, -15));
    IssueDoc project2Branch1Issue2 = newDoc(project2Branch1).setFuncCreationDate(addDays(now, -30));

    indexIssues(project1Issue1, project1Issue2, project2Issue1, project2Issue2,
      project1Branch1Issue1, project1Branch1Issue2, project2Branch1Issue1, project2Branch1Issue2);

    // Search for issues of project 1 branch 1 having less than 15 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .mainBranch(false)
        .createdAfterByProjectUuids(ImmutableMap.of(project1Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -15), true))),
      project1Branch1Issue1.key());

    // Search for issues of project 1 branch 1 having less than 14 days and project 2 branch 1 having less then 25 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .mainBranch(false)
        .createdAfterByProjectUuids(ImmutableMap.of(
          project1Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -14), true),
          project2Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -25), true))),
      project1Branch1Issue1.key(), project2Branch1Issue1.key());

    // Search for issues of project 1 branch 1 having less than 30 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .mainBranch(false)
        .createdAfterByProjectUuids(ImmutableMap.of(
          project1Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -30), true))),
      project1Branch1Issue1.key(), project1Branch1Issue2.key());

    // Search for issues of project 1 branch 1 and project 2 branch 2 having less than 5 days
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .mainBranch(false)
      .createdAfterByProjectUuids(ImmutableMap.of(
        project1Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -5), true),
        project2Branch1.uuid(), new IssueQuery.PeriodStart(addDays(now, -5), true))));
  }

  @Test
  public void filter_by_new_code_reference_by_projects() {
    ComponentDto project1 = newPrivateProjectDto();
    IssueDoc project1Issue1 = newDoc(project1).setIsNewCodeReference(true);
    IssueDoc project1Issue2 = newDoc(project1).setIsNewCodeReference(false);
    ComponentDto project2 = newPrivateProjectDto();
    IssueDoc project2Issue1 = newDoc(project2).setIsNewCodeReference(false);
    IssueDoc project2Issue2 = newDoc(project2).setIsNewCodeReference(true);
    indexIssues(project1Issue1, project1Issue2, project2Issue1, project2Issue2);

    // Search for issues of project 1 and project 2 that are new code on a branch using reference for new code
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .newCodeOnReferenceByProjectUuids(Set.of(project1.uuid(), project2.uuid())),
      project1Issue1.key(), project2Issue2.key());
  }

  @Test
  public void filter_by_new_code_reference_branches() {
    ComponentDto project1 = newPrivateProjectDto();
    IssueDoc project1Issue1 = newDoc(project1).setIsNewCodeReference(true);
    IssueDoc project1Issue2 = newDoc(project1).setIsNewCodeReference(false);

    ComponentDto project1Branch1 = db.components().insertProjectBranch(project1);
    IssueDoc project1Branch1Issue1 = newDoc(project1Branch1).setIsNewCodeReference(false);
    IssueDoc project1Branch1Issue2 = newDoc(project1Branch1).setIsNewCodeReference(true);

    ComponentDto project2 = newPrivateProjectDto();

    IssueDoc project2Issue1 = newDoc(project2).setIsNewCodeReference(true);
    IssueDoc project2Issue2 = newDoc(project2).setIsNewCodeReference(false);

    ComponentDto project2Branch1 = db.components().insertProjectBranch(project2);
    IssueDoc project2Branch1Issue1 = newDoc(project2Branch1).setIsNewCodeReference(false);
    IssueDoc project2Branch1Issue2 = newDoc(project2Branch1).setIsNewCodeReference(true);

    indexIssues(project1Issue1, project1Issue2, project2Issue1, project2Issue2,
      project1Branch1Issue1, project1Branch1Issue2, project2Branch1Issue1, project2Branch1Issue2);

    // Search for issues of project 1 branch 1 and project 2 branch 1 that are new code on a branch using reference for new code
    assertThatSearchReturnsOnly(IssueQuery.builder()
        .mainBranch(false)
        .newCodeOnReferenceByProjectUuids(Set.of(project1Branch1.uuid(), project2Branch1.uuid())),
      project1Branch1Issue2.key(), project2Branch1Issue2.key());
  }

  @Test
  public void filter_by_severities() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setSeverity(Severity.INFO),
      newDoc("I2", file).setSeverity(Severity.MAJOR));

    assertThatSearchReturnsOnly(IssueQuery.builder().severities(asList(Severity.INFO, Severity.MAJOR)), "I1", "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().severities(singletonList(Severity.INFO)), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().severities(singletonList(Severity.BLOCKER)));
  }

  @Test
  public void filter_by_statuses() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setStatus(Issue.STATUS_CLOSED),
      newDoc("I2", file).setStatus(Issue.STATUS_OPEN));

    assertThatSearchReturnsOnly(IssueQuery.builder().statuses(asList(Issue.STATUS_CLOSED, Issue.STATUS_OPEN)), "I1", "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().statuses(singletonList(Issue.STATUS_CLOSED)), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().statuses(singletonList(Issue.STATUS_CONFIRMED)));
  }

  @Test
  public void filter_by_resolutions() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setResolution(Issue.RESOLUTION_FALSE_POSITIVE),
      newDoc("I2", file).setResolution(Issue.RESOLUTION_FIXED));

    assertThatSearchReturnsOnly(IssueQuery.builder().resolutions(asList(Issue.RESOLUTION_FALSE_POSITIVE, Issue.RESOLUTION_FIXED)), "I1", "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().resolutions(singletonList(Issue.RESOLUTION_FALSE_POSITIVE)), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().resolutions(singletonList(Issue.RESOLUTION_REMOVED)));
  }

  @Test
  public void filter_by_resolved() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setStatus(Issue.STATUS_CLOSED).setResolution(Issue.RESOLUTION_FIXED),
      newDoc("I2", file).setStatus(Issue.STATUS_OPEN).setResolution(null),
      newDoc("I3", file).setStatus(Issue.STATUS_OPEN).setResolution(null));

    assertThatSearchReturnsOnly(IssueQuery.builder().resolved(true), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().resolved(false), "I2", "I3");
    assertThatSearchReturnsOnly(IssueQuery.builder().resolved(null), "I1", "I2", "I3");
  }

  @Test
  public void filter_by_rules() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);
    RuleDefinitionDto ruleDefinitionDto = newRule();
    db.rules().insert(ruleDefinitionDto);

    indexIssues(newDoc("I1", file).setRuleUuid(ruleDefinitionDto.getUuid()));

    assertThatSearchReturnsOnly(IssueQuery.builder().ruleUuids(singletonList(ruleDefinitionDto.getUuid())), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().ruleUuids(singletonList("uuid-abc")));
  }

  @Test
  public void filter_by_languages() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);
    RuleDefinitionDto ruleDefinitionDto = newRule();
    db.rules().insert(ruleDefinitionDto);

    indexIssues(newDoc("I1", file).setRuleUuid(ruleDefinitionDto.getUuid()).setLanguage("xoo"));

    assertThatSearchReturnsOnly(IssueQuery.builder().languages(singletonList("xoo")), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().languages(singletonList("unknown")));
  }

  @Test
  public void filter_by_assignees() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setAssigneeUuid("steph-uuid"),
      newDoc("I2", file).setAssigneeUuid("marcel-uuid"),
      newDoc("I3", file).setAssigneeUuid(null));

    assertThatSearchReturnsOnly(IssueQuery.builder().assigneeUuids(singletonList("steph-uuid")), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().assigneeUuids(asList("steph-uuid", "marcel-uuid")), "I1", "I2");
    assertThatSearchReturnsEmpty(IssueQuery.builder().assigneeUuids(singletonList("unknown")));
  }

  @Test
  public void filter_by_assigned() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setAssigneeUuid("steph-uuid"),
      newDoc("I2", file).setAssigneeUuid(null),
      newDoc("I3", file).setAssigneeUuid(null));

    assertThatSearchReturnsOnly(IssueQuery.builder().assigned(true), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().assigned(false), "I2", "I3");
    assertThatSearchReturnsOnly(IssueQuery.builder().assigned(null), "I1", "I2", "I3");
  }

  @Test
  public void filter_by_authors() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setAuthorLogin("steph"),
      newDoc("I2", file).setAuthorLogin("marcel"),
      newDoc("I3", file).setAssigneeUuid(null));

    assertThatSearchReturnsOnly(IssueQuery.builder().authors(singletonList("steph")), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().authors(asList("steph", "marcel")), "I1", "I2");
    assertThatSearchReturnsEmpty(IssueQuery.builder().authors(singletonList("unknown")));
  }

  @Test
  public void filter_by_created_after() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setFuncCreationDate(parseDate("2014-09-20")),
      newDoc("I2", file).setFuncCreationDate(parseDate("2014-09-23")));

    assertThatSearchReturnsOnly(IssueQuery.builder().createdAfter(parseDate("2014-09-19")), "I1", "I2");
    // Lower bound is included
    assertThatSearchReturnsOnly(IssueQuery.builder().createdAfter(parseDate("2014-09-20")), "I1", "I2");
    assertThatSearchReturnsOnly(IssueQuery.builder().createdAfter(parseDate("2014-09-21")), "I2");
    assertThatSearchReturnsEmpty(IssueQuery.builder().createdAfter(parseDate("2014-09-25")));
  }

  @Test
  public void filter_by_created_before() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setFuncCreationDate(parseDate("2014-09-20")),
      newDoc("I2", file).setFuncCreationDate(parseDate("2014-09-23")));

    assertThatSearchReturnsEmpty(IssueQuery.builder().createdBefore(parseDate("2014-09-19")));
    // Upper bound is excluded
    assertThatSearchReturnsEmpty(IssueQuery.builder().createdBefore(parseDate("2014-09-20")));
    assertThatSearchReturnsOnly(IssueQuery.builder().createdBefore(parseDate("2014-09-21")), "I1");
    assertThatSearchReturnsOnly(IssueQuery.builder().createdBefore(parseDate("2014-09-25")), "I1", "I2");
  }

  @Test
  public void filter_by_created_after_and_before() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setFuncCreationDate(parseDate("2014-09-20")),
      newDoc("I2", file).setFuncCreationDate(parseDate("2014-09-23")));

    // 19 < createdAt < 25
    assertThatSearchReturnsOnly(IssueQuery.builder().createdAfter(parseDate("2014-09-19")).createdBefore(parseDate("2014-09-25")),
      "I1", "I2");

    // 20 < createdAt < 25: excludes first issue
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-20")).createdBefore(parseDate("2014-09-25")), "I1", "I2");

    // 21 < createdAt < 25
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-21")).createdBefore(parseDate("2014-09-25")), "I2");

    // 21 < createdAt < 24
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-21")).createdBefore(parseDate("2014-09-24")), "I2");

    // 21 < createdAt < 23: excludes second issue
    assertThatSearchReturnsEmpty(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-21")).createdBefore(parseDate("2014-09-23")));

    // 19 < createdAt < 21: only first issue
    assertThatSearchReturnsOnly(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-19")).createdBefore(parseDate("2014-09-21")), "I1");

    // 20 < createdAt < 20: exception
    assertThatThrownBy(() -> underTest.search(IssueQuery.builder()
      .createdAfter(parseDate("2014-09-20")).createdBefore(parseDate("2014-09-20"))
      .build(), new SearchOptions()))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void filter_by_created_after_and_before_take_into_account_timezone() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setFuncCreationDate(parseDateTime("2014-09-20T00:00:00+0100")),
      newDoc("I2", file).setFuncCreationDate(parseDateTime("2014-09-23T00:00:00+0100")));

    assertThatSearchReturnsOnly(IssueQuery.builder().createdAfter(parseDateTime("2014-09-19T23:00:00+0000")).createdBefore(parseDateTime("2014-09-22T23:00:01+0000")),
      "I1", "I2");

    assertThatSearchReturnsEmpty(IssueQuery.builder().createdAfter(parseDateTime("2014-09-19T23:00:01+0000")).createdBefore(parseDateTime("2014-09-22T23:00:00+0000")));
  }

  @Test
  public void filter_by_created_before_must_be_lower_than_after() {
    try {
      underTest.search(IssueQuery.builder().createdAfter(parseDate("2014-09-20")).createdBefore(parseDate("2014-09-19")).build(),
        new SearchOptions());
      Fail.failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
    } catch (IllegalArgumentException exception) {
      assertThat(exception.getMessage()).isEqualTo("Start bound cannot be larger or equal to end bound");
    }
  }

  @Test
  public void fail_if_created_before_equals_created_after() {
    assertThatThrownBy(() -> underTest.search(IssueQuery.builder().createdAfter(parseDate("2014-09-20"))
      .createdBefore(parseDate("2014-09-20")).build(), new SearchOptions()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Start bound cannot be larger or equal to end bound");
  }

  @Test
  public void filter_by_created_after_must_not_be_in_future() {
    try {
      underTest.search(IssueQuery.builder().createdAfter(new Date(Long.MAX_VALUE)).build(), new SearchOptions());
      Fail.failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
    } catch (IllegalArgumentException exception) {
      assertThat(exception.getMessage()).isEqualTo("Start bound cannot be in the future");
    }
  }

  @Test
  public void filter_by_created_at() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(newDoc("I1", file).setFuncCreationDate(parseDate("2014-09-20")));

    assertThatSearchReturnsOnly(IssueQuery.builder().createdAt(parseDate("2014-09-20")), "I1");
    assertThatSearchReturnsEmpty(IssueQuery.builder().createdAt(parseDate("2014-09-21")));
  }

  @Test
  public void filter_by_new_code_reference() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(newDoc("I1", file).setIsNewCodeReference(true),
      newDoc("I2", file).setIsNewCodeReference(false));

    assertThatSearchReturnsOnly(IssueQuery.builder().newCodeOnReference(true), "I1");
  }

  @Test
  public void filter_by_cwe() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setType(RuleType.VULNERABILITY).setCwe(asList("20", "564", "89", "943")),
      newDoc("I2", file).setType(RuleType.VULNERABILITY).setCwe(singletonList("943")),
      newDoc("I3", file));

    assertThatSearchReturnsOnly(IssueQuery.builder().cwe(singletonList("20")), "I1");
  }

  @Test
  public void filter_by_owaspTop10() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setType(RuleType.VULNERABILITY).setOwaspTop10(asList("a1", "a2")),
      newDoc("I2", file).setType(RuleType.VULNERABILITY).setCwe(singletonList("a3")),
      newDoc("I3", file));

    assertThatSearchReturnsOnly(IssueQuery.builder().owaspTop10(singletonList("a1")), "I1");
  }

  @Test
  public void filter_by_sansTop25() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setType(RuleType.VULNERABILITY).setSansTop25(asList("porous-defenses", "risky-resource", "insecure-interaction")),
      newDoc("I2", file).setType(RuleType.VULNERABILITY).setSansTop25(singletonList("porous-defenses")),
      newDoc("I3", file));

    assertThatSearchReturnsOnly(IssueQuery.builder().sansTop25(singletonList("risky-resource")), "I1");
  }

  @Test
  public void filter_by_sonarSecurity() {
    ComponentDto project = newPrivateProjectDto();
    ComponentDto file = newFileDto(project, null);

    indexIssues(
      newDoc("I1", file).setType(RuleType.VULNERABILITY).setSonarSourceSecurityCategory(SQCategory.BUFFER_OVERFLOW),
      newDoc("I2", file).setType(RuleType.VULNERABILITY).setSonarSourceSecurityCategory(SQCategory.DOS),
      newDoc("I3", file));

    assertThatSearchReturnsOnly(IssueQuery.builder().sonarsourceSecurity(singletonList("buffer-overflow")), "I1");
  }

  private void indexIssues(IssueDoc... issues) {
    issueIndexer.index(asList(issues).iterator());
    authorizationIndexer.allow(stream(issues).map(issue -> new IndexPermissions(issue.projectUuid(), PROJECT).allowAnyone()).collect(toList()));
  }

  private void indexView(String viewUuid, List<String> projects) {
    viewIndexer.index(new ViewDoc().setUuid(viewUuid).setProjects(projects));
  }

  /**
   * Execute the search request and return the document ids of results.
   */
  private List<String> searchAndReturnKeys(IssueQuery.Builder query) {
    return Arrays.stream(underTest.search(query.build(), new SearchOptions()).getHits().getHits())
      .map(SearchHit::getId)
      .collect(Collectors.toList());
  }

  private void assertThatSearchReturnsOnly(IssueQuery.Builder query, String... expectedIssueKeys) {
    List<String> keys = searchAndReturnKeys(query);
    assertThat(keys).containsExactlyInAnyOrder(expectedIssueKeys);
  }

  private void assertThatSearchReturnsEmpty(IssueQuery.Builder query) {
    List<String> keys = searchAndReturnKeys(query);
    assertThat(keys).isEmpty();
  }
}
