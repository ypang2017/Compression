/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.hdfs.action;

import com.google.gson.Gson;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartdata.action.ActionException;
import org.smartdata.action.Utils;
import org.smartdata.action.annotation.ActionSignature;
import org.smartdata.hdfs.SmartCompressorStream;
import org.smartdata.model.CompressionFileState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This action convert a file to a compressed file.
 */
@ActionSignature(
    actionId = "compress",
    displayName = "compress",
    usage =
        HdfsAction.FILE_PATH
            + " $file "
            + CompressionAction.BUF_SIZE
            + " $size "
            + CompressionAction.COMPRESS_IMPL
            + " $impl"
)
public class CompressionAction extends HdfsAction {
  private static final Logger LOG =
      LoggerFactory.getLogger(CompressionAction.class);

  public static final String BUF_SIZE = "-bufSize";
  public static final String COMPRESS_IMPL = "-compressImpl";
  private static List<String> compressionImplList = Arrays.asList(new String[]{
    "Lz4","Bzip2","Zlib","snappy"});

  private String filePath;
  private int bufferSize = 1024 * 1024;
  private String compressionImpl = "snappy";
  private int UserDefinedbuffersize;
  private int Calculatedbuffersize;

  private CompressionFileState compressionInfo;

  @Override
  public void init(Map<String, String> args) {
    super.init(args);
    this.filePath = args.get(FILE_PATH);
    if (args.containsKey(BUF_SIZE)) {
      this.UserDefinedbuffersize = Integer.valueOf(args.get(BUF_SIZE));
    }
    if (args.containsKey(COMPRESS_IMPL)) {
      this.compressionImpl = args.get(COMPRESS_IMPL);
    }
  }

  @Override
  protected void execute() throws Exception {
    if (filePath == null) {
      throw new IllegalArgumentException("File parameter is missing.");
    }
    if (!compressionImplList.contains(compressionImpl)) {
      throw new ActionException("Action fails, this compressionImpl isn't supported!");
    }
    appendLog(
        String.format("Action starts at %s : Read %s", Utils.getFormatedCurrentTime(), filePath));

    if (!defaultDfsClient.exists(filePath)) {
      throw new ActionException("ReadFile Action fails, file doesn't exist!");
    }
    // Generate compressed file
    String compressedFileName = "/tmp/ssm" + filePath + "." + System.currentTimeMillis() + ".ssm_compression";
    HdfsFileStatus srcFile = defaultDfsClient.getFileInfo(filePath);
    short replication = srcFile.getReplication();
    long blockSize = srcFile.getBlockSize();
    long fileSize = srcFile.getLen();
    //The capacity of originalPos and compressedPos is 5000 in database 
    this.Calculatedbuffersize = (int)fileSize/5000;
    
    //Determine the actual buffersize
    if(UserDefinedbuffersize < bufferSize || UserDefinedbuffersize < Calculatedbuffersize){
      if(bufferSize <= Calculatedbuffersize){
        appendLog("User defined buffersize is too small,use the calculated buffersize:" + Calculatedbuffersize );
      }else{
        appendLog("User defined buffersize is too small,use the default buffersize:" + bufferSize );
      }
    }
    bufferSize = Math.max(Math.max(UserDefinedbuffersize,Calculatedbuffersize),bufferSize);
    
    DFSInputStream dfsInputStream = defaultDfsClient.open(filePath);
    compressionInfo = new CompressionFileState(filePath, bufferSize, compressionImpl);
    compressionInfo.setCompressionImpl(compressionImpl);
    compressionInfo.setOriginalLength(srcFile.getLen());
    OutputStream compressedOutputStream = defaultDfsClient.create(compressedFileName,
      true, replication, blockSize);
    compress(dfsInputStream, compressedOutputStream);
    HdfsFileStatus destFile = defaultDfsClient.getFileInfo(compressedFileName);
    compressionInfo.setCompressedLength(destFile.getLen());
    String compressionInfoJson = new Gson().toJson(compressionInfo);
    appendResult(compressionInfoJson);

    // Replace the original file with the compressed file
    defaultDfsClient.delete(filePath);
    defaultDfsClient.rename(compressedFileName, filePath);
  }

  private void compress(InputStream inputStream, OutputStream outputStream) throws IOException {
    SmartCompressorStream smartCompressorStream = new SmartCompressorStream(
        inputStream, outputStream, bufferSize, compressionInfo);
    smartCompressorStream.convert();
  }
}
