/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.CASContainsRequest;
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.FetchBuildGraphRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesResponse;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.PathInfo;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.SetBuckDotFilePathsRequest;
import com.facebook.buck.distributed.thrift.SetBuckVersionRequest;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.distributed.thrift.StoreBuildGraphRequest;
import com.facebook.buck.distributed.thrift.StoreLocalChangesRequest;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;


public class DistBuildService implements Closeable {
  private static final Logger LOG = Logger.get(DistBuildService.class);
  private final FrontendService service;

  public DistBuildService(
      FrontendService service) {
    this.service = service;
  }

  public MultiGetBuildSlaveRealTimeLogsResponse fetchSlaveLogLines(
      final StampedeId stampedeId,
      final List<LogLineBatchRequest> logLineRequests) throws IOException {

    MultiGetBuildSlaveRealTimeLogsRequest getLogLinesRequest =
        new MultiGetBuildSlaveRealTimeLogsRequest();
    getLogLinesRequest.setStampedeId(stampedeId);
    getLogLinesRequest.setBatches(logLineRequests);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_REAL_TIME_LOGS);
    request.setMultiGetBuildSlaveRealTimeLogsRequest(getLogLinesRequest);
    FrontendResponse response = makeRequestChecked(request);
    return response.getMultiGetBuildSlaveRealTimeLogsResponse();
  }

  public MultiGetBuildSlaveLogDirResponse fetchBuildSlaveLogDir(
      final StampedeId stampedeId,
      final List<RunId> runIds) throws IOException {

    MultiGetBuildSlaveLogDirRequest getBuildSlaveLogDirRequest =
        new MultiGetBuildSlaveLogDirRequest();
    getBuildSlaveLogDirRequest.setStampedeId(stampedeId);
    getBuildSlaveLogDirRequest.setRunIds(runIds);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_LOG_DIR);
    request.setMultiGetBuildSlaveLogDirRequest(getBuildSlaveLogDirRequest);
    FrontendResponse response = makeRequestChecked(request);
    return response.getMultiGetBuildSlaveLogDirResponse();
  }

  public ListenableFuture<Void> uploadTargetGraph(
      final BuildJobState buildJobStateArg,
      final StampedeId stampedeId,
      ListeningExecutorService executorService) {
    // TODO(shivanker): We shouldn't be doing this. Fix after we stop reading all files into memory.
    final BuildJobState buildJobState = buildJobStateArg.deepCopy();
    return executorService.submit(() -> {
      // Get rid of file contents from buildJobState
      for (BuildJobStateFileHashes cell : buildJobState.getFileHashes()) {
        if (!cell.isSetEntries()) {
          continue;
        }
        for (BuildJobStateFileHashEntry file : cell.getEntries()) {
          file.unsetContents();
        }
      }

      // Now serialize and send the whole buildJobState
      StoreBuildGraphRequest storeBuildGraphRequest = new StoreBuildGraphRequest();
      storeBuildGraphRequest.setStampedeId(stampedeId);
      storeBuildGraphRequest.setBuildGraph(BuildJobStateSerializer.serialize(buildJobState));

      FrontendRequest request = new FrontendRequest();
      request.setType(FrontendRequestType.STORE_BUILD_GRAPH);
      request.setStoreBuildGraphRequest(storeBuildGraphRequest);
      makeRequestChecked(request);
      // No response expected.
      return null;
    });
  }

  public ListenableFuture<Void> uploadMissingFiles(
      final List<BuildJobStateFileHashes> fileHashes,
      ListeningExecutorService executorService) {
    List<FileInfo> requiredFiles = new ArrayList<>();
    for (BuildJobStateFileHashes filesystem : fileHashes) {
      if (!filesystem.isSetEntries()) {
        continue;
      }
      for (BuildJobStateFileHashEntry file : filesystem.entries) {
        if (file.isSetRootSymLink()) {
          LOG.info(
              "File with path [%s] is a symlink. Skipping upload..",
              file.path.getPath());
          continue;
        } else if (file.isIsDirectory()) {
          LOG.info(
              "Path [%s] is a directory. Skipping upload..",
              file.path.getPath());
          continue;
        } else if (file.isPathIsAbsolute()) {
          LOG.info(
              "Path [%s] is absolute. Skipping upload..",
              file.path.getPath());
          continue;
        }

        // TODO(shivanker): Eventually, we won't have file contents in BuildJobState.
        // Then change this code to load file contents inline (only for missing files)
        FileInfo fileInfo = new FileInfo();
        fileInfo.setContent(file.getContents());
        fileInfo.setContentHash(file.getHashCode());
        requiredFiles.add(fileInfo);
      }
    }

    return uploadMissingFilesFromList(requiredFiles, executorService);
  }

  private ListenableFuture<Void> uploadMissingFilesFromList(
      final List<FileInfo> fileList,
      ListeningExecutorService executorService) {
    return executorService.submit(() -> {
      Map<String, FileInfo> sha1ToFileInfo = new HashMap<>();
      for (FileInfo file : fileList) {
        sha1ToFileInfo.put(file.getContentHash(), file);
      }

      List<String> contentHashes = ImmutableList.copyOf(sha1ToFileInfo.keySet());
      CASContainsRequest containsReq = new CASContainsRequest();
      containsReq.setContentSha1s(contentHashes);
      FrontendRequest request = new FrontendRequest();
      request.setType(FrontendRequestType.CAS_CONTAINS);
      request.setCasContainsRequest(containsReq);
      FrontendResponse response = makeRequestChecked(request);

      Preconditions.checkState(
          response.getCasContainsResponse().exists.size() == contentHashes.size());
      List<Boolean> isPresent = response.getCasContainsResponse().exists;
      List<FileInfo> filesToBeUploaded = new LinkedList<>();
      for (int i = 0; i < isPresent.size(); ++i) {
        if (isPresent.get(i)) {
          continue;
        }
        filesToBeUploaded.add(sha1ToFileInfo.get(contentHashes.get(i)));
      }

      LOG.info(
          "%d out of %d files already exist in the cache. Uploading %d files..",
          sha1ToFileInfo.size() - filesToBeUploaded.size(),
          sha1ToFileInfo.size(),
          filesToBeUploaded.size());
      request = new FrontendRequest();
      StoreLocalChangesRequest storeReq = new StoreLocalChangesRequest();
      storeReq.setFiles(filesToBeUploaded);
      request.setType(FrontendRequestType.STORE_LOCAL_CHANGES);
      request.setStoreLocalChangesRequest(storeReq);
      makeRequestChecked(request);
      // No response expected.
      return null;
    });
  }

  public BuildJob createBuild() throws IOException {
    // Tell server to create the build and get the build id.
    CreateBuildRequest createTimeRequest = new CreateBuildRequest();
    createTimeRequest.setCreateTimestampMillis(System.currentTimeMillis());
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createTimeRequest);
    FrontendResponse response = makeRequestChecked(request);

    return response.getCreateBuildResponse().getBuildJob();
  }

  public BuildJob startBuild(StampedeId id) throws IOException {
    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setStampedeId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuildRequest(startRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getStartBuildResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJob getCurrentBuildJobState(StampedeId id) throws IOException {
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setStampedeId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatusRequest(statusRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getBuildStatusResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJobState fetchBuildJobState(StampedeId stampedeId) throws IOException {
    FrontendRequest request = createFetchBuildGraphRequest(stampedeId);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchBuildGraphResponse());
    Preconditions.checkState(response.getFetchBuildGraphResponse().isSetBuildGraph());
    Preconditions.checkState(
        response.getFetchBuildGraphResponse().getBuildGraph().length > 0);

    return BuildJobStateSerializer.deserialize(
        response.getFetchBuildGraphResponse().getBuildGraph());
  }

  public static FrontendRequest createFetchBuildGraphRequest(StampedeId stampedeId) {
    FetchBuildGraphRequest fetchBuildGraphRequest = new FetchBuildGraphRequest();
    fetchBuildGraphRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_GRAPH);
    frontendRequest.setFetchBuildGraphRequest(fetchBuildGraphRequest);
    return frontendRequest;
  }

  public InputStream fetchSourceFile(String hashCode) throws IOException {
    FrontendRequest request = createFetchSourceFileRequest(hashCode);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchSourceFilesResponse());
    Preconditions.checkState(response.getFetchSourceFilesResponse().isSetFiles());
    FetchSourceFilesResponse fetchSourceFilesResponse = response.getFetchSourceFilesResponse();
    Preconditions.checkState(1 == fetchSourceFilesResponse.getFilesSize());
    FileInfo file = fetchSourceFilesResponse.getFiles().get(0);
    Preconditions.checkState(file.isSetContent());

    return new ByteArrayInputStream(file.getContent());
  }

  public static FrontendRequest createFetchSourceFileRequest(String fileHash) {
    FetchSourceFilesRequest fetchSourceFileRequest = new FetchSourceFilesRequest();
    fetchSourceFileRequest.setContentHashesIsSet(true);
    fetchSourceFileRequest.addToContentHashes(fileHash);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_SRC_FILES);
    frontendRequest.setFetchSourceFilesRequest(fetchSourceFileRequest);
    return frontendRequest;
  }

  public static FrontendRequest createFrontendBuildStatusRequest(StampedeId stampedeId) {
    BuildStatusRequest buildStatusRequest = new BuildStatusRequest();
    buildStatusRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.BUILD_STATUS);
    frontendRequest.setBuildStatusRequest(buildStatusRequest);
    return frontendRequest;
  }

  public void setBuckVersion(StampedeId id, BuckVersion buckVersion) throws IOException {
    SetBuckVersionRequest setBuckVersionRequest = new SetBuckVersionRequest();
    setBuckVersionRequest.setStampedeId(id);
    setBuckVersionRequest.setBuckVersion(buckVersion);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_BUCK_VERSION);
    request.setSetBuckVersionRequest(setBuckVersionRequest);
    makeRequestChecked(request);
  }

  public void setBuckDotFiles(StampedeId id, List<PathInfo> dotFiles) throws IOException {
    SetBuckDotFilePathsRequest storeBuckDotFilesRequest = new SetBuckDotFilePathsRequest();
    storeBuckDotFilesRequest.setStampedeId(id);
    storeBuckDotFilesRequest.setDotFiles(dotFiles);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_DOTFILE_PATHS);
    request.setSetBuckDotFilePathsRequest(storeBuckDotFilesRequest);
    makeRequestChecked(request);
  }

  public ListenableFuture<Void> uploadBuckDotFiles(
      final StampedeId id,
      final ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      ListeningExecutorService executorService) throws IOException {
    ListenableFuture<Pair<List<FileInfo>, List<PathInfo>>> filesFuture =
        executorService.submit(() -> {

          Path[] buckDotFilesExceptConfig =
              Arrays.stream(filesystem.listFiles(Paths.get(".")))
                  .filter(f -> !f.isDirectory())
                  .filter(f -> !Files.isSymbolicLink(f.toPath()))
                  .filter(f -> f.getName().startsWith("."))
                  .filter(f -> f.getName().contains("buck"))
                  .filter(f -> !f.getName().startsWith(".buckconfig"))
                  .map(f -> f.toPath())
                  .toArray(Path[]::new);

          List<FileInfo> fileEntriesToUpload = new LinkedList<>();
          List<PathInfo> pathEntriesToUpload = new LinkedList<>();
          for (Path path : buckDotFilesExceptConfig) {
            FileInfo fileInfoObject = new FileInfo();
            fileInfoObject.setContent(filesystem.readFileIfItExists(path).get().getBytes());
            fileInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
            fileEntriesToUpload.add(fileInfoObject);

            PathInfo pathInfoObject = new PathInfo();
            pathInfoObject.setPath(path.toString());
            pathInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
            pathEntriesToUpload.add(pathInfoObject);
          }

          return new Pair<>(
              fileEntriesToUpload,
              pathEntriesToUpload);
        });

    ListenableFuture<Void> setFilesFuture = Futures.transformAsync(
        filesFuture,
        filesAndPaths -> {
          setBuckDotFiles(id, filesAndPaths.getSecond());
          return Futures.immediateFuture(null);
        },
        executorService);

    ListenableFuture<Void> uploadFilesFuture = Futures.transformAsync(
        filesFuture,
        filesAndPaths -> {
          uploadMissingFilesFromList(filesAndPaths.getFirst(), executorService);
          return Futures.immediateFuture(null);
        },
        executorService);

    return Futures.transform(
        Futures.allAsList(ImmutableList.of(setFilesFuture, uploadFilesFuture)),
        new Function<List<Void>, Void>() {
          @Nullable
          @Override
          public Void apply(@Nullable List<Void> input) {
            return null;
          }
        });
  }

  @Override
  public void close() throws IOException {
    service.close();
  }

  private FrontendResponse makeRequestChecked(FrontendRequest request) throws IOException {
    FrontendResponse response = service.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(String.format(
          "Stampede request of type [%s] failed with error message [%s].",
          request.getType().toString(),
          response.getErrorMessage()));
    }
    Preconditions.checkState(request.getType().equals(response.getType()));
    return response;
  }
}
