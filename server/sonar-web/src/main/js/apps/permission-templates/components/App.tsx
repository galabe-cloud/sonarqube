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
import { Location } from 'history';
import * as React from 'react';
import { Helmet } from 'react-helmet-async';
import { getPermissionTemplates } from '../../../api/permissions';
import withAppStateContext from '../../../app/components/app-state/withAppStateContext';
import Suggestions from '../../../app/components/embed-docs-modal/Suggestions';
import { translate } from '../../../helpers/l10n';
import { AppState } from '../../../types/appstate';
import { Permission, PermissionTemplate } from '../../../types/types';
import '../../permissions/styles.css';
import { mergeDefaultsToTemplates, mergePermissionsToTemplates, sortPermissions } from '../utils';
import Home from './Home';
import Template from './Template';

interface Props {
  location: Location;
  appState: AppState;
}

interface State {
  ready: boolean;
  permissions: Permission[];
  permissionTemplates: PermissionTemplate[];
}

export class App extends React.PureComponent<Props, State> {
  mounted = false;
  state: State = {
    ready: false,
    permissions: [],
    permissionTemplates: []
  };

  componentDidMount() {
    this.mounted = true;
    this.requestPermissions();
  }

  componentWillUnmount() {
    this.mounted = false;
  }

  requestPermissions = async () => {
    const { permissions, defaultTemplates, permissionTemplates } = await getPermissionTemplates();

    if (this.mounted) {
      const sortedPerm = sortPermissions(permissions);
      const permissionTemplatesMerged = mergeDefaultsToTemplates(
        mergePermissionsToTemplates(permissionTemplates, sortedPerm),
        defaultTemplates
      );
      this.setState({
        ready: true,
        permissionTemplates: permissionTemplatesMerged,
        permissions: sortedPerm
      });
    }
  };

  renderTemplate(id: string) {
    if (!this.state.ready) {
      return null;
    }

    const template = this.state.permissionTemplates.find(t => t.id === id);
    if (!template) {
      return null;
    }

    return (
      <Template
        refresh={this.requestPermissions}
        template={template}
        topQualifiers={this.props.appState.qualifiers}
      />
    );
  }

  renderHome() {
    return (
      <Home
        permissionTemplates={this.state.permissionTemplates}
        permissions={this.state.permissions}
        ready={this.state.ready}
        refresh={this.requestPermissions}
        topQualifiers={this.props.appState.qualifiers}
      />
    );
  }

  render() {
    const { id } = this.props.location.query;
    return (
      <div>
        <Suggestions suggestions="permission_templates" />
        <Helmet defer={false} title={translate('permission_templates.page')} />

        {id && this.renderTemplate(id)}
        {!id && this.renderHome()}
      </div>
    );
  }
}

export default withAppStateContext(App);
