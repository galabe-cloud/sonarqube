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
import { shallow } from 'enzyme';
import * as React from 'react';
import { click } from '../../../helpers/testUtils';
import Header, { Props } from '../Header';

jest.mock('../../../helpers/system', () => ({
  getReactDomContainerSelector: jest.fn(() => '#content'),
  isSonarCloud: jest.fn().mockReturnValue(false)
}));

it('renders', () => {
  expect(shallowRender()).toMatchSnapshot('default');
  expect(shallowRender({ defaultProjectVisibility: undefined })).toMatchSnapshot(
    'undefined visibility'
  );
});

it('creates project', () => {
  const onProjectCreate = jest.fn();
  const wrapper = shallowRender({ onProjectCreate });
  click(wrapper.find('#create-project'));
  expect(onProjectCreate).toBeCalledWith();
});

it('changes default visibility', () => {
  const onChangeDefaultProjectVisibility = jest.fn();
  const wrapper = shallowRender({ onChangeDefaultProjectVisibility });

  click(wrapper.find('.js-change-visibility'));

  const modalWrapper = wrapper.find('ChangeDefaultVisibilityForm');
  expect(modalWrapper).toMatchSnapshot();
  modalWrapper.prop<Function>('onConfirm')('private');
  expect(onChangeDefaultProjectVisibility).toBeCalledWith('private');

  modalWrapper.prop<Function>('onClose')();
  wrapper.update();
  expect(wrapper.find('ChangeDefaultVisibilityForm').exists()).toBe(false);
});

function shallowRender(props?: { [P in keyof Props]?: Props[P] }) {
  return shallow(
    <Header
      defaultProjectVisibility="public"
      hasProvisionPermission={true}
      onChangeDefaultProjectVisibility={jest.fn()}
      onProjectCreate={jest.fn()}
      {...props}
    />
  );
}
