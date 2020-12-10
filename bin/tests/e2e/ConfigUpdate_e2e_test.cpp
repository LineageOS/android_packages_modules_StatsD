// Copyright (C) 2020 The Android Open Source Project
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

#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <gtest/gtest.h>

#include "flags/flags.h"
#include "src/StatsLogProcessor.h"
#include "src/storage/StorageManager.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

using android::base::SetProperty;
using android::base::StringPrintf;
using namespace std;

// Tests that only run with the partial config update feature turned on.
namespace {
// Setup for test fixture.
class ConfigUpdateE2eTest : public ::testing::Test {
private:
    string originalFlagValue;
public:
    void SetUp() override {
        originalFlagValue = getFlagBool(PARTIAL_CONFIG_UPDATE_FLAG, "");
        string rawFlagName =
                StringPrintf("persist.device_config.%s.%s", STATSD_NATIVE_NAMESPACE.c_str(),
                             PARTIAL_CONFIG_UPDATE_FLAG.c_str());
        SetProperty(rawFlagName, "true");
    }

    void TearDown() override {
        string rawFlagName =
                StringPrintf("persist.device_config.%s.%s", STATSD_NATIVE_NAMESPACE.c_str(),
                             PARTIAL_CONFIG_UPDATE_FLAG.c_str());
        SetProperty(rawFlagName, originalFlagValue);
    }
};
}  // Anonymous namespace.

TEST_F(ConfigUpdateE2eTest, TestCountMetric) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");

    AtomMatcher syncStartMatcher = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = syncStartMatcher;
    AtomMatcher wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockAcquireMatcher;
    AtomMatcher wakelockReleaseMatcher = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockReleaseMatcher;
    AtomMatcher screenOnMatcher = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = screenOnMatcher;
    AtomMatcher screenOffMatcher = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = screenOffMatcher;

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    Predicate screenOnPredicate = CreateScreenIsOnPredicate();
    *config.add_predicate() = screenOnPredicate;

    Predicate* combination = config.add_predicate();
    combination->set_id(StringToId("ScreenOnAndHoldingWL)"));
    combination->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenOnPredicate, combination);
    addPredicateToPredicateCombination(holdingWakelockPredicate, combination);

    State uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    CountMetric countPersist =
            createCountMetric("CountSyncPerUidWhileScreenOnHoldingWLSliceProcessState",
                              syncStartMatcher.id(), combination->id(), {uidProcessState.id()});
    *countPersist.mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    // Links between sync state atom and condition of uid is holding wakelock.
    MetricConditionLink* links = countPersist.add_links();
    links->set_condition(holdingWakelockPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    MetricStateLink* stateLink = countPersist.add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::SYNC_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});

    CountMetric countChange = createCountMetric("Count*WhileScreenOn", syncStartMatcher.id(),
                                                screenOnPredicate.id(), {});
    CountMetric countRemove = createCountMetric("CountSync", syncStartMatcher.id(), nullopt, {});
    *config.add_count_metric() = countRemove;
    *config.add_count_metric() = countPersist;
    *config.add_count_metric() = countChange;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(TEN_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};

    // Initialize log events before update. Counts are for countPersist since others are simpler.
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 2 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND));
    events.push_back(CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 3 * NS_PER_SEC, app2Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 5 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Not counted.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 15 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 20 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 1.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 21 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Not counted.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 25 * NS_PER_SEC,
                                                attributionUids2, attributionTags2, "wl2"));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 30 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 2.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 31 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 1
    events.push_back(CreateReleaseWakelockEvent(bucketStartTimeNs + 35 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    // Do update. Add matchers/conditions in different order to force indices to change.
    StatsdConfig newConfig;
    newConfig.add_allowed_log_source("AID_ROOT");

    *newConfig.add_atom_matcher() = screenOnMatcher;
    *newConfig.add_atom_matcher() = screenOffMatcher;
    *newConfig.add_atom_matcher() = syncStartMatcher;
    *newConfig.add_atom_matcher() = wakelockAcquireMatcher;
    *newConfig.add_atom_matcher() = wakelockReleaseMatcher;
    *newConfig.add_predicate() = *combination;
    *newConfig.add_predicate() = holdingWakelockPredicate;
    *newConfig.add_predicate() = screenOnPredicate;
    *newConfig.add_state() = uidProcessState;

    countChange.set_what(screenOnMatcher.id());
    *newConfig.add_count_metric() = countChange;
    CountMetric countNew = createCountMetric("CountWlWhileScreenOn", wakelockAcquireMatcher.id(),
                                             screenOnPredicate.id(), {});
    *newConfig.add_count_metric() = countNew;
    *newConfig.add_count_metric() = countPersist;

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;
    processor->OnConfigUpdated(updateTimeNs, key, newConfig);

    // Send events after the update. Counts reset to 0 since this is a new bucket.
    events.clear();
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 65 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Not counted.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 66 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 1.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + 70 * NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl1"));
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 75 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 80 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Not counted.
    events.push_back(
            CreateScreenStateChangedEvent(bucketStartTimeNs + 85 * NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON));
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 90 * NS_PER_SEC, attributionUids1,
                                          attributionTags1, "sync_name"));  // Counted. uid1 = 1.
    events.push_back(CreateSyncStartEvent(bucketStartTimeNs + 11 * NS_PER_SEC, attributionUids2,
                                          attributionTags2, "sync_name"));  // Counted. uid2 = 2.
    // Flushes bucket.
    events.push_back(CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + NS_PER_SEC,
                                                attributionUids1, attributionTags1, "wl2"));
    // Send log events to StatsLogProcessor.
    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }
    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs + 10 * NS_PER_SEC;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 2);

    // Report from before update.
    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 3);
    // Count syncs. There were 5 syncs before the update.
    StatsLogReport countRemoveBefore = report.metrics(0);
    EXPECT_EQ(countRemoveBefore.metric_id(), countRemove.id());
    EXPECT_TRUE(countRemoveBefore.has_count_metrics());
    ASSERT_EQ(countRemoveBefore.count_metrics().data_size(), 1);
    auto data = countRemoveBefore.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 5);

    // Uid 1 had 2 syncs, uid 2 had 1 sync.
    StatsLogReport countPersistBefore = report.metrics(1);
    EXPECT_EQ(countPersistBefore.metric_id(), countPersist.id());
    EXPECT_TRUE(countPersistBefore.has_count_metrics());
    StatsLogReport::CountMetricDataWrapper countMetrics;
    sortMetricDataByDimensionsValue(countPersistBefore.count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 2);
    data = countMetrics.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 2);

    data = countMetrics.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 1);

    // Counts syncs while screen on. There were 4 before the update.
    StatsLogReport countChangeBefore = report.metrics(2);
    EXPECT_EQ(countChangeBefore.metric_id(), countChange.id());
    EXPECT_TRUE(countChangeBefore.has_count_metrics());
    ASSERT_EQ(countChangeBefore.count_metrics().data_size(), 1);
    data = countChangeBefore.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), bucketStartTimeNs, updateTimeNs, 4);

    // Report from after update.
    report = reports.reports(1);
    ASSERT_EQ(report.metrics_size(), 3);
    // Count screen on while screen is on. There was 1 after the update.
    StatsLogReport countChangeAfter = report.metrics(0);
    EXPECT_EQ(countChangeAfter.metric_id(), countChange.id());
    EXPECT_TRUE(countChangeAfter.has_count_metrics());
    ASSERT_EQ(countChangeAfter.count_metrics().data_size(), 1);
    data = countChangeAfter.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);

    // Count wl acquires while screen on. There were 2, one in each bucket.
    StatsLogReport countNewAfter = report.metrics(1);
    EXPECT_EQ(countNewAfter.metric_id(), countNew.id());
    EXPECT_TRUE(countNewAfter.has_count_metrics());
    ASSERT_EQ(countNewAfter.count_metrics().data_size(), 1);
    data = countNewAfter.count_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 2);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);
    ValidateCountBucket(data.bucket_info(1), bucketStartTimeNs + bucketSizeNs, dumpTimeNs, 1);

    // Uid 1 had 1 sync, uid 2 had 2 syncs.
    StatsLogReport countPersistAfter = report.metrics(2);
    EXPECT_EQ(countPersistAfter.metric_id(), countPersist.id());
    EXPECT_TRUE(countPersistAfter.has_count_metrics());
    countMetrics.Clear();
    sortMetricDataByDimensionsValue(countPersistAfter.count_metrics(), &countMetrics);
    ASSERT_EQ(countMetrics.data_size(), 2);
    data = countMetrics.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 1);

    data = countMetrics.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::SYNC_STATE_CHANGED, app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    ValidateCountBucket(data.bucket_info(0), updateTimeNs, bucketStartTimeNs + bucketSizeNs, 2);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhat) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    *config.add_predicate() = holdingWakelockPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    // Create a wakelock acquire, causing the condition to be true.
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    durationMetric->set_bucket(FIVE_MINUTES);

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 80 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 1:20
    processor->OnLogEvent(event.get());
    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  // 1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 1);
    DurationMetricData data = metricData.data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);

    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.start_bucket_elapsed_nanos(), updateTimeNs);
    EXPECT_EQ(bucketInfo.end_bucket_elapsed_nanos(), dumpTimeNs);
    EXPECT_EQ(bucketInfo.duration_nanos(), 20 * NS_PER_SEC);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    Predicate isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});
    *config.add_predicate() = isInBackgroundPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, app1Uid);  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 35 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 0:35
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    auto links = durationMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 73 * NS_PER_SEC, app2Uid);  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 2);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 24 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 17 * NS_PER_SEC);
}

TEST_F(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedState) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    auto uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    // Count metric. We don't care about this one. Only use it so the StateTracker gets persisted.
    CountMetric* countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("Tmp"));
    countMetric->set_what(config.atom_matcher(0).id());
    countMetric->add_slice_by_state(uidProcessState.id());
    // The metric is dimensioning by first attribution node and only by uid.
    *countMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    countMetric->set_bucket(FIVE_MINUTES);
    auto stateLink = countMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});
    config.add_no_report_metric(countMetric->id());

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 10 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 22 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC, app2Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:30
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->add_slice_by_state(uidProcessState.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    stateLink = durationMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config);

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 72 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 75 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);  // 1:15
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 3);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 15 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 9 * NS_PER_SEC);

    data = metricData.data(2);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 18 * NS_PER_SEC);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
