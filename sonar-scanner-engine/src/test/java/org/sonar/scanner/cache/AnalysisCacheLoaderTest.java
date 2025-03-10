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
package org.sonar.scanner.cache;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.DeflaterInputStream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.sonar.api.scanner.fs.InputProject;
import org.sonar.api.utils.MessageException;
import org.sonar.scanner.bootstrap.DefaultScannerWsClient;
import org.sonar.scanner.protocol.internal.ScannerInternal.AnalysisCacheMsg;
import org.sonar.scanner.scan.branch.BranchConfiguration;
import org.sonar.scanner.scan.branch.BranchType;
import org.sonarqube.ws.client.HttpException;
import org.sonarqube.ws.client.WsRequest;
import org.sonarqube.ws.client.WsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.scanner.cache.AnalysisCacheLoader.CONTENT_ENCODING;

public class AnalysisCacheLoaderTest {
  private final static AnalysisCacheMsg MSG = AnalysisCacheMsg.newBuilder()
    .putMap("key", ByteString.copyFrom("value", StandardCharsets.UTF_8))
    .build();
  private final WsResponse response = mock(WsResponse.class);
  private final DefaultScannerWsClient wsClient = mock(DefaultScannerWsClient.class);
  private final InputProject project = mock(InputProject.class);
  private final BranchConfiguration branchConfiguration = mock(BranchConfiguration.class);
  private final AnalysisCacheLoader loader = new AnalysisCacheLoader(wsClient, project, branchConfiguration);

  @Before
  public void before() {
    when(project.key()).thenReturn("myproject");
    when(wsClient.call(any())).thenReturn(response);
  }

  @Test
  public void loads_content() throws IOException {
    setResponse(MSG);
    AnalysisCacheMsg msg = loader.load().get();
    assertThat(msg).isEqualTo(MSG);
    assertRequestPath("api/analysis_cache/get?project=myproject");
  }

  @Test
  public void loads_content_for_branch() throws IOException {
    when(branchConfiguration.referenceBranchName()).thenReturn("name");

    setResponse(MSG);
    AnalysisCacheMsg msg = loader.load().get();

    assertThat(msg).isEqualTo(MSG);
    assertRequestPath("api/analysis_cache/get?project=myproject&branch=name");
  }

  @Test
  public void loads_compressed_content() throws IOException {
    setCompressedResponse(MSG);
    AnalysisCacheMsg msg = loader.load().get();
    assertThat(msg).isEqualTo(MSG);
  }

  @Test
  public void returns_empty_if_404() {
    when(wsClient.call(any())).thenThrow(new HttpException("url", 404, "content"));
    assertThat(loader.load()).isEmpty();
  }

  @Test
  public void throw_error_if_http_exception_not_404() {
    when(wsClient.call(any())).thenThrow(new HttpException("url", 401, "content"));
    assertThatThrownBy(loader::load)
      .isInstanceOf(MessageException.class)
      .hasMessage("Failed to download analysis cache: HTTP code 401: content");
  }

  @Test
  public void throw_error_if_cant_decompress_content() {
    setInvalidCompressedResponse();
    assertThatThrownBy(loader::load)
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Failed to download analysis cache");
  }

  private void assertRequestPath(String expectedPath) {
    ArgumentCaptor<WsRequest> requestCaptor = ArgumentCaptor.forClass(WsRequest.class);
    verify(wsClient).call(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getPath()).isEqualTo(expectedPath);
  }

  private void setResponse(AnalysisCacheMsg msg) throws IOException {
    when(response.contentStream()).thenReturn(createInputStream(msg));
  }

  private void setCompressedResponse(AnalysisCacheMsg msg) throws IOException {
    when(response.contentStream()).thenReturn(new DeflaterInputStream(createInputStream(msg)));
    when(response.header(CONTENT_ENCODING)).thenReturn(Optional.of("gzip"));
  }

  private void setInvalidCompressedResponse() {
    when(response.contentStream()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    when(response.header(CONTENT_ENCODING)).thenReturn(Optional.of("gzip"));
  }

  private InputStream createInputStream(AnalysisCacheMsg analysisCacheMsg) throws IOException {
    ByteArrayOutputStream serialized = new ByteArrayOutputStream(analysisCacheMsg.getSerializedSize());
    analysisCacheMsg.writeTo(serialized);
    return new ByteArrayInputStream(serialized.toByteArray());
  }
}
