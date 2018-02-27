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
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog;
import com.android.tradefed.device.DeviceNotAvailableException;

import java.util.List;

public class MetricsTestCase extends DeviceAtomTestCase {

    private static abstract class TestCase {
        private final StatsdConfigProto.StatsdConfig mConfig;
        private final String mMethod;
        private final long mWaitTimeMs;

        public TestCase(StatsdConfigProto.StatsdConfig config, String methodName, long waitTimeMs) {
            mConfig = config;
            mMethod = methodName;
            mWaitTimeMs = waitTimeMs;
        }

        public StatsdConfigProto.StatsdConfig getConfig() {
            return mConfig;
        }

        public String getMethod() {
            return mMethod;
        }

        public long getWaitTimeMs() {
            return mWaitTimeMs;
        }

        /**
         * Results validation.
         */
        abstract void verifyReport(StatsLog.ConfigMetricsReport report);
    }

    private void testMetrics(TestCase test) throws DeviceNotAvailableException {
        try {
            uploadConfig(test.getConfig());
            runDeviceTests(DEVICE_SIDE_TEST_PACKAGE,
                    ".MetricsTests", test.getMethod());
            Thread.sleep(test.getWaitTimeMs());
            test.verifyReport(getReportCommon());
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            try {
                removeConfig(CONFIG_ID);
            } catch (Exception e) {
                // ignored.
            }
        }

    }

    public void testSimpleEventCountMetric() throws DeviceNotAvailableException {
        long matcherId = 1;
        StatsdConfigProto.StatsdConfig.Builder builder = MetricsUtils.getEmptyConfig();
        builder.addCountMetric(StatsdConfigProto.CountMetric.newBuilder()
                .setId(MetricsUtils.COUNT_METRIC_ID)
                .setBucket(StatsdConfigProto.TimeUnit.CTS)
                .setWhat(matcherId))
                .addAtomMatcher(MetricsUtils.getAtomMatcher(
                        Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER).setId(matcherId).build());

        TestCase testCase = new TestCase(builder.build(), "testSimpleEventCountMetric", 1000) {
            @Override
            void verifyReport(StatsLog.ConfigMetricsReport report) {
                assertEquals(1, report.getMetricsCount());
                com.android.os.StatsLog.StatsLogReport metricReport = report.getMetrics(0);
                assertEquals(MetricsUtils.COUNT_METRIC_ID, metricReport.getMetricId());
                assertTrue(metricReport.hasCountMetrics());

                com.android.os.StatsLog.StatsLogReport.CountMetricDataWrapper countData
                        = metricReport.getCountMetrics();

                assertTrue(countData.getDataCount() > 0);
                assertEquals(2, countData.getData(0).getBucketInfo(0).getCount());
            }
        };
        testMetrics(testCase);
    }

    public void testEventCountWithCondition() throws DeviceNotAvailableException {
        long startMatcherId = 1;
        long endMatcherId = 2;
        long whatMatcherId = 3;
        long conditionId = 4;

        StatsdConfigProto.AtomMatcher plugMatcher =
                MetricsUtils.getAtomMatcher(Atom.PLUGGED_STATE_CHANGED_FIELD_NUMBER)
                        .setId(whatMatcherId).build();

        StatsdConfigProto.AtomMatcher predicateStartMatcher =
                StatsdConfigProto.AtomMatcher.newBuilder()
                        .setId(startMatcherId).setSimpleAtomMatcher(
                        StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                                .setAtomId(Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER)
                                .addFieldValueMatcher(
                                        StatsdConfigProto.FieldValueMatcher.newBuilder()
                                                .setField(1).setEqInt(1))).build();

        StatsdConfigProto.AtomMatcher predicateEndMatcher =
                StatsdConfigProto.AtomMatcher.newBuilder()
                        .setId(endMatcherId).setSimpleAtomMatcher(
                        StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                                .setAtomId(Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER)
                                .addFieldValueMatcher(
                                        StatsdConfigProto.FieldValueMatcher.newBuilder()
                                                .setField(1).setEqInt(2))).build();

        StatsdConfigProto.SimplePredicate predicate = StatsdConfigProto.SimplePredicate.newBuilder()
                .setStart(startMatcherId)
                .setStop(endMatcherId)
                .setCountNesting(false)
                .build();

        StatsdConfigProto.Predicate p = StatsdConfigProto.Predicate.newBuilder()
                .setSimplePredicate(predicate)
                .setId(conditionId)
                .build();

        StatsdConfigProto.StatsdConfig.Builder builder = MetricsUtils.getEmptyConfig()
                .addCountMetric(StatsdConfigProto.CountMetric.newBuilder()
                        .setId(MetricsUtils.COUNT_METRIC_ID)
                        .setBucket(StatsdConfigProto.TimeUnit.CTS)
                        .setWhat(whatMatcherId)
                        .setCondition(conditionId))
                .addAtomMatcher(plugMatcher)
                .addAtomMatcher(predicateStartMatcher)
                .addAtomMatcher(predicateEndMatcher)
                .addPredicate(p);

        TestCase testCase = new TestCase(builder.build(),
                "testEventCountWithCondition", 1000) {
            @Override
            void verifyReport(StatsLog.ConfigMetricsReport report) {
                assertEquals(1, report.getMetricsCount());
                com.android.os.StatsLog.StatsLogReport metricReport = report.getMetrics(0);
                assertEquals(MetricsUtils.COUNT_METRIC_ID, metricReport.getMetricId());
                assertTrue(metricReport.hasCountMetrics());

                com.android.os.StatsLog.StatsLogReport.CountMetricDataWrapper countData
                        = metricReport.getCountMetrics();

                assertTrue(countData.getDataCount() > 0);
                assertEquals(1, countData.getData(0).getBucketInfo(0).getCount());
            }
        };
        testMetrics(testCase);
    }

    private com.android.os.StatsLog.ConfigMetricsReport getReportCommon() throws Exception {
        com.android.os.StatsLog.ConfigMetricsReportList report = getReportList();
        List<com.android.os.StatsLog.ConfigMetricsReport> list = report.getReportsList();
        assertTrue(list.size() > 0);
        com.android.os.StatsLog.ConfigMetricsReport report1 = list.get(0);
        assertTrue(report1.hasUidMap());
        // One metric per test.
        return list.get(0);
    }
}
