/**
 * Copyright © 2016 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.twitter;

import com.github.jcustenborder.kafka.connect.utils.VersionUtil;
import com.github.jcustenborder.kafka.connect.utils.data.SourceRecordDeque;
import com.github.jcustenborder.kafka.connect.utils.data.SourceRecordDequeBuilder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.twitter.clientlib.ApiException;
import com.twitter.clientlib.TwitterCredentialsBearer;
import com.twitter.clientlib.api.TwitterApi;
import com.twitter.clientlib.model.AddOrDeleteRulesRequest;
import com.twitter.clientlib.model.AddRulesRequest;
import com.twitter.clientlib.model.RuleNoId;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twitter4j.FilterQuery;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterStreamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class TwitterSourceTask extends SourceTask implements StatusListener {
  static final Logger log = LoggerFactory.getLogger(TwitterSourceTask.class);
  SourceRecordDeque messageQueue;

  InputStream twitterStream;
  TwitterSourceConnectorConfig config;

  @Override
  public String version() {
    return VersionUtil.version(this.getClass());
  }

  @Override
  public void start(Map<String, String> map) {
    this.config = new TwitterSourceConnectorConfig(map);
    this.messageQueue = SourceRecordDequeBuilder.of()
        .emptyWaitMs(this.config.queueEmptyMs)
        .batchSize(this.config.queueBatchSize)
        .build();

    TwitterApi apiInstance = new TwitterApi(new TwitterCredentialsBearer(this.config.bearerToken));
    try {
      if (this.config.filterRule != null) {
        if (log.isInfoEnabled()) {
          log.info("Setting up filter rule = {}", this.config.filterRule);
        }
        AddRulesRequest add = new AddRulesRequest();
        RuleNoId rule = new RuleNoId();
        rule.setValue(this.config.filterRule);
        add.addAddItem(rule);
        apiInstance.tweets().addOrDeleteRules(new AddOrDeleteRulesRequest(add)).execute();
        if (log.isInfoEnabled()) {
          log.info("Starting the twitter search stream.");
        }
        this.twitterStream = apiInstance.tweets().searchStream().execute();
      } else {
        if (log.isInfoEnabled()) {
          log.info("Starting the twitter sample stream.");
        }
        this.twitterStream = apiInstance.tweets().sampleStream().execute();
      }
    } catch (ApiException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    return this.messageQueue.getBatch();
  }

  @Override
  public void stop() {
    if (log.isInfoEnabled()) {
      log.info("Shutting down twitter stream.");
    }
    try {
      twitterStream.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onStatus(Status status) {
    try {
      Struct keyStruct = new Struct(StatusConverter.STATUS_SCHEMA_KEY);
      Struct valueStruct = new Struct(StatusConverter.STATUS_SCHEMA);

      StatusConverter.convertKey(status, keyStruct);
      StatusConverter.convert(status, valueStruct);

      Map<String, ?> sourcePartition = ImmutableMap.of();
      Map<String, ?> sourceOffset = ImmutableMap.of();

      SourceRecord record = new SourceRecord(sourcePartition, sourceOffset, this.config.topic, StatusConverter.STATUS_SCHEMA_KEY, keyStruct, StatusConverter.STATUS_SCHEMA, valueStruct);
      this.messageQueue.add(record);
    } catch (Exception ex) {
      if (log.isErrorEnabled()) {
        log.error("Exception thrown", ex);
      }
    }
  }

  @Override
  public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
    if (!this.config.processDeletes) {
      return;
    }

    try {
      Struct keyStruct = new Struct(StatusConverter.SCHEMA_STATUS_DELETION_NOTICE_KEY);

      StatusConverter.convertKey(statusDeletionNotice, keyStruct);

      Map<String, ?> sourcePartition = ImmutableMap.of();
      Map<String, ?> sourceOffset = ImmutableMap.of();

      SourceRecord record = new SourceRecord(sourcePartition, sourceOffset, this.config.topic, StatusConverter.SCHEMA_STATUS_DELETION_NOTICE_KEY, keyStruct, null, null);
      this.messageQueue.add(record);
    } catch (Exception ex) {
      if (log.isErrorEnabled()) {
        log.error("Exception thrown", ex);
      }
    }
  }

  @Override
  public void onTrackLimitationNotice(int i) {

  }

  @Override
  public void onScrubGeo(long l, long l1) {

  }

  @Override
  public void onStallWarning(StallWarning stallWarning) {
    if (log.isWarnEnabled()) {
      log.warn("code = '{}' percentFull = '{}' - {}", stallWarning.getCode(), stallWarning.getPercentFull(), stallWarning.getMessage());
    }
  }

  @Override
  public void onException(Exception e) {
    if (log.isErrorEnabled()) {
      log.error("onException", e);
    }
  }
}