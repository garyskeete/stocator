/**
 * (C) Copyright IBM Corp. 2015, 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.stocator.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.stocator.fs.common.Constants;
import com.ibm.stocator.fs.common.IStoreClient;
import com.ibm.stocator.fs.common.Utils;
import com.ibm.stocator.fs.common.ObjectStoreGlobber;
import com.ibm.stocator.fs.common.StocatorPath;
import com.ibm.stocator.fs.common.ExtendedFileSystem;

//import static com.ibm.stocator.fs.common.Constants.HADOOP_ATTEMPT;

import static com.ibm.stocator.fs.common.Constants.OUTPUT_COMMITTER_TYPE;
import static com.ibm.stocator.fs.common.Constants.DEFAULT_FOUTPUTCOMMITTER_V1;

/**
 * Object store driver implementation
 * Based on the Hadoop FileSystem interface
 *
 */
public class ObjectStoreFileSystem extends ExtendedFileSystem {

  /*
   * Logger
   */
  private static final Logger LOG = LoggerFactory.getLogger(ObjectStoreFileSystem.class);

  /*
   * Storage client. Contains implementation of the underlying storage.
   */
  private IStoreClient storageClient;
  /*
   * Host name with schema, e.g. schema://dataroot.conf-entry/
   */
  private String hostNameScheme;

  /*
   * full URL to the data path
   */
  private URI uri;
  private StocatorPath stocatorPath;

  @Override
  public String getScheme() {
    return storageClient.getScheme();
  }

  @Override
  public void initialize(URI fsuri, Configuration conf) throws IOException {
    super.initialize(fsuri, conf);
    LOG.trace("Initialize for {}", fsuri);
    if (!conf.getBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", true)) {
      throw new IOException("mapreduce.fileoutputcommitter.marksuccessfuljobs should be enabled");
    }
    uri = URI.create(fsuri.getScheme() + "://" + fsuri.getAuthority());
    setConf(conf);
    String committerType = conf.get(OUTPUT_COMMITTER_TYPE, DEFAULT_FOUTPUTCOMMITTER_V1);
    stocatorPath = new StocatorPath(committerType, conf);
    if (storageClient == null) {
      storageClient = ObjectStoreVisitor.getStoreClient(fsuri, conf);
      if (Utils.validSchema(fsuri.toString())) {
        hostNameScheme = storageClient.getScheme() + "://"  + Utils.getHost(fsuri) + "/";
      } else {
        String accessURL = Utils.extractAccessURL(fsuri.toString());
        hostNameScheme = accessURL + "/" + Utils.extractDataRoot(fsuri.toString(),
            accessURL) + "/";
      }
    }
  }

  @Override
  public URI getUri() {
    return uri;
  }

  /**
   * Check path should check the validity of the path. Skipped at this point.
   */
  @Override
  protected void checkPath(Path path) {
    LOG.trace("Check path: {}", path.toString());
  }

  /**
   * Check if the object exists.
   */
  @Override
  public boolean exists(Path f) throws IOException {
    LOG.debug("exists {}", f.toString());
    /*
    if (stocatorPath.isTemporaryPathContain(f)) {
      LOG.debug("Exists on temp object {}. Return false", f.toString());
      return false;
    }
    */
    String realPath = stocatorPath.getActualPath(f, false,
        storageClient.getDataRoot(), hostNameScheme);
    LOG.debug("exists(start) {}, transformed {}", f.toString(), realPath);
    boolean res =  storageClient.exists(hostNameScheme, new Path(realPath));
    LOG.debug("exists(finish) found: {}: on {}, transformed {}", res, f.toString(), realPath);
    return res;
  }

  /**
   * There is no "directories" in the object store
   * The general structure is "dataroot/object"
   * and "object" may contain nested structure
   */
  @Override
  public boolean isDirectory(Path f) throws IOException {
    LOG.debug("is directory: {}", f.toString());
    return false;
  }

  @Override
  public boolean isFile(Path f) throws IOException {
    LOG.debug("is file: {}", f.toString());
    return true;
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listLocatedStatus(Path f)
      throws FileNotFoundException, IOException {
    LOG.debug("listLocatedStatus: {} ", f.toString());
    return super.listLocatedStatus(f);
  }

  @Override
  protected RemoteIterator<LocatedFileStatus> listLocatedStatus(Path f,
      PathFilter filter)
      throws FileNotFoundException, IOException {
    LOG.debug("listLocatedStatus with path filter: {}", f.toString());
    return super.listLocatedStatus(f, filter);
  }

  @Override
  public FSDataInputStream open(Path f) throws IOException {
    LOG.debug("open: {} without buffer size" , f.toString());
    return storageClient.getObject(hostNameScheme, f);
  }

  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    LOG.debug("open: {} with buffer size {}", f.toString(), bufferSize);
    return storageClient.getObject(hostNameScheme, f);
  }

  /**
   * {@inheritDoc}
   * create path of the form dataroot/objectname
   * Each object name is modified to contain task-id prefix.
   * Thus for example, create
   * dataroot/objectname/_temporary/0/_temporary/attempt_201603131849_0000_m_000019_0/
   * part-r-00019-a08dcbab-8a34-4d80-a51c-368a71db90aa.csv
   * will be transformed to
   * PUT dataroot/object
   * /201603131849_0000_m_000019_0-part-r-00019-a08dcbab-8a34-4d80-a51c-368a71db90aa.csv
   *
   */
  public FSDataOutputStream create(Path f, FsPermission permission,
      boolean overwrite, int bufferSize,
      short replication, long blockSize, Progressable progress) throws IOException {
    LOG.debug("Create: {}, overwrite is: {}", f.toString(), overwrite);
    String objNameModified = "";
    // check if request is dataroot/objectname/_SUCCESS
    if (f.getName().equals(Constants.HADOOP_SUCCESS)) {
      objNameModified =  stocatorPath.getObjectNameRoot(f, false,
          storageClient.getDataRoot(), hostNameScheme, true);
    } else {
      objNameModified = stocatorPath.getObjectNameRoot(f, true,
          storageClient.getDataRoot(), hostNameScheme, true);
    }
    if (stocatorPath.isHive()) {
      LOG.debug("Hive identified and overwrtie mode {} detected for {}", overwrite,
          objNameModified);
      String actuallName = stocatorPath.getActualPath(f, false,
          storageClient.getDataRoot(), hostNameScheme);
      Path p = new Path(actuallName);
      LOG.debug("Original path is {}. Going to list {}", f.toString(), p.toString());
      FileStatus[] fsList = storageClient.list(hostNameScheme, p, true, true);
      LOG.debug("Hive identified: list {} returned {} results ", p, fsList.length);
      if (fsList.length > 0) {
        for (FileStatus fs: fsList) {
          LOG.debug("List result : {}", fs.getPath().getName());
        }
        objNameModified = objNameModified + "_copy_" + fsList.length;
        LOG.debug("Hive identified: going to create {}", objNameModified);
      }
    }
    FSDataOutputStream outStream = storageClient.createObject(objNameModified,
        "application/octet-stream", null, statistics);
    return outStream;
  }

  public FSDataOutputStream append(Path f, int bufferSize,
      Progressable progress) throws IOException {
    throw new IOException("Append is not supported");
  }

  /**
   * {@inheritDoc}
   * We don't need rename on temporary objects, since objects are already were created with real
   * names.
   */
  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    LOG.debug("rename from {} to {}", src.toString(), dst.toString());
    String objNameModified = stocatorPath.getObjectNameRoot(src, true,
        storageClient.getDataRoot(), hostNameScheme, true);
    LOG.debug("Modified object name {}", objNameModified);
    if (stocatorPath.isTemporaryPathContain(objNameModified)) {
      LOG.debug("Rename on the temp object {}. Return true", src);
      return true;
    }
    LOG.debug("Checking if source exists {}", src);
    if (exists(src)) {
      LOG.debug("Source {} exists", src);
    }
    return storageClient.rename(hostNameScheme, src.toString(), dst.toString());
  }

  @Override
  public boolean delete(Path f, boolean recursive) throws IOException {
    LOG.debug("About to delete {}", f.toString());
    if (stocatorPath.isTemporaryPathContain(f.toString())) {
      return true;
    }
    String objNameModified = stocatorPath.getObjectNameRoot(f, true,
        storageClient.getDataRoot(), hostNameScheme, true);
    LOG.debug("delete: {} recursive {}. modifed name {}, hostname {}", f.toString(),
        recursive, objNameModified, hostNameScheme);
    Path pathToObj = new Path(objNameModified);
    if (stocatorPath.isTemporaryPathContain(f.getName())) {
      FileStatus[] fsList = storageClient.list(hostNameScheme, pathToObj.getParent(), true, true);
      if (fsList.length > 0) {
        for (FileStatus fs: fsList) {
          if (fs.getPath().getName().endsWith(f.getName())) {
            storageClient.delete(hostNameScheme, fs.getPath(), recursive);
          }
        }
      }
    } else {
      FileStatus[] fsList = storageClient.list(hostNameScheme, pathToObj, true, true);
      if (fsList.length > 0) {
        for (FileStatus fs: fsList) {
          LOG.trace("Delete candidate {} path {}", fs.getPath().toString(), f.toString());
          String pathToDelete = f.toString();
          if (!pathToDelete.endsWith("/")) {
            pathToDelete = pathToDelete + "/";
          }
          LOG.trace("Delete candidate {} pathToDelete {}", fs.getPath().toString(), pathToDelete);
          if (fs.getPath().toString().equals(f.toString())
              || fs.getPath().toString().startsWith(pathToDelete)) {
            LOG.debug("Delete {} from the list of {}", fs.getPath(), pathToObj);
            storageClient.delete(hostNameScheme, fs.getPath(), recursive);
          }
        }
      }
    }
    return true;
  }

  @Override
  public FileStatus[] listStatus(Path f,
      PathFilter filter) throws FileNotFoundException, IOException {
    return listStatus(f, filter, false);
  }

  @Override
  public FileStatus[] listStatus(Path[] files,
      PathFilter filter) throws FileNotFoundException, IOException {
    return super.listStatus(files, filter);
  }

  @Override
  public FileStatus[] listStatus(Path[] files) throws FileNotFoundException, IOException {
    return super.listStatus(files);
  }

  @Override
  public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
    LOG.debug("List status of {}", f.toString());
    return listStatus(f, null);
  }

  @Override
  public FileStatus[] listStatus(Path f, PathFilter filter, boolean prefixBased)
      throws FileNotFoundException, IOException {
    LOG.debug("list status: {},  prefix based {}",f.toString(), prefixBased);
    FileStatus[] result = {};
    if (stocatorPath.isTemporaryPathContain(f)) {
      return result;
    }
    FileStatus fileStatus = null;
    try {
      fileStatus = getFileStatus(f);
    } catch (FileNotFoundException e) {
      LOG.trace("{} not found. Try to list", f.toString());
    }

    if ((fileStatus != null && fileStatus.isDirectory()) || (fileStatus == null && prefixBased)) {
      LOG.trace("{} is directory, prefix based listing set to {}", f.toString(), prefixBased);
      result = storageClient.list(hostNameScheme, f, false, prefixBased);
    } else if (fileStatus != null) {
      LOG.debug("{} is not directory. Adding without list", f);
      result = new FileStatus[1];
      result[0] = fileStatus;
    }
    return result;
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listFiles(Path f, boolean recursive)
      throws FileNotFoundException, IOException {
    LOG.debug("list files: {}", f.toString());
    return super.listFiles(f, recursive);
  }

  @Override
  public void setWorkingDirectory(Path new_dir) {
    LOG.debug("set working directory: {}", new_dir.toString());

  }

  /**
   * {@inheritDoc}
   *
   * When path is of the form schema://dataroot.provider/objectname/_temporary/0
   * it is assumed that new job started to write it's data.
   * In this case we create an empty object schema://dataroot.provider/objectname
   * that will later be used to identify objects that were created by Spark.
   * This is needed for fault tolerance coverage to identify data that was created
   * by failed jobs or tasks.
   * dataroot/object created as a 0 size object with type application/directory
   *
   */
  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    return mkdirs(f);
  }

  /**
   * {@inheritDoc}
   *
   * When path is of the form schema://dataroot.provider/objectname/_temporary/0
   * it is assumed that new job started to write it's data.
   * In this case we create an empty object schema://dataroot.provider/objectname
   * that will later be used to identify objects that were created by Spark.
   * This is needed for fault tolerance coverage to identify data that was created
   * by failed jobs or tasks.
   * dataroot/object created as a 0 size object with type application/directory
   *
   */
  @Override
  public boolean mkdirs(Path f) throws IOException {
    LOG.debug("mkdirs: {}", f.toString());
    if (stocatorPath.isTemporaryPathTarget(f)) {
      String objNameModified = stocatorPath.getObjectNameRoot(f,true,
          storageClient.getDataRoot(), hostNameScheme, true);
      Path pathToObj = new Path(objNameModified);
      String plainObjName = objNameModified;//pathToObj.getParent().toString();
      LOG.debug("Going to create identifier {}", plainObjName);
      Map<String, String> metadata = new HashMap<String, String>();
      metadata.put("Data-Origin", "stocator");
      FSDataOutputStream outStream = storageClient.createObject(plainObjName,
          Constants.APPLICATION_DIRECTORY, metadata, statistics);
      outStream.close();
    } else {
      /*
        String objName = stocatorPath.getObjectNameRoot(f, false, storageClient.getDataRoot(),
            hostNameScheme, true);
        LOG.trace("mkdirs to create directory {}", objName);
        FSDataOutputStream outStream = storageClient.createObject(objName,
            Constants.APPLICATION_DIRECTORY, null, statistics);
        outStream.close();
        */
    }
    return true;
  }

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    LOG.debug("get file status: {}", f.toString());
    return storageClient.getObjectMetadata(hostNameScheme, f, "fileStatus");
  }

  @Override
  public Path resolvePath(Path p) throws IOException {
    LOG.debug("resolve path: {}", p.toString());
    return super.resolvePath(p);
  }

  @Override
  public long getBlockSize(Path f) throws IOException {
    LOG.debug("get block size: {}", f.toString());
    return getFileStatus(f).getBlockSize();
  }

  @Override
  public ContentSummary getContentSummary(Path f) throws IOException {
    LOG.debug("get content summary: {}", f.toString());
    return super.getContentSummary(f);
  }

  @Override
  public long getDefaultBlockSize(Path f) {
    long defaultBlockSize = super.getDefaultBlockSize(f);
    LOG.trace("Default block size for: {} is {}", f.toString(), defaultBlockSize);
    return defaultBlockSize;
  }

  @Override
  public FileStatus[] globStatus(Path pathPattern) throws IOException {
    LOG.debug("Glob status: {}", pathPattern.toString());
    return new ObjectStoreGlobber(this, pathPattern, DEFAULT_FILTER).glob();
  }

  @Override
  public FileStatus[] globStatus(Path pathPattern, PathFilter filter) throws IOException {
    LOG.debug("Glob status {} with path filter {}",pathPattern.toString(), filter.toString());
    return new ObjectStoreGlobber(this, pathPattern, filter).glob();
  }

  /**
   * {@inheritDoc}
   *
   * @return path to the working directory
   */
  @Override
  public Path getWorkingDirectory() {
    return storageClient.getWorkingDirectory();
  }

  /**
   * Default Path filter
   */
  private static final PathFilter DEFAULT_FILTER = new PathFilter() {
    @Override
    public boolean accept(Path file) {
      return true;
    }
  };

}
