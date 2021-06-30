// Copyright (C) 2021 The Android Open Source Project
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

#include <android-modules-utils/sdk_level.h>
#include <gtest/gtest.h>

#include "src/StatsLogProcessor.h"
#include "src/flags/FlagProvider.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

using android::modules::sdklevel::IsAtLeastS;
using namespace std;

class KllMetricE2eAbTest : public ::testing::Test {
protected:
    void SetUp() override {
        if (shouldSkipTest()) {
            GTEST_SKIP() << skipReason();
        }

        originalFlagValue = FlagProvider::getInstance().getFlagString(KLL_METRIC_FLAG, FLAG_EMPTY);
        key = ConfigKey(123, 987);
        bucketStartTimeNs = getElapsedRealtimeNs();
        bucketSizeNs = TimeUnitToBucketSizeInMillis(TEN_MINUTES) * 1000000LL;
        whatMatcher = CreateScreenBrightnessChangedAtomMatcher();
        metric = createKllMetric("ScreenBrightness", whatMatcher, /*valueField=*/1,
                                 /*condition=*/nullopt);

        config.add_allowed_log_source("AID_ROOT");

        *config.add_atom_matcher() = whatMatcher;
        *config.add_kll_metric() = metric;

        events.push_back(CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 5 * NS_PER_SEC, 5));
        events.push_back(
                CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 15 * NS_PER_SEC, 15));
        events.push_back(
                CreateScreenBrightnessChangedEvent(bucketStartTimeNs + 25 * NS_PER_SEC, 40));
    }

    void TearDown() override {
        if (originalFlagValue) {
            writeFlag(KLL_METRIC_FLAG, originalFlagValue.value());
        }
    }

    virtual bool shouldSkipTest() const = 0;

    virtual string skipReason() const = 0;

    ConfigKey key;
    uint64_t bucketStartTimeNs;
    uint64_t bucketSizeNs;
    AtomMatcher whatMatcher;
    KllMetric metric;
    StatsdConfig config;
    vector<unique_ptr<LogEvent>> events;

    std::optional<string> originalFlagValue;
};

class KllMetricE2eAbTestSPlus : public KllMetricE2eAbTest {
    bool shouldSkipTest() const override {
        return !IsAtLeastS();
    }

    string skipReason() const override {
        return "Skipping KllMetricE2eAbTestSPlus because device is not S+";
    }
};

TEST_F(KllMetricE2eAbTestSPlus, TestFlagEnabled) {
    writeFlag(KLL_METRIC_FLAG, FLAG_TRUE);
    const sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    ASSERT_EQ(report.metrics_size(), 1);
    StatsLogReport metricReport = report.metrics(0);
    EXPECT_EQ(metricReport.metric_id(), metric.id());
    EXPECT_TRUE(metricReport.has_kll_metrics());
    ASSERT_EQ(metricReport.kll_metrics().data_size(), 1);
    KllMetricData data = metricReport.kll_metrics().data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);
    KllBucketInfo bucket = data.bucket_info(0);
    EXPECT_EQ(bucket.start_bucket_elapsed_nanos(), bucketStartTimeNs);
    EXPECT_EQ(bucket.end_bucket_elapsed_nanos(), bucketStartTimeNs + bucketSizeNs);
    EXPECT_EQ(bucket.sketches_size(), 1);
    EXPECT_EQ(metricReport.kll_metrics().skipped_size(), 0);
}

TEST_F(KllMetricE2eAbTestSPlus, TestFlagDisabled) {
    writeFlag(KLL_METRIC_FLAG, FLAG_FALSE);
    const sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    EXPECT_EQ(report.metrics_size(), 0);
}

struct KllServerFlagParam {
    string flagValue;
    string label;
};

class KllMetricE2eAbTestRMinus : public KllMetricE2eAbTest,
                                 public ::testing::WithParamInterface<KllServerFlagParam> {
    bool shouldSkipTest() const override {
        return IsAtLeastS();
    }

    string skipReason() const override {
        return "Skipping KllMetricE2eAbTestRMinus because device is not R-";
    }
};

INSTANTIATE_TEST_SUITE_P(
        KllMetricE2eAbTestRMinus, KllMetricE2eAbTestRMinus,
        testing::ValuesIn<KllServerFlagParam>({
                // Server flag values
                {FLAG_TRUE, "ServerFlagTrue"},
                {FLAG_FALSE, "ServerFlagFalse"},
        }),
        [](const testing::TestParamInfo<KllMetricE2eAbTestRMinus::ParamType>& info) {
            return info.param.label;
        });

TEST_P(KllMetricE2eAbTestRMinus, TestFlag) {
    writeFlag(KLL_METRIC_FLAG, GetParam().flagValue);
    const sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    for (auto& event : events) {
        processor->OnLogEvent(event.get());
    }

    uint64_t dumpTimeNs = bucketStartTimeNs + bucketSizeNs;
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);

    ConfigMetricsReport report = reports.reports(0);
    EXPECT_EQ(report.metrics_size(), 0);
}
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
