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
import * as React from 'react';
import { Link } from 'react-router';
import { isValidLicense } from '../../../../api/marketplace';
import { Alert } from '../../../../components/ui/Alert';
import { translate, translateWithParameters } from '../../../../helpers/l10n';
import { AppState } from '../../../../types/appstate';
import { ComponentQualifier } from '../../../../types/component';
import { Task } from '../../../../types/tasks';
import withAppStateContext from '../../app-state/withAppStateContext';

interface Props {
  appState: AppState;
  currentTask?: Task;
}

interface State {
  isValidLicense?: boolean;
  loading: boolean;
}

export class ComponentNavLicenseNotif extends React.PureComponent<Props, State> {
  mounted = false;
  state: State = { loading: false };

  componentDidMount() {
    this.mounted = true;
    this.fetchIsValidLicense();
  }

  componentWillUnmount() {
    this.mounted = false;
  }

  fetchIsValidLicense = () => {
    this.setState({ loading: true });
    isValidLicense().then(
      ({ isValidLicense }) => {
        if (this.mounted) {
          this.setState({ isValidLicense, loading: false });
        }
      },
      () => {
        if (this.mounted) {
          this.setState({ loading: false });
        }
      }
    );
  };

  render() {
    const { currentTask, appState } = this.props;
    const { isValidLicense, loading } = this.state;

    if (loading || !currentTask || !currentTask.errorType) {
      return null;
    }

    if (isValidLicense && currentTask.errorType !== 'LICENSING_LOC') {
      return (
        <Alert display="banner" variant="error">
          {translateWithParameters(
            'component_navigation.status.last_blocked_due_to_bad_license_X',
            translate('qualifier', currentTask.componentQualifier || ComponentQualifier.Project)
          )}
        </Alert>
      );
    }

    return (
      <Alert display="banner" variant="error">
        <span className="little-spacer-right">{currentTask.errorMessage}</span>
        {appState.canAdmin ? (
          <Link to="/admin/extension/license/app">
            {translate('license.component_navigation.button', currentTask.errorType)}.
          </Link>
        ) : (
          translate('please_contact_administrator')
        )}
      </Alert>
    );
  }
}

export default withAppStateContext(ComponentNavLicenseNotif);
