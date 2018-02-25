/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.cts.statsd.metric;

import android.cts.statsd.atom.DeviceAtomTestCase;

import com.android.internal.os.StatsdConfigProto;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.ValueMetric;
import com.android.os.AtomsProto.AppBreadcrumbReported;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.ValueBucketInfo;
import com.android.os.StatsLog.ValueMetricData;
import com.android.os.StatsLog.StatsLogReport;

public class ValueMetricsTests extends DeviceAtomTestCase {
  private static final int APP_BREADCRUMB_REPORTED_A_MATCH_START_ID = 0;
  private static final int APP_BREADCRUMB_REPORTED_A_MATCH_STOP_ID = 1;
  private static final int APP_BREADCRUMB_REPORTED_B_MATCH_START_ID = 2;

  public void testValueMetric() throws Exception {
    // Add AtomMatcher's.
    AtomMatcher startAtomMatcher =
        MetricsUtils.startAtomMatcher(APP_BREADCRUMB_REPORTED_A_MATCH_START_ID);
    AtomMatcher stopAtomMatcher =
        MetricsUtils.stopAtomMatcher(APP_BREADCRUMB_REPORTED_A_MATCH_STOP_ID);
    AtomMatcher atomMatcher =
        MetricsUtils.simpleAtomMatcher(APP_BREADCRUMB_REPORTED_B_MATCH_START_ID);

    StatsdConfigProto.StatsdConfig.Builder builder = MetricsUtils.getEmptyConfig();
    builder.addAtomMatcher(startAtomMatcher);
    builder.addAtomMatcher(stopAtomMatcher);
    builder.addAtomMatcher(atomMatcher);

    // Add ValueMetric.
    FieldMatcher fieldMatcher =
        FieldMatcher.newBuilder().setField(APP_BREADCRUMB_REPORTED_B_MATCH_START_ID).build();
    builder.addValueMetric(
        ValueMetric.newBuilder()
            .setId(MetricsUtils.VALUE_METRIC_ID)
            .setWhat(APP_BREADCRUMB_REPORTED_B_MATCH_START_ID)
            .setBucket(StatsdConfigProto.TimeUnit.CTS)
            .setValueField(FieldMatcher.newBuilder()
                               .setField(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                               .addChild(FieldMatcher.newBuilder().setField(
                                   AppBreadcrumbReported.LABEL_FIELD_NUMBER)))
            .setDimensionsInWhat(FieldMatcher.newBuilder()
                                     .setField(APP_BREADCRUMB_REPORTED_B_MATCH_START_ID)
                                     .build())
            .build());

    // Upload config.
    uploadConfig(builder);

    // Create AppBreadcrumbReported Start/Stop events.
    doAppBreadcrumbReportedStart(1);
    Thread.sleep(1000);
    doAppBreadcrumbReportedStop(1);
    doAppBreadcrumbReportedStart(3);
    doAppBreadcrumbReportedStop(3);

    // Wait for the metrics to propagate to statsd.
    Thread.sleep(2000);

    StatsLogReport metricReport = getStatsLogReport();
    assertEquals(MetricsUtils.VALUE_METRIC_ID, metricReport.getMetricId());
    assertTrue(metricReport.hasValueMetrics());
    StatsLogReport.ValueMetricDataWrapper valueData = metricReport.getValueMetrics();
    assertEquals(valueData.getDataCount(), 1);

    int bucketCount = valueData.getData(0).getBucketInfoCount();
    assertTrue(bucketCount > 1);
    ValueMetricData data = valueData.getData(0);
    int totalValue = 0;
    for (ValueBucketInfo bucketInfo : data.getBucketInfoList()) {
      assertTrue(bucketInfo.hasStartBucketElapsedNanos());
      assertTrue(bucketInfo.hasEndBucketElapsedNanos());
      totalValue += (int) bucketInfo.getValue();
    }
    assertEquals(totalValue, 8);
  }
}
