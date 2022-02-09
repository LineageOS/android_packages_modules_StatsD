// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <gtest/gtest.h>

#include <vector>

#include "flags/FlagProvider.h"
#include "src/StatsLogProcessor.h"
#include "src/state/StateTracker.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

// Setup for test fixture.
class EventMetricE2eTest : public ::testing::Test {
    void SetUp() override {
        FlagProvider::getInstance().overrideFuncs(&isAtLeastSFuncTrue);
    }

    void TearDown() override {
        FlagProvider::getInstance().resetOverrides();
    }
};

template <class T>
vector<T> AsVector(const google::protobuf::RepeatedField<T>& container) {
    return vector<T>(container.begin(), container.end());
}

vector<string> AsStringVector(const google::protobuf::RepeatedPtrField<string>& container) {
    return vector<string>(container.begin(), container.end());
}

TEST_F(EventMetricE2eTest, TestEventMetricDataAggregated) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    AtomMatcher wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockAcquireMatcher;

    EventMetric wakelockEventMetric =
            createEventMetric("EventWakelockStateChanged", wakelockAcquireMatcher.id(), nullopt);
    *config.add_event_metric() = wakelockEventMetric;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    // Initialize log events before update.
    std::vector<std::unique_ptr<LogEvent>> events;

    int app1Uid = 123;
    vector<int> attributionUids = {app1Uid};
    std::vector<string> attributionTags = {"App1"};

    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                attributionUids, attributionTags, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 20 * NS_PER_SEC,
                                                attributionUids, attributionTags, "wl1"));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 30 * NS_PER_SEC,
                                                attributionUids, attributionTags, "wl2"));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + 100 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    backfillAggregatedAtoms(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 1);
    StatsLogReport wakelockEventMetricReport = report.metrics(0);
    EXPECT_EQ(wakelockEventMetricReport.metric_id(), wakelockEventMetric.id());
    EXPECT_TRUE(wakelockEventMetricReport.has_event_metrics());
    ASSERT_EQ(wakelockEventMetricReport.event_metrics().data_size(), 3);
    auto data = wakelockEventMetricReport.event_metrics().data(0);
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 10 * NS_PER_SEC);
    EXPECT_EQ(data.atom().wakelock_state_changed().tag(), "wl1");
    data = wakelockEventMetricReport.event_metrics().data(1);
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 20 * NS_PER_SEC);
    EXPECT_EQ(data.atom().wakelock_state_changed().tag(), "wl1");
    data = wakelockEventMetricReport.event_metrics().data(2);
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 30 * NS_PER_SEC);
    EXPECT_EQ(data.atom().wakelock_state_changed().tag(), "wl2");
}

TEST_F(EventMetricE2eTest, TestRepeatedFieldsAndEmptyArrays) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    AtomMatcher testAtomReportedAtomMatcher =
            CreateSimpleAtomMatcher("TestAtomReportedMatcher", util::TEST_ATOM_REPORTED);
    *config.add_atom_matcher() = testAtomReportedAtomMatcher;

    EventMetric testAtomReportedEventMetric =
            createEventMetric("EventTestAtomReported", testAtomReportedAtomMatcher.id(), nullopt);
    *config.add_event_metric() = testAtomReportedEventMetric;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    // Initialize log events before update.
    std::vector<std::unique_ptr<LogEvent>> events;

    vector<int> intArray = {3, 6};
    vector<int64_t> longArray = {1000L, 10002L};
    vector<float> floatArray = {0.3f, 0.09f};
    vector<string> stringArray = {"str1", "str2"};
    int boolArrayLength = 2;
    bool boolArray[boolArrayLength];
    boolArray[0] = 1;
    boolArray[1] = 0;
    vector<bool> boolArrayVector = {1, 0};
    vector<int> enumArray = {TestAtomReported::ON, TestAtomReported::OFF};

    events.push_back(CreateTestAtomReportedEventVariableRepeatedFields(
            bucketStartTimeNs + 10 * NS_PER_SEC, intArray, longArray, floatArray, stringArray,
            boolArray, boolArrayLength, enumArray));
    events.push_back(CreateTestAtomReportedEventVariableRepeatedFields(
            bucketStartTimeNs + 20 * NS_PER_SEC, {}, {}, {}, {}, {}, 0, {}));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + 100 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    backfillAggregatedAtoms(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 1);
    StatsLogReport testAtomEventMetricReport = report.metrics(0);
    EXPECT_EQ(testAtomEventMetricReport.metric_id(), testAtomReportedEventMetric.id());
    EXPECT_TRUE(testAtomEventMetricReport.has_event_metrics());
    ASSERT_EQ(testAtomEventMetricReport.event_metrics().data_size(), 2);

    EventMetricData data = testAtomEventMetricReport.event_metrics().data(0);
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 10 * NS_PER_SEC);
    TestAtomReported atom = data.atom().test_atom_reported();
    EXPECT_EQ(AsVector<int>(atom.repeated_int_field()), intArray);
    EXPECT_EQ(AsVector<int64_t>(atom.repeated_long_field()), longArray);
    EXPECT_EQ(AsVector<float>(atom.repeated_float_field()), floatArray);
    EXPECT_EQ(AsStringVector(atom.repeated_string_field()), stringArray);
    EXPECT_EQ(AsVector<bool>(atom.repeated_boolean_field()), boolArrayVector);
    EXPECT_EQ(AsVector<int>(atom.repeated_enum_field()), enumArray);

    data = testAtomEventMetricReport.event_metrics().data(1);
    atom = data.atom().test_atom_reported();
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 20 * NS_PER_SEC);
    EXPECT_EQ(atom.repeated_int_field_size(), 0);
    EXPECT_EQ(atom.repeated_long_field_size(), 0);
    EXPECT_EQ(atom.repeated_float_field_size(), 0);
    EXPECT_EQ(atom.repeated_string_field_size(), 0);
    EXPECT_EQ(atom.repeated_boolean_field_size(), 0);
    EXPECT_EQ(atom.repeated_enum_field_size(), 0);
}

TEST_F(EventMetricE2eTest, TestMatchRepeatedFieldPositionFirst) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    AtomMatcher testAtomReportedStateFirstOnAtomMatcher =
            CreateTestAtomRepeatedStateFirstOnAtomMatcher();
    *config.add_atom_matcher() = testAtomReportedStateFirstOnAtomMatcher;

    EventMetric testAtomReportedEventMetric = createEventMetric(
            "EventTestAtomReported", testAtomReportedStateFirstOnAtomMatcher.id(), nullopt);
    *config.add_event_metric() = testAtomReportedEventMetric;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    // Initialize log events before update.
    std::vector<std::unique_ptr<LogEvent>> events;

    vector<int> enumArray1 = {TestAtomReported::OFF, TestAtomReported::ON};
    vector<int> enumArray2 = {TestAtomReported::ON, TestAtomReported::OFF};

    events.push_back(CreateTestAtomReportedEventVariableRepeatedFields(
            bucketStartTimeNs + 10 * NS_PER_SEC, {}, {}, {}, {}, {}, 0, enumArray1));
    events.push_back(CreateTestAtomReportedEventVariableRepeatedFields(
            bucketStartTimeNs + 20 * NS_PER_SEC, {}, {}, {}, {}, {}, 0, enumArray2));

    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + 100 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    backfillAggregatedAtoms(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 1);
    StatsLogReport testAtomEventMetricReport = report.metrics(0);
    EXPECT_EQ(testAtomEventMetricReport.metric_id(), testAtomReportedEventMetric.id());
    EXPECT_TRUE(testAtomEventMetricReport.has_event_metrics());
    ASSERT_EQ(testAtomEventMetricReport.event_metrics().data_size(), 1);

    EventMetricData data = testAtomEventMetricReport.event_metrics().data(0);
    EXPECT_EQ(data.elapsed_timestamp_nanos(), bucketStartTimeNs + 20 * NS_PER_SEC);
    TestAtomReported atom = data.atom().test_atom_reported();
    ASSERT_EQ(atom.repeated_int_field_size(), 0);
    ASSERT_EQ(atom.repeated_long_field_size(), 0);
    ASSERT_EQ(atom.repeated_float_field_size(), 0);
    ASSERT_EQ(atom.repeated_string_field_size(), 0);
    ASSERT_EQ(atom.repeated_boolean_field_size(), 0);
    ASSERT_EQ(atom.repeated_enum_field_size(), 2);
    EXPECT_EQ(atom.repeated_enum_field(0), TestAtomReported::ON);
    EXPECT_EQ(atom.repeated_enum_field(1), TestAtomReported::OFF);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
