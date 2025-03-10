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
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IssuesServiceMock from '../../../api/mocks/IssuesServiceMock';
import { renderOwaspTop102021Category } from '../../../helpers/security-standard';
import { renderComponentApp } from '../../../helpers/testReactTestingUtils';
import AppContainer from '../components/AppContainer';

jest.mock('../../../api/issues');

let handler: IssuesServiceMock;

beforeAll(() => {
  handler = new IssuesServiceMock();
});

afterEach(() => handler.reset());

jest.setTimeout(10_000);

it('should support OWASP Top 10 version 2021', async () => {
  const user = userEvent.setup();
  renderIssueApp();
  await user.click(await screen.findByRole('link', { name: 'issues.facet.standards' }));
  const owaspTop102021 = screen.getByRole('link', { name: 'issues.facet.owaspTop10_2021' });
  expect(owaspTop102021).toBeInTheDocument();

  await user.click(owaspTop102021);
  await Promise.all(
    handler.owasp2021FacetList().values.map(async ({ val }) => {
      const standard = await handler.getStandards();
      /* eslint-disable-next-line testing-library/render-result-naming-convention */
      const linkName = renderOwaspTop102021Category(standard, val);
      expect(await screen.findByRole('link', { name: linkName })).toBeInTheDocument();
    })
  );
});

function renderIssueApp() {
  renderComponentApp('project/issues', AppContainer);
}
